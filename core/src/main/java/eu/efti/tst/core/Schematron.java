package eu.efti.tst.core;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmDestination;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.s9api.Xslt30Transformer;
import net.sf.saxon.s9api.streams.Steps;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.transform.stream.StreamSource;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Schematron validation with SchXslt2: the schema is transpiled to an XSLT stylesheet once, which is then applied
 * to every document and yields an SVRL report.
 */
final class Schematron {

    private static final String SVRL = "http://purl.oclc.org/dsdl/svrl";
    private static final String SCHEMATRON = "http://purl.oclc.org/dsdl/schematron";
    private static final String TRANSPILER = "/content/transpile.xsl";
    private static final QName SUCCESS_STATUS = new QName("successStatus");

    private final XsltExecutable validator;
    private final XdmValue successStatus;

    private Schematron(XsltExecutable validator, XdmValue successStatus) {
        this.validator = validator;
        this.successStatus = successStatus;
    }

    private static RuleViolation toViolation(XdmNode assertion) {
        String text = assertion.select(Steps.child(SVRL, "text")).findFirst()
                .map(node -> node.getStringValue().strip().replaceAll("\\s+", " "))
                .orElse("");
        return new RuleViolation(
                Objects.requireNonNullElse(assertion.getAttributeValue(new QName("role")), ""),
                text,
                Objects.requireNonNullElse(assertion.getAttributeValue(new QName("location")), ""));
    }

    /** A violation with an unknown role would be dropped from the report, so such roles are rejected up front. */
    private static void requireKnownRoles(Path schema, Set<String> testCaseIds) {
        Document document = Xml.parse(schema);
        List<String> unknown = Stream.of("assert", "report")
                .map(name -> document.getElementsByTagNameNS(SCHEMATRON, name))
                .flatMap(nodes -> IntStream.range(0, nodes.getLength()).mapToObj(i -> (Element) nodes.item(i)))
                .map(rule -> rule.getAttribute("role"))
                .filter(role -> !role.isBlank() && !testCaseIds.contains(role))
                .distinct()
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Schematron " + schema + " names unknown test cases in @role: " + unknown);
        }
    }

    /**
     * @param successStatus value of the schema parameter {@code $successStatus}
     * @param testCaseIds   the IDs an assertion may name in its role; an assertion without role belongs to T-DAT-01
     * @throws IllegalArgumentException if the schema cannot be read or compiled or an assertion names an unknown
     *                                  test case
     */
    static Schematron compile(Processor processor, Path schema, Collection<String> successStatus, Set<String> testCaseIds) {
        try {
            XsltCompiler compiler = processor.newXsltCompiler();
            URL transpiler = Objects.requireNonNull(Schematron.class.getResource(TRANSPILER), "SchXslt2 not on classpath");
            XdmDestination stylesheet = new XdmDestination();
            compiler.compile(new StreamSource(transpiler.toString()))
                    .load30()
                    .transform(new StreamSource(schema.toFile()), stylesheet);
            XsltExecutable validator = compiler.compile(stylesheet.getXdmNode().asSource());
            requireKnownRoles(schema, testCaseIds);
            return new Schematron(validator, XdmValue.makeSequence(successStatus.stream().map(XdmAtomicValue::new).toList()));
        } catch (SaxonApiException e) {
            throw new IllegalArgumentException("Cannot compile Schematron " + schema + ": " + e.getMessage(), e);
        }
    }

    List<RuleViolation> validate(XdmNode document) {
        try {
            Xslt30Transformer transformer = validator.load30();
            transformer.setStylesheetParameters(Map.of(SUCCESS_STATUS, successStatus));
            transformer.setGlobalContextItem(document);
            XdmDestination report = new XdmDestination();
            transformer.applyTemplates(document, report);
            return report.getXdmNode()
                    .select(Steps.descendant(SVRL, "failed-assert").cat(Steps.descendant(SVRL, "successful-report")))
                    .map(Schematron::toViolation)
                    .toList();
        } catch (SaxonApiException e) {
            throw new IllegalArgumentException("Schematron validation failed: " + e.getMessage(), e);
        }
    }
}
