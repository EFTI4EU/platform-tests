package eu.efti.tst.runner;

import eu.efti.tst.core.CheckedMessage;
import eu.efti.tst.core.ConformanceChecks;
import eu.efti.tst.core.ConformanceContext;
import eu.efti.tst.core.Format;
import eu.efti.tst.core.MessageHistory;
import eu.efti.tst.core.MessageRecord;
import eu.efti.tst.core.ParsedMessage;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.Xslt30Transformer;
import net.sf.saxon.s9api.XsltExecutable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Shared test data: the real format folder, the DS1 data set and message builders using the format's templates and
 * helpers, so that the tests name no element of the format.
 */
final class Fixtures {

    static final Path FORMAT_DIR = Path.of("..", "formats", "ri-v0.5");
    static final Format FORMAT = Format.load(FORMAT_DIR);
    static final DatasetCatalogue CATALOGUE = DatasetCatalogue.ofFormat(FORMAT_DIR);
    static final DatasetCatalogue.Dataset DS1_CONFIG = CATALOGUE.select(List.of("DS1")).getFirst();
    static final String DS1 = FORMAT.testData(DS1_CONFIG.testData());
    static final String DS1_CORRECTED = DS1.replace(DS1_CONFIG.transportMeansTypo(), DS1_CONFIG.transportMeansCorrected());
    static final String GATE = "DE-GATE-TEST";
    static final String PLATFORM = "DE-PF-TEST-0042";
    static final String GATE_PARTY = "tstgate";
    static final String PLATFORM_PARTY = "pseudoplatform";
    static final String DATASET_ID = "0b6f3f3e-3c1a-4f6e-9d4b-2a7c5e8f1a90";
    static final ConformanceContext CONTEXT = new ConformanceContext(GATE, PLATFORM, Duration.ofSeconds(60),
            ConformanceContext.Mode.TEST_RUN);
    static final Instant T0 = Instant.parse("2026-09-15T07:00:00Z");
    static final DatasetPlan DS1_PLAN = plan("DS1");
    private static final XsltExecutable CONSIGNEE_SIGNATURE =
            compile(FORMAT_DIR.resolve("platform").resolve("consignee-signature.xsl"));

    private Fixtures() {
    }

    static DatasetPlan plan(String key) {
        return DatasetPlan.load(CATALOGUE.select(List.of(key)).getFirst(), FORMAT);
    }

