package eu.efti.tst.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static eu.efti.tst.core.Fixtures.DATASET;
import static eu.efti.tst.core.Fixtures.FORMAT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubsetSchemaTest {

    private static final Set<String> S1 = Set.of("S1");
    private static final Set<String> S2 = Set.of("S2");
    private static final Set<String> S1_S2 = Set.of("S1", "S2");
    private static final String FULL = FORMAT.filterBySubsets(DATASET, S1_S2);
    private static final SubsetSchema.Annotations VOCABULARY = new SubsetSchema.Annotations("efti", "id", "parentId",
            "subset", "id", "status", Map.of("M", SubsetSchema.Status.MANDATORY, "SI", SubsetSchema.Status.STRUCTURAL));

    private static List<String> violations(String dataset, Set<String> subsets) {
        return FORMAT.compositionViolations(dataset, subsets);
    }

    /** An XSD with the root element {@code root} of type {@code t:Root} and the given type definitions. */
    private static Path schema(Path dir, String types) throws IOException {
        return Files.writeString(dir.resolve("schema.xsd"), """
                <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:t="urn:t" xmlns:o="urn:o"
                            targetNamespace="urn:t" elementFormDefault="qualified">
                  <xsd:import namespace="urn:o" schemaLocation="other.xsd"/>
                  <xsd:element name="root" type="t:Root"/>
                  %s
                </xsd:schema>""".formatted(types));
    }

    private static SubsetSchema load(Path dir, String types) throws IOException {
        Files.writeString(dir.resolve("other.xsd"), """
                <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:o">
                  <xsd:complexType name="Aggregate"><xsd:sequence><xsd:element name="a" type="xsd:token"/></xsd:sequence></xsd:complexType>
                  <xsd:complexType name="Coded"><xsd:simpleContent><xsd:extension base="xsd:token">
                    <xsd:attribute name="list" type="xsd:token"/></xsd:extension></xsd:simpleContent></xsd:complexType>
                </xsd:schema>""");
        return SubsetSchema.load(schema(dir, types), "root", VOCABULARY);
    }

    private static String element(String name, String type, String annotationId, String status) {
        return """
                <xsd:element name="%s" type="%s"><xsd:annotation><xsd:appinfo>
                  <efti id="%s" parentId="R"><subset id="X" status="%s"/></efti>
                </xsd:appinfo></xsd:annotation></xsd:element>""".formatted(name, type, annotationId, status);
    }

    @Test
    void filterKeepsOnlyElementsOfTheRequestedSubsets() {
        String s1 = FORMAT.filterBySubsets(DATASET, S1);
        String s2 = FORMAT.filterBySubsets(DATASET, S2);

        assertThat(s1).contains("<department>", "<plate>", "<hazard>", "<item>", "<count>")
                .doesNotContain("<receiver>", "<weight", "<equipment>", "<kind>", "<mode>", "<dangerous>");
        assertThat(s2).contains("<receiver>", "<weight", "<equipment>", "<kind>")
                .doesNotContain("<department>", "<hazard>", "<seq>", "<mode>");
        assertThat(FULL).contains("<department>", "<receiver>").doesNotContain("<mode>", "<dangerous>", "<seq>");
        assertThat(violations(s1, S1)).isEmpty();
        assertThat(violations(s2, S2)).isEmpty();
        assertThat(violations(FULL, S1_S2)).isEmpty();
    }

    @Test
    void filterResolvesAnnotationsOfSharedTypesPerContext() {
        // department belongs to S1 in the context of the sender only
        String dataset = DATASET.replace("<name>Receiver GmbH</name>", "<name>Receiver GmbH</name><department>Goods in</department>");

        assertThat(FORMAT.filterBySubsets(dataset, S1_S2)).containsOnlyOnce("<department>").contains("Shipping");
        assertThat(violations(dataset, S1_S2)).contains("/shipment/receiver/department is not part of the requested subsets [S1, S2]");
    }

    @Test
    void filterWithoutOrWithUnknownSubsetsKeepsOnlyTheRoot() {
        assertThat(FORMAT.filterBySubsets(DATASET, List.of())).doesNotContain("<sender", "<vehicle");
        assertThat(FORMAT.filterBySubsets(DATASET, List.of("XX99"))).doesNotContain("<sender", "<vehicle");
    }

    @Test
    void filterRejectsNonDataSets() {
        assertThatThrownBy(() -> FORMAT.filterBySubsets("<shipment/>", List.of("S1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Not a data set");
        assertThatThrownBy(() -> FORMAT.filterBySubsets("<shipment", List.of("S1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deriveIdentifiersYieldsTheIdentifierConsignment() {
        String identifiers = FORMAT.deriveIdentifiers(DATASET);

        assertThat(identifiers).contains("xmlns=\"urn:tst:mini:identifiers\"", "AB123", "<mode>3</mode>", "<seq>1</seq>")
                .doesNotContain("urn:tst:mini:dataset", "<sender>", "<weight", "<item>");
    }

    @Test
    void deriveIdentifiersHandlesPrefixedDataSets() {
        String prefixed = """
                <d:shipment xmlns:d="urn:tst:mini:dataset">
                  <d:sender><d:name>X</d:name></d:sender>
                  <d:mode>3</d:mode><d:dangerous>0</d:dangerous>
                </d:shipment>""";

        String identifiers = FORMAT.deriveIdentifiers(prefixed);

        assertThat(identifiers).doesNotContain("urn:tst:mini:dataset", "sender");
        assertThat(FORMAT.identifiers(identifiers).get(IdentifierType.MODE_OF_TRANSPORT)).containsExactly("3");
    }

    @Test
    void elementsOutsideTheRequestedSubsetsAreViolations() {
        assertThat(violations(FULL, S1)).containsExactly(
                "/shipment/receiver is not part of the requested subsets [S1]",
                "/shipment/weight is not part of the requested subsets [S1]",
                "/shipment/item/kind is not part of the requested subsets [S1]",
                "/shipment/equipment is not part of the requested subsets [S1]");
        // an element of another subset (S3) stays a violation for S1 and S2, even if it is an identifier
        assertThat(violations(FULL.replace("</shipment>", "<mode>3</mode></shipment>"), S1_S2))
                .containsExactly("/shipment/mode is not part of the requested subsets [S1, S2]");
    }

    @Test
    void elementsOutsideTheSubsetsAreListedWithoutTheirDescendants() {
        assertThat(FORMAT.elementsOutsideSubsets(FULL, S1))
                .containsExactly("/shipment/receiver", "/shipment/weight", "/shipment/item/kind", "/shipment/equipment");
        assertThat(FORMAT.elementsOutsideSubsets(FORMAT.filterBySubsets(DATASET, S1), S1)).isEmpty();
        assertThatThrownBy(() -> FORMAT.elementsOutsideSubsets("<other/>", S1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void elementMandatoryInOneSubsetAndOptionalInAnotherIsMandatoryIfEitherIsRequested() {
        String withoutSenderName = FULL.replace("<name>Sender Ltd</name>", "");

        assertThat(violations(FORMAT.filterBySubsets(withoutSenderName, S2), S2)).isEmpty();
        assertThat(violations(FORMAT.filterBySubsets(withoutSenderName, S1), S1))
                .containsExactly("/shipment/sender/name is mandatory in [S1] but missing");
        assertThat(violations(withoutSenderName, S1_S2)).containsExactly("/shipment/sender/name is mandatory in [S1] but missing");
    }

    @Test
    void emptyMandatoryElementsAreViolations() {
        String dataset = FULL.replace("<city>Berlin</city>", "<city> </city>");

        assertThat(violations(dataset, S1_S2)).containsExactly("/shipment/receiver/address/city is mandatory in [S2] but empty");
    }

    @Test
    void optionalAndConditionalElementsMayBeMissing() {
        String dataset = FULL.replace("<department>Shipping</department>", "").replace("<hazard>none</hazard>", "")
                .replace("<kind>PX</kind>", "");

        assertThat(violations(dataset, S1_S2)).isEmpty();
    }

    @Test
    void compositeSubsetStatusesAreNotEnforcedAsMandatory() {
        // the ID of a carried equipment has status C/M in S2
        String dataset = FULL.replace("<category>TE</category>", "<category>TE</category><carried/>");

        assertThat(violations(FORMAT.filterBySubsets(dataset, S2), S2)).isEmpty();
    }

    @Test
    void missingNestedAggregatesWithMandatoryElementsAreViolations() {
        assertThat(violations(FULL.replaceAll("(?s)<receiver>.*?</receiver>", ""), S1_S2))
                .containsExactly("/shipment/receiver holds mandatory elements of [S2] but is missing");
        assertThat(violations(FULL.replaceAll("(?s)<address>\\s*<street>Side.*?</address>", ""), S1_S2))
                .containsExactly("/shipment/receiver/address holds mandatory elements of [S2] but is missing");
        assertThat(violations(FULL.replaceAll("(?s)<vehicle>.*?</vehicle>", ""), S1_S2))
                .containsExactly("/shipment/vehicle holds mandatory elements of [S1, S2] but is missing");
    }

    @Test
    void repeatableAggregatesMayBeMissing() {
        String dataset = FULL.replaceAll("(?s)<item>.*?</item>", "").replaceAll("(?s)<equipment>.*?</equipment>", "");

        assertThat(violations(dataset, S1_S2)).isEmpty();
    }

    @Test
    void siblingsWithIdenticalContentAreViolationsRegardlessOfIndentation() {
        String identical = FULL.replaceAll("(?s)(<item>.*?</item>)", "$1$1");
        String reindented = FULL.replaceAll("(?s)(<item>.*?</item>)", "$1<item><count>3</count><kind>PX</kind></item>");
        String distinct = FULL.replaceAll("(?s)(<item>.*?</item>)", "$1<item><count>2</count><kind>PX</kind></item>");
        String duplicate = "/shipment/item occurs more than once with identical content";

        assertThat(violations(identical, S1_S2)).containsExactly(duplicate);
        assertThat(violations(reindented, S1_S2)).containsExactly(duplicate);
        assertThat(violations(distinct, S1_S2)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "<xsd:sequence><xsd:element ref='t:other'/></xsd:sequence> | element reference t:other",
            "<xsd:choice><xsd:element name='a' type='xsd:token'/></xsd:choice> | xsd:choice in type Root",
            "<xsd:sequence><xsd:group ref='t:g'/></xsd:sequence> | xsd:group in type Root",
            "<xsd:sequence><xsd:any/></xsd:sequence> | xsd:any in type Root",
            "<xsd:complexContent><xsd:extension base='t:Base'/></xsd:complexContent> | xsd:complexContent in type Root",
            "<xsd:sequence><xsd:element name='a' type='o:Aggregate'/></xsd:sequence> | imported complex type o:Aggregate",
            "<xsd:sequence><xsd:element name='a' type='t:Missing'/></xsd:sequence> | Type t:Missing of element a is not defined",
            "<xsd:sequence><xsd:element name='a' type='t:Root'/></xsd:sequence> | Type Root is recursive"})
    void loadFailsForUnsupportedConstructs(String content, String message, @TempDir Path dir) {
        assertThatThrownBy(() -> load(dir, "<xsd:complexType name='Root'>" + content + "</xsd:complexType>"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }

    @Test
    void loadFailsForAmbiguousAnnotations(@TempDir Path dir) {
        String twoAnnotations = """
                <xsd:complexType name="Root"><xsd:sequence>
                  <xsd:element name="a" type="xsd:token"><xsd:annotation><xsd:appinfo>
                    <efti id="A1" parentId="P1"><subset id="X" status="M"/></efti>
                    <efti id="A2" parentId="P2"><subset id="X" status="M"/></efti>
                  </xsd:appinfo></xsd:annotation></xsd:element>
                </xsd:sequence></xsd:complexType>""";

        assertThatThrownBy(() -> load(dir, twoAnnotations)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Element a of type Root has 2 subset annotations");
    }

    @Test
    void loadSupportsAnonymousTypesRepeatedSequencesAndImportedSimpleContent(@TempDir Path dir) throws IOException {
        String types = """
                <xsd:complexType name="Root"><xsd:sequence maxOccurs="unbounded">
                  %s
                  <xsd:element name="b"><xsd:annotation><xsd:appinfo>
                      <efti id="B" parentId="R"><subset id="X" status="SI"/></efti>
                    </xsd:appinfo></xsd:annotation>
                    <xsd:complexType><xsd:sequence>
                      <xsd:element name="c" type="xsd:token"><xsd:annotation><xsd:appinfo>
                        <efti id="C" parentId="B"><subset id="X" status="M"/></efti>
                      </xsd:appinfo></xsd:annotation></xsd:element>
                      <xsd:element name="d" type="xsd:token"/>
                    </xsd:sequence></xsd:complexType>
                  </xsd:element>
                </xsd:sequence></xsd:complexType>""".formatted(element("a", "o:Coded", "A", "M"));
        SubsetSchema schema = load(dir, types);
        var root = Xml.parse("<root xmlns='urn:t'><a list='1'>x</a><b><c>y</c><d>z</d></b></root>").getDocumentElement();

        assertThat(schema.compositionViolations(Xml.parse("<root xmlns='urn:t'><a>x</a></root>").getDocumentElement(),
                Set.of("X"))).isEmpty();
        assertThat(schema.compositionViolations(root, Set.of("X")))
                .containsExactly("/root/b/d is not part of the requested subsets [X]");
    }
}
