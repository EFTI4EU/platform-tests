package eu.efti.tst.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static eu.efti.tst.core.Fixtures.DATASET;
import static eu.efti.tst.core.Fixtures.DATASET_ID;
import static eu.efti.tst.core.Fixtures.FORMAT;
import static eu.efti.tst.core.Fixtures.GATE;
import static eu.efti.tst.core.Fixtures.PLATFORM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormatTest {

    private static final Map<String, String> ALL_VALUES = Map.ofEntries(
            Map.entry("requestId", "req-1"), Map.entry("referencedRequestId", "req-0"), Map.entry("gateId", GATE),
            Map.entry("platformId", PLATFORM), Map.entry("datasetId", DATASET_ID), Map.entry("subsetIds", "S1 S2"),
            Map.entry("message", "Please check"), Map.entry("status", "404"), Map.entry("description", "Not found"),
            Map.entry("dataset", DATASET), Map.entry("identifiers", FORMAT.deriveIdentifiers(DATASET)));

    private static ParsedMessage parse(String xml) {
        return FORMAT.parse(Fixtures.toPlatform(xml, Instant.now()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<dataRequest", "", "<dataRequest/>", "<x:dataRequest xmlns:x='urn:other'/>"})
    void kindIsUnknownForMalformedOrForeignXml(String xml) {
        assertThat(parse(xml).kind()).isEqualTo(MessageKind.UNKNOWN);
    }

    @Test
    void parseReadsTheFieldsOfADataRequest() {
        ParsedMessage message = parse(Fixtures.dataRequest("req-1", "S1 S2"));

        assertThat(message.kind()).isEqualTo(MessageKind.DATA_REQUEST);
        assertThat(message.malformation()).isEmpty();
        assertThat(message.requestId()).contains("req-1");
        assertThat(message.gateId()).contains(GATE);
        assertThat(message.platformId()).contains(PLATFORM);
        assertThat(message.datasetId()).contains(DATASET_ID);
        assertThat(message.subsetIds()).containsExactly("S1", "S2");
        assertThat(message.status()).isEmpty();
        assertThat(message.success()).isFalse();
        assertThat(message.dataset()).isEmpty();
    }

    @Test
    void parseReadsTheReferencedRequestOfAFollowUp() {
        ParsedMessage message = parse(Fixtures.followUp("fu-1", "req-1"));

        assertThat(message.kind()).isEqualTo(MessageKind.FOLLOW_UP);
        assertThat(message.requestId()).contains("fu-1");
        assertThat(message.referencedRequestId()).contains("req-1");
        assertThat(message.message()).contains("Please check the plate");
    }

    @Test
    void parseReadsStatusDescriptionAndDataSetOfResponses() {
        ParsedMessage success = parse(Fixtures.dataResponse("req-1", DATASET));
        ParsedMessage error = parse(Fixtures.errorResponse("data-response-error", "req-2"));

        assertThat(success.status()).contains("200");
        assertThat(success.success()).isTrue();
        assertThat(success.description()).isEmpty();
        assertThat(success.dataset()).hasValueSatisfying(dataset -> assertThat(dataset).contains("AB123"));
        assertThat(error.status()).contains("404");
        assertThat(error.success()).isFalse();
        assertThat(error.description()).contains("Unknown data set");
        assertThat(error.dataset()).isEmpty();
    }

    @Test
    void parseReadsDataSetIdAndIdentifiersOfAnUpload() {
        ParsedMessage message = parse(Fixtures.identifierUpload(DATASET_ID, DATASET));

        assertThat(message.kind()).isEqualTo(MessageKind.IDENTIFIER_UPLOAD);
        assertThat(message.datasetId()).contains(DATASET_ID);
        assertThat(message.identifiers()).hasValueSatisfying(xml -> assertThat(xml).contains("AB123"));
    }

    @Test
    void parseNeverThrowsOnMalformedPayloads() {
        ParsedMessage message = parse("<dataResponse status='200'>");

        assertThat(message.kind()).isEqualTo(MessageKind.UNKNOWN);
        assertThat(message.malformation()).hasValueSatisfying(error -> assertThat(error).startsWith("Not well-formed XML"));
        assertThat(message.requestId()).isEmpty();
    }

    @Test
    void parseRejectsDoctypeDeclarations() {
        String xxe = "<?xml version='1.0'?><!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]>"
                + "<dataResponse xmlns='urn:tst:mini:messages' status='200' requestId='&e;'/>";

        assertThat(parse(xxe).malformation()).isPresent();
        assertThat(FORMAT.validateSchema(xxe)).isNotEmpty();
    }

    @Test
    void renderEscapesTextValuesAndRepeatsSections() {
        Map<String, String> values = new HashMap<>(ALL_VALUES);
        values.put("message", "a < b & \"c\"");
        values.put("subsetIds", " S1\nS2  ");

        String followUp = FORMAT.render("follow-up", values);
        String request = FORMAT.render("data-request", values);

        assertThat(parse(followUp).message()).contains("a < b & \"c\"");
        assertThat(parse(request).subsetIds()).containsExactly("S1", "S2");
        assertThat(FORMAT.validateSchema(followUp)).isEmpty();
    }

    @Test
    void renderInsertsRawFragmentsWithoutXmlDeclaration() {
        assertThat(DATASET).startsWith("<?xml");

        String response = Fixtures.dataResponse("req-1", DATASET);

        assertThat(response).containsOnlyOnce("<?xml");
        assertThat(FORMAT.validateSchema(response)).isEmpty();
    }

    @Test
    void renderFailsForMissingValuesAndUnknownTemplates() {
        assertThatThrownBy(() -> FORMAT.render("data-request", Map.of("requestId", "1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gateId");
        assertThatThrownBy(() -> FORMAT.render("no-such-template", ALL_VALUES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FORMAT.render("../format", ALL_VALUES))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid template name");
    }

    @Test
    void validateSchemaReportsAllErrorsWithPosition() {
        String xml = """
                <dataResponse xmlns="urn:tst:mini:messages" status="9999">
                  <unexpected/>
                </dataResponse>""";

        assertThat(FORMAT.validateSchema(xml))
                .hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(error -> assertThat(error).matches("\\d+:\\d+: .*"));
    }

    @Test
    void validateSchemaReportsMalformedXml() {
        assertThat(FORMAT.validateSchema("<dataResponse")).singleElement();
    }

    @Test
    void followUpAcknowledgementsMayEchoTheFollowUpOrTheReferencedRequest() {
        ParsedMessage followUp = parse(Fixtures.followUp("fu-1", "req-1"));
        ParsedMessage withoutRequestId = parse(FORMAT.render("follow-up-missing-request-id", ALL_VALUES));
        ParsedMessage request = parse(Fixtures.dataRequest("req-1", "S1"));

        assertThat(FORMAT.correlationKey(followUp)).isEqualTo(Optional.of("fu-1"));
        assertThat(FORMAT.acceptedEchoes(followUp)).containsExactly("fu-1", "req-1");
        assertThat(FORMAT.correlationKey(withoutRequestId)).isEmpty();
        assertThat(FORMAT.acceptedEchoes(withoutRequestId)).isEmpty();
        assertThat(FORMAT.acceptedEchoes(request)).containsExactly("req-1");
    }

    @Test
    void testDataSetsAreFilesOfTheFormat() {
        assertThat(FORMAT.testData("shipment")).isEqualTo(DATASET);
        assertThatThrownBy(() -> FORMAT.testData("other")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no test data set other");
        assertThatThrownBy(() -> FORMAT.testData("../shipment")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void descriptorValuesAreExposed() {
        assertThat(FORMAT.version()).isEqualTo("mini");
        assertThat(FORMAT.maxFollowUpTextLength()).isEqualTo(100);
        assertThat(FORMAT.invalidRequestStatus()).isEqualTo("400");
        assertThat(FORMAT.notFoundStatus()).isEqualTo("404");
    }

    @Test
    void successStatusOfTheDescriptorReachesTheSchematron(@TempDir Path dir) {
        Format format = Format.load(Fixtures.formatCopy(dir, "format.properties",
                properties -> properties.replace("status.success=200", "status.success=201")));
        String successWith201 = Fixtures.dataResponse("req-1", DATASET).replace("status=\"200\"", "status=\"201\"");

        assertThat(format.validateRules(successWith201)).isEmpty();
        assertThat(format.validateRules(Fixtures.dataResponse("req-1", DATASET))).extracting(RuleViolation::message)
                .contains("A data response with status '200' does not carry an error description.");
    }

    @Test
    void loadFailsForSubsetStatusCodesWithoutDeclaredMeaning(@TempDir Path dir) {
        Path format = Fixtures.formatCopy(dir, "format.properties", properties -> properties.replace(" C/M", ""));

        assertThatThrownBy(() -> Format.load(format)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'C/M'");
    }

    @Test
    void loadFailsWithIllegalArgumentForABrokenSchematron(@TempDir Path dir) {
        Path format = Fixtures.formatCopy(dir, "schematron/messages.sch", schematron -> schematron.replace("</sch:schema>", ""));

        assertThatThrownBy(() -> Format.load(format)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Schematron");
    }

    @Test
    void loadFailsForSchematronRolesThatAreNoTestCase(@TempDir Path dir) {
        Path format = Fixtures.formatCopy(dir, "schematron/messages.sch",
                schematron -> schematron.replace("role=\"T-DAT-03\"", "role=\"T-DAT-99\""));

        assertThatThrownBy(() -> Format.load(format)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[T-DAT-99]");
    }

    @Test
    void limitationsAreStatedPerTestCase(@TempDir Path dir) {
        Format format = Format.load(Fixtures.formatCopy(dir, "format.properties",
                properties -> properties + "\nlimitation.T-DAT-02=no timestamp\n"));

        assertThat(format.limitation("T-DAT-02")).contains("no timestamp");
        assertThat(format.limitation("T-DAT-03")).isEmpty();
    }

    @Test
    void loadFailsForLimitationsOfUnknownTestCases(@TempDir Path dir) {
        Path format = Fixtures.formatCopy(dir, "format.properties", properties -> properties + "\nlimitation.T-DAT-99=x\n");

        assertThatThrownBy(() -> Format.load(format)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[T-DAT-99]");
    }

    @Test
    void loadFailsForUnsupportedConstructsInTheDataSetSchema(@TempDir Path dir) {
        Path format = Fixtures.formatCopy(dir, "xsd/dataset.xsd", xsd -> xsd.replace(
                "<xsd:element name=\"hazard\" type=\"xsd:token\" minOccurs=\"0\">",
                "<xsd:any namespace=\"##other\" processContents=\"skip\" minOccurs=\"0\"/>"
                        + "<xsd:element name=\"hazard\" type=\"xsd:token\" minOccurs=\"0\">"));

        assertThatThrownBy(() -> Format.load(format)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataset.xsd").hasMessageContaining("xsd:any in type Shipment is not supported");
    }
}