    private static XsltExecutable compile(Path xslt) {
        try {
            return new Processor(false).newXsltCompiler().compile(new StreamSource(xslt.toFile()));
        } catch (SaxonApiException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String serialize(Document document) {
        try {
            StringWriter xml = new StringWriter();
            TransformerFactory.newInstance().newTransformer().transform(new DOMSource(document), new StreamResult(xml));
            return xml.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The element and its descendant elements, in document order. */
    private static List<Element> elements(Element root) {
        NodeList descendants = root.getElementsByTagNameNS("*", "*");
        return Stream.concat(Stream.of(root),
                IntStream.range(0, descendants.getLength()).mapToObj(i -> (Element) descendants.item(i))).toList();
    }

    /** Whether the element has no child elements and holds the value. */
    private static boolean holds(Element element, String value) {
        return element.getElementsByTagNameNS("*", "*").getLength() == 0 && element.getTextContent().strip().equals(value);
    }

    /** Checks and appends the message as the engine does. */
    static CheckedMessage exchange(MessageHistory history, MessageRecord record) {
        return ConformanceChecks.checkAndAppend(record, history, CONTEXT);
    }

    static MessageRecord toPlatform(String payload, Instant timestamp) {
        return new MessageRecord(UUID.randomUUID() + "@gate", timestamp, MessageRecord.Direction.TO_PLATFORM,
                GATE_PARTY, PLATFORM_PARTY, UUID.randomUUID().toString(), null, payload);
    }

    /** A message of the platform in a new conversation, referring to no message (e.g. an identifier upload). */
    static MessageRecord fromPlatform(String payload, Instant timestamp) {
        return new MessageRecord(UUID.randomUUID() + "@platform", timestamp, MessageRecord.Direction.FROM_PLATFORM,
                PLATFORM_PARTY, GATE_PARTY, UUID.randomUUID().toString(), null, payload);
    }

    /** A response of the platform to the request, in its conversation and referring to it, as a platform must. */
    static MessageRecord answer(ParsedMessage request, String payload, Instant timestamp) {
        return new MessageRecord(UUID.randomUUID() + "@platform", timestamp, MessageRecord.Direction.FROM_PLATFORM,
                PLATFORM_PARTY, GATE_PARTY, request.record().conversationId(), request.record().as4MessageId(), payload);
    }

    static String dataRequest(String template, String requestId, String datasetId, String subsetIds) {
        return FORMAT.render(template, Map.of("requestId", requestId, "gateId", GATE, "platformId", PLATFORM,
                "datasetId", datasetId, "subsetIds", subsetIds));
    }

    static String followUp(String template, String requestId, String referencedRequestId) {
        return FORMAT.render(template, Map.of("requestId", requestId, "referencedRequestId", referencedRequestId,
                "gateId", GATE, "platformId", PLATFORM, "datasetId", DATASET_ID, "message", "Please check the plate"));
    }

    static String dataResponse(String requestId, String dataset, List<String> subsets) {
        return FORMAT.render("data-response-success", Map.of("requestId", requestId,
                "dataset", FORMAT.filterBySubsets(dataset, subsets)));
    }

    static String errorResponse(String template, String requestId, String description) {
        return FORMAT.render(template, Map.of("requestId", requestId, "status", FORMAT.notFoundStatus(), "description", description));
    }

    static String identifierUpload(String datasetId, String dataset) {
        return FORMAT.render("identifier-upload", Map.of("requestId", UUID.randomUUID().toString(), "datasetId", datasetId,
                "identifiers", FORMAT.deriveIdentifiers(dataset)));
    }

    /** The data set after the consignee signature (end of transport), as the format's pseudo platform helper sets it. */
    static String delivered(String dataset) {
        try {
            Xslt30Transformer transformer = CONSIGNEE_SIGNATURE.load30();
            transformer.setStylesheetParameters(Map.of(new QName("signatureTime"),
                    new XdmAtomicValue(ZonedDateTime.parse("2026-09-16T14:00:00+02:00"))));
            StringWriter xml = new StringWriter();
            transformer.transform(new StreamSource(new StringReader(dataset)),
                    CONSIGNEE_SIGNATURE.getProcessor().newSerializer(xml));
            return xml.toString();
        } catch (SaxonApiException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The XML without the element holding the value, e.g. to drop a value of the test data. */
    static String without(String xml, String value) {
        Document document = parse(xml);
        List<Element> holding = elements(document.getDocumentElement()).stream().filter(element -> holds(element, value)).toList();
        if (holding.size() != 1) {
            throw new IllegalArgumentException(holding.size() + " elements hold " + value);
        }
        holding.getFirst().getParentNode().removeChild(holding.getFirst());
        return serialize(document);
    }

    /** The XML without the siblings of the element holding the value, e.g. to drop the other fields of an identifier. */
    static String withoutSiblingsOf(String xml, String value) {
        Document document = parse(xml);
        Element holding = elements(document.getDocumentElement()).stream().filter(element -> holds(element, value)).findFirst().orElseThrow();
        Element parent = (Element) holding.getParentNode();
        elements(parent).stream().filter(element -> element.getParentNode() == parent && element != holding).forEach(parent::removeChild);
        return serialize(document);
    }

    /**
     * The response with a copy of the request's UIL, i.e. of the innermost element holding both its gate and its
     * platform identifier, as first child of its root element.
     */
    static String withUilOf(ParsedMessage request, String response) {
        Element uil = elements(parse(request.record().payload()).getDocumentElement()).stream()
                .filter(element -> elements(element).stream().anyMatch(child -> holds(child, request.gateId().orElseThrow()))
                        && elements(element).stream().anyMatch(child -> holds(child, request.platformId().orElseThrow())))
                .reduce((outer, inner) -> inner)
                .orElseThrow();
        Document document = parse(response);
        Element root = document.getDocumentElement();
        root.insertBefore(document.importNode(uil, true), root.getFirstChild());
        return serialize(document);
    }

    /** The message as parsed by a format that exposes the gate and platform identifiers of a UIL in it. */
    static ParsedMessage withUil(ParsedMessage message, String gateId, String platformId) {
        return new ParsedMessage(message.record(), message.kind(), message.malformation(), message.requestId(),
                message.referencedRequestId(), Optional.of(gateId), Optional.of(platformId), message.datasetId(),
                message.status(), message.success(), message.description(), message.message(), message.subsetIds(),
                message.dataset(), message.identifiers());
    }
}
