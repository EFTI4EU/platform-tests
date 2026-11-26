package eu.efti.tst.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static eu.efti.tst.core.Fixtures.CONTEXT;
import static eu.efti.tst.core.Fixtures.DATASET;
import static eu.efti.tst.core.Fixtures.DATASET_ID;
import static eu.efti.tst.core.Fixtures.FORMAT;
import static eu.efti.tst.core.Fixtures.GATE;
import static eu.efti.tst.core.Fixtures.MONITORING;
import static eu.efti.tst.core.Fixtures.PLATFORM;
import static eu.efti.tst.core.Fixtures.T0;
import static eu.efti.tst.core.Fixtures.answer;
import static eu.efti.tst.core.Fixtures.dataRequest;
import static eu.efti.tst.core.Fixtures.dataResponse;
import static eu.efti.tst.core.Fixtures.errorResponse;
import static eu.efti.tst.core.Fixtures.followUp;
import static eu.efti.tst.core.Fixtures.followUpAck;
import static eu.efti.tst.core.Fixtures.fromPlatform;
import static eu.efti.tst.core.Fixtures.reply;
import static eu.efti.tst.core.Fixtures.toPlatform;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class ConformanceChecksTest {

    private static final String FULL_RESPONSE = FORMAT.filterBySubsets(DATASET, List.of("S1", "S2"));
    private static final String DATA_ERROR = "data-response-error";

    private final MessageHistory history = new MessageHistory(FORMAT);

    /** Checks the message against the history and appends it, as a caller would. */
    private List<Finding> exchange(MessageRecord record) {
        return ConformanceChecks.checkAndAppend(record, history, CONTEXT).findings();
    }

    /** Sends a request to the platform and returns it. */
    private MessageRecord send(String payload, Instant timestamp) {
        MessageRecord request = toPlatform(payload, timestamp);
        exchange(request);
        return request;
    }

    /** Exchanges an identifier upload of the data set and the upload status of the gate side. */
    private List<Finding> upload(String datasetId, String dataset, boolean accepted) {
        MessageRecord upload = fromPlatform(Fixtures.identifierUpload(datasetId, dataset), T0);
        List<Finding> findings = exchange(upload);
        exchange(Fixtures.uploadStatus(upload, accepted));
        return findings;
    }

    private List<Finding> upload(String dataset, boolean accepted) {
        return upload(DATASET_ID, dataset, accepted);
    }

    /** Sends a data request and returns the findings of the platform's data response to it. */
    private List<Finding> requestAndRespond(String subsets, String dataset) {
        MessageRecord request = send(dataRequest("req-1", subsets), T0);
        return exchange(answer(request, dataResponse("req-1", dataset), T0.plusSeconds(1)));
    }

    private static List<String> ids(List<Finding> findings) {
        return findings.stream().map(Finding::testCaseId).toList();
    }

    private static List<String> details(List<Finding> findings) {
        return findings.stream().map(Finding::detail).toList();
    }

    private ParsedMessage message(int index) {
        return history.messages().get(index);
    }

    @Test
    void messagesToThePlatformYieldNoFindings() {
        assertThat(exchange(toPlatform("not even xml", T0))).isEmpty();
    }

    @Test
    void validIdentifierUploadPasses() {
        assertThat(exchange(fromPlatform(Fixtures.identifierUpload(DATASET_ID, DATASET), T0))).isEmpty();
    }

    @Test
    void messageWithBytesThatWereInvalidInItsEncodingFailsSyntax() {
        MessageRecord request = send(dataRequest("req-1", "S1"), T0);
        String undecodable = errorResponse(DATA_ERROR, "req-1").replace("Unknown", "Unbek\uFFFDnnt");

        assertThat(exchange(answer(request, undecodable, T0))).singleElement().satisfies(finding -> {
            assertThat(finding.testCaseId()).isEqualTo("T-DAT-01");
            assertThat(finding.detail()).contains("U+FFFD");
        });
    }

    @Test
    void malformedAndUnknownMessagesFailSyntax() {
        MessageRecord malformed = fromPlatform("<dataResponse", T0);
        MessageRecord unknown = fromPlatform("<somethingElse/>", T0);

        assertThat(exchange(malformed)).singleElement().satisfies(finding -> {
            assertThat(finding.testCaseId()).isEqualTo("T-DAT-01");
            assertThat(finding.as4MessageId()).isEqualTo(malformed.as4MessageId());
            assertThat(finding.detail()).startsWith("Not well-formed XML");
        });
        assertThat(exchange(unknown)).singleElement().satisfies(finding -> {
            assertThat(finding.testCaseId()).isEqualTo("T-DAT-01");
            assertThat(finding.detail()).isEqualTo("Not a message of format mini.");
        });
    }

    @Test
    void schemaViolationFailsSyntax() {
        String upload = Fixtures.identifierUpload(DATASET_ID, DATASET).replace("<dangerous>false", "<dangerous>maybe");

        assertThat(ids(exchange(fromPlatform(upload, T0)))).containsOnly("T-DAT-01").isNotEmpty();
    }

    @Test
    void failingRuleValidationKeepsTheSchemaViolations(@TempDir Path dir) {
        Format format = Format.load(Fixtures.formatCopy(dir, "schematron/messages.sch", schematron -> schematron.replace(
                "<sch:pattern id=\"identifier-set\">",
                "<sch:pattern><sch:rule context=\"/*\"><sch:assert test=\"error()\">x</sch:assert></sch:rule></sch:pattern>"
                        + "<sch:pattern id=\"identifier-set\">")));
        String upload = Fixtures.identifierUpload(DATASET_ID, DATASET).replace("<dangerous>false", "<dangerous>maybe");

        List<Finding> findings = ConformanceChecks.checkAndAppend(fromPlatform(upload, T0), new MessageHistory(format), CONTEXT)
                .findings();

        assertThat(ids(findings)).containsOnly("T-DAT-01");
        assertThat(details(findings)).anyMatch(detail -> detail.startsWith("Schema violation at "))
                .anyMatch(detail -> detail.startsWith("The check cannot be evaluated"));
    }

    @Test
    void datasetIdMustBeUuidVersion4() {
        assertThat(ids(exchange(fromPlatform(Fixtures.identifierUpload("DS1-0001", DATASET), T0)))).containsExactly("T-DAT-01");
        // version 1 UUID
        assertThat(ids(exchange(fromPlatform(Fixtures.identifierUpload("c232ab00-9414-11ec-b3c8-9f6bdeced846", DATASET), T0))))
                .containsExactly("T-DAT-01");
        assertThat(exchange(fromPlatform(Fixtures.identifierUpload(DATASET_ID.toUpperCase(), DATASET), T0))).isEmpty();
    }

    @Test
    void identifierUploadWithoutModeAndDangerousGoodsFailsT_DAT_03() {
        String upload = FORMAT.render("identifier-upload", Map.of("requestId", "u1", "datasetId", DATASET_ID,
                "identifiers", "<shipment xmlns='urn:tst:mini:identifiers'/>"));

        List<Finding> findings = exchange(fromPlatform(upload, T0));

        assertThat(ids(findings)).containsExactly("T-DAT-03", "T-DAT-03");
        assertThat(details(findings)).satisfiesExactlyInAnyOrder(
                detail -> assertThat(detail).startsWith("The identifier upload does not contain the mode of transport. (at "),
                detail -> assertThat(detail).startsWith("The identifier upload does not contain the dangerous goods indicator. (at "));
    }

    @Test
    void correlatedCompleteDataResponsePasses() {
        upload(DATASET, true);

        assertThat(requestAndRespond("S1 S2", FULL_RESPONSE)).isEmpty();
    }

    @Test
    void correlatedErrorResponsesPass() {
        MessageRecord request = send(dataRequest("req-1", "S1"), T0);
        MessageRecord followUp = send(followUp("fu-1", "req-1"), T0);

        assertThat(exchange(answer(request, errorResponse(DATA_ERROR, "req-1"), T0.plusSeconds(1)))).isEmpty();
        assertThat(exchange(answer(followUp, errorResponse("follow-up-ack-error", "fu-1"), T0.plusSeconds(1)))).isEmpty();
    }

    @Test
    void successResponseWithoutDataSetOrWithDescriptionFailsStatusLogic() {
        MessageRecord first = send(dataRequest("req-1", "S1"), T0);
        MessageRecord second = send(dataRequest("req-2", "S1"), T0);
        String withoutDataset = "<dataResponse xmlns='urn:tst:mini:messages' requestId='req-1' status='200'/>";
        String withDescription = "<dataResponse xmlns='urn:tst:mini:messages' requestId='req-2' status='200'>"
                + "<description>ok</description>" + FORMAT.filterBySubsets(DATASET, List.of("S1")) + "</dataResponse>";

        assertThat(exchange(answer(first, withoutDataset, T0))).singleElement()
                .satisfies(finding -> assertThat(finding.detail()).contains("does not carry the data set"));
        assertThat(exchange(answer(second, withDescription, T0))).singleElement()
                .satisfies(finding -> assertThat(finding.detail()).startsWith(
                        "A data response with success status '200' carries an error description. (at "))
                .extracting(Finding::testCaseId).isEqualTo("T-DAT-02");
    }

    @Test
    void errorResponseWithEmptyDescriptionOrWithDataSetFailsStatusLogic() {
        MessageRecord first = send(dataRequest("req-1", "S1"), T0);
        MessageRecord second = send(dataRequest("req-2", "S1"), T0);
        String withDataset = "<dataResponse xmlns='urn:tst:mini:messages' requestId='req-1' status='404'>"
                + "<description>missing</description>" + FORMAT.filterBySubsets(DATASET, List.of("S1")) + "</dataResponse>";
        String withEmptyDescription = "<dataResponse xmlns='urn:tst:mini:messages' requestId='req-2' status='404'>"
                + "<description> </description></dataResponse>";

        assertThat(exchange(answer(first, withDataset, T0))).singleElement()
                .satisfies(finding -> assertThat(finding.detail()).contains("carries a data set"))
                .extracting(Finding::testCaseId).isEqualTo("T-DAT-02");
        assertThat(exchange(answer(second, withEmptyDescription, T0))).singleElement()
                .satisfies(finding -> assertThat(finding.detail()).contains("does not carry an error description"))
                .extracting(Finding::testCaseId).isEqualTo("T-DAT-02");
    }

    @Test
    void errorAcknowledgementWithoutDescriptionPasses() {
        send(dataRequest("req-1", "S1"), T0);
        MessageRecord followUp = send(followUp("fu-1", "req-1"), T0);
        String ackWithoutDescription = "<followUpAck xmlns='urn:tst:mini:messages' requestId='fu-1' status='500'/>";

        assertThat(exchange(answer(followUp, ackWithoutDescription, T0))).isEmpty();
    }

    @Test
    void identifiersExposedInAResponseMustMatchTheKnownValues() {
        MessageRecord first = send(dataRequest("req-1", "S1"), T0);
        MessageRecord second = send(dataRequest("req-2", "S1"), T0);
        String response = "<dataResponse xmlns='urn:tst:mini:messages' requestId='%s' status='404'>"
                + "<uil><gate>%s</gate><platform>%s</platform><dataset>%s</dataset></uil>"
                + "<description>missing</description></dataResponse>";

        assertThat(details(exchange(answer(first, response.formatted("req-1", "XX-GATE", "XX-PF", "other"), T0))))
                .containsExactly("Gate identifier XX-GATE instead of DE-GATE-TEST.",
                        "Platform identifier XX-PF instead of DE-PF-TEST-0042.",
                        "Data set ID other instead of the requested " + DATASET_ID + ".");
        assertThat(exchange(answer(second, response.formatted("req-2", GATE, PLATFORM, DATASET_ID.toUpperCase()), T0)))
                .isEmpty();
    }

    @Test
    void compositionViolationsOfADataSetFailT_DAT_04() {
        String dataset = FULL_RESPONSE.replace("<city>Berlin</city>", "").replace("</shipment>", "<mode>3</mode></shipment>");

        assertThat(requestAndRespond("S1 S2", dataset)).extracting(Finding::testCaseId, Finding::detail).containsExactlyInAnyOrder(
                tuple("T-DAT-04", "/shipment/mode is not part of the requested subsets [S1, S2]"),
                tuple("T-DAT-04", "/shipment/receiver/address/city is mandatory in [S2] but missing"));
    }

    @Test
    void monitoringChecksOnlyTheMessageFormatAndTheExchangeRules() {
        String withoutMode = FORMAT.render("identifier-upload", Map.of("requestId", "u1", "datasetId", DATASET_ID,
                "identifiers", "<shipment xmlns='urn:tst:mini:identifiers'/>"));
        assertThat(ConformanceChecks.checkAndAppend(fromPlatform(withoutMode, T0), history, MONITORING).findings()).isEmpty();
        MessageRecord upload = fromPlatform(Fixtures.identifierUpload(DATASET_ID, DATASET), T0);
        ConformanceChecks.checkAndAppend(upload, history, MONITORING);
        ConformanceChecks.checkAndAppend(Fixtures.uploadStatus(upload, true), history, MONITORING);
        MessageRecord request = toPlatform(dataRequest("req-1", "S1 S2"), T0);
        ConformanceChecks.checkAndAppend(request, history, MONITORING);
        // missing mandatory element (T-DAT-04), deviating identifier (T-DAT-05), wrong data type (T-DAT-01)
        String dataset = FULL_RESPONSE.replace("<city>Berlin</city>", "").replace("AB123", "AB321")
                .replace("<weight unit=\"KGM\">12500</weight>", "<weight>heavy</weight>");
        MessageRecord late = answer(request, dataResponse("req-1", dataset), T0.plusSeconds(61));

        assertThat(ids(ConformanceChecks.checkAndAppend(late, history, MONITORING).findings()))
                .contains("T-DAT-01", "T-ALL-04").containsOnly("T-DAT-01", "T-ALL-04");
    }

    @Test
    void schemaInvalidDataSetInADataResponseFailsSyntaxAndIsCheckedNonetheless() {
        String dataset = FULL_RESPONSE.replace("<weight unit=\"KGM\">12500</weight>", "<weight>heavy</weight>")
                .replace("<plate>AB123</plate>", "");

        List<Finding> findings = requestAndRespond("S1 S2", dataset);

        assertThat(ids(findings)).contains("T-DAT-01").containsOnly("T-DAT-01", "T-DAT-04");
        assertThat(details(findings)).contains("/shipment/vehicle/plate is mandatory in [S1] but missing");
    }

    @Test
    void returnedIdentifiersMustMatchTheLatestUpload() {
        upload(DATASET, true);

        assertThat(requestAndRespond("S1 S2", FULL_RESPONSE.replace("AB123", "AB321"))).singleElement().satisfies(finding -> {
            assertThat(finding.testCaseId()).isEqualTo("T-DAT-05");
            assertThat(finding.detail()).isEqualTo("TRANSPORT_MEANS: registered [AB123|DE] but the data set contains [AB321|DE].");
        });
    }

    @Test
    void identifierConsistencyComparesTheCarrierAcceptanceDate() {
        upload(DATASET, true);

        assertThat(requestAndRespond("S1 S2", FULL_RESPONSE.replace("202609150800", "202609151012")))
                .extracting(Finding::testCaseId, Finding::detail).containsExactly(tuple("T-DAT-05",
                        "CARRIER_ACCEPTANCE_DATE: registered [202609150800] but the data set contains [202609151012]."));
    }

    @Test
    void identifierConsistencyUsesTheLatestAcceptedUploadAndSkipsIdentifiersOutsideTheSubsets() {
        upload(DATASET, true);
        upload(DATASET.replace("AB123", "AB321"), true);
        upload(DATASET.replace("AB123", "XX999"), false);

        // S2 holds neither mode of transport nor dangerous goods indicator, which the upload carries
        assertThat(requestAndRespond("S2", FORMAT.filterBySubsets(DATASET.replace("AB123", "AB321"), List.of("S2"))))
                .isEmpty();
    }

    @Test
    void registeredIdentifierMissingFromTheResponseIsReportedUnlessItsElementMayBeOmitted() {
        upload(DATASET, true);
        String withoutEquipment = FORMAT.filterBySubsets(DATASET, List.of("S2")).replaceFirst("(?s)<equipment>.*</equipment>", "");

        // the equipment ID is mandatory in S2, while the repeatable aggregate holding it may occur zero times for T-DAT-04
        assertThat(details(requestAndRespond("S2", withoutEquipment)))
                .containsExactly("TRANSPORT_EQUIPMENT: registered [EQ1|PL|TE|1] but the data set does not contain it.");
    }

    @Test
    void registeredIdentifierOptionalInAllRequestedSubsetsMayBeMissingFromTheResponse() {
        String delivered = DATASET.replace("<mode>", "<delivery><at>202609160800</at></delivery><mode>");
        upload(delivered, true);

        assertThat(requestAndRespond("S1 S2", FULL_RESPONSE)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"12500.000", "+12500", "012500", "12500.", "' 12500 '"})
    void roundTripComparesNumbersByValue(String weight) {
        assertThat(roundTripDifferences(FULL_RESPONSE.replace(">12500<", ">" + weight + "<"))).isEmpty();
    }

    @Test
    void roundTripReportsDifferentNumbersAndKeepsTokensExact() {
        assertThat(roundTripDifferences(FULL_RESPONSE.replace(">12500<", ">12500.5<")))
                .singleElement().asString().contains("expected [12500] but found [12500.5]");
        // the count is a token, where 03 and 3 are different values
        assertThat(roundTripDifferences(FULL_RESPONSE.replace("<count>3<", "<count>03<")))
                .singleElement().asString().contains("expected [3] but found [03]");
    }

    private List<String> roundTripDifferences(String dataset) {
        MessageRecord request = toPlatform(dataRequest("req-1", "S1 S2"), T0);
        history.append(request);
        MessageRecord response = answer(request, dataResponse("req-1", dataset), T0);
        history.append(response);
        return details(ConformanceChecks.checkRoundTrip(response, history, DATASET).findings());
    }

    @Test
    void identifierConsistencyComparesOnlyTheFieldsOfACompositeValueTheResponseCarries() {
        // the sequence number of the equipment belongs to S3 only, the plate is optional in S2
        upload(DATASET, true);
        MessageRecord request = send(dataRequest("req-2", "S1 S2"), T0);
        String withoutPlate = FORMAT.filterBySubsets(DATASET, List.of("S2")).replace("<plate>AB123</plate>", "");

        assertThat(FORMAT.identifiers(withoutPlate).get(IdentifierType.TRANSPORT_MEANS)).containsExactly("|DE");
        assertThat(requestAndRespond("S2", withoutPlate)).isEmpty();
        assertThat(details(exchange(answer(request, dataResponse("req-2", FULL_RESPONSE.replace("<country>PL", "<country>CZ")), T0))))
                .containsExactly("TRANSPORT_EQUIPMENT: registered [EQ1|PL|TE|1] but the data set contains [EQ1|CZ|TE|].");
    }

    @Test
    void identifierConsistencyReportsRepeatedIdentifiersThatDifferInNumber() {
        upload(DATASET, true);
        String twoEquipments = FULL_RESPONSE.replaceAll("(?s)(<equipment>.*?</equipment>)", "$1$1");

        assertThat(requestAndRespond("S1 S2", twoEquipments)).extracting(Finding::testCaseId, Finding::detail).contains(tuple(
                "T-DAT-05", "TRANSPORT_EQUIPMENT: registered [EQ1|PL|TE|1] but the data set contains [EQ1|PL|TE|, EQ1|PL|TE|]."));
    }

    @Test
    void identifierConsistencyReportsValuesTheRegistryDoesNotHold() {
        upload(DATASET.replaceAll("(?s)<equipment>.*?</equipment>", ""), true);

        assertThat(requestAndRespond("S1 S2", FULL_RESPONSE)).extracting(Finding::testCaseId, Finding::detail).containsExactly(
                tuple("T-DAT-05", "TRANSPORT_EQUIPMENT: registered [] but the data set contains [EQ1|PL|TE|]."));
    }

    @Test
    void identifierConsistencyFindsTheUploadWhateverTheCaseOfTheDataSetId() {
        upload(DATASET_ID.toUpperCase(), DATASET, true);

        assertThat(ids(requestAndRespond("S1 S2", FULL_RESPONSE.replace("AB123", "AB321")))).containsExactly("T-DAT-05");
    }

    @Test
    void identifierConsistencyIsSkippedWithoutAcceptedUpload() {
        exchange(fromPlatform(Fixtures.identifierUpload(DATASET_ID, DATASET), T0));

        assertThat(requestAndRespond("S1 S2", FULL_RESPONSE.replace("AB123", "AB321"))).isEmpty();
    }

    @Test
    void unevaluableIdentifierConsistencyYieldsAFindingInsteadOfAnException() {
        MessageRecord upload = fromPlatform(Fixtures.identifierUpload(DATASET_ID, DATASET)
                .replace("<dangerous>false", "<dangerous>maybe"), T0);
        exchange(upload);
        exchange(Fixtures.uploadStatus(upload, true));

        assertThat(requestAndRespond("S1 S2", FULL_RESPONSE)).singleElement().satisfies(finding -> {
            assertThat(finding.testCaseId()).isEqualTo("T-DAT-05");
            assertThat(finding.detail()).startsWith("The check cannot be evaluated");
        });
    }

    @Test
    void responseWithoutRefToMessageIdFailsCorrelationButIsPairedByItsEcho() {
        send(dataRequest("req-1", "S1"), T0);

        assertThat(exchange(fromPlatform(errorResponse(DATA_ERROR, "req-1"), T0))).extracting(Finding::testCaseId, Finding::detail)
                .containsExactly(tuple("T-ALL-03", "The response carries no AS4 RefToMessageId referring to the DATA_REQUEST it answers."));
        assertThat(history.responseTo(message(0))).isPresent();
    }

    @Test
    void responseReferringToNoRequestOfItsKindIsUncorrelated() {
        MessageRecord request = send(dataRequest("req-1", "S1"), T0);
        MessageRecord unknownReference = new MessageRecord("r1@platform", T0, MessageRecord.Direction.FROM_PLATFORM,
                PLATFORM, GATE, request.conversationId(), "unknown@gate", errorResponse(DATA_ERROR, "req-1"));

        assertThat(exchange(unknownReference)).extracting(Finding::testCaseId, Finding::detail).containsExactly(
                tuple("T-ALL-03", "The AS4 RefToMessageId unknown@gate refers to no DATA_REQUEST sent to the platform."));
        assertThat(exchange(answer(request, followUpAck("req-1"), T0))).extracting(Finding::detail).containsExactly(
                "The AS4 RefToMessageId " + request.as4MessageId() + " refers to no FOLLOW_UP sent to the platform.");
        assertThat(history.responseTo(message(0))).isEmpty();
    }

    @Test
    void responseWithoutReferenceEchoingAnUnknownRequestIdIsUnsolicited() {
        send(dataRequest("req-1", "S1"), T0);

        assertThat(details(exchange(fromPlatform(errorResponse(DATA_ERROR, "req-other"), T0)))).containsExactly(
                "The response carries no AS4 RefToMessageId referring to the DATA_REQUEST it answers.",
                "No DATA_REQUEST with request ID req-other was sent to the platform (unsolicited response or request ID not echoed).");
    }

    @Test
    void responseWithoutReferenceAndRequestIdOutsideTheConversationOfARequestIsUncorrelated() {
        send(dataRequest("req-1", "S1"), T0);
        String response = "<dataResponse xmlns='urn:tst:mini:messages' status='404'><description>x</description></dataResponse>";

        assertThat(exchange(fromPlatform(response, T0))).extracting(Finding::testCaseId, Finding::detail).contains(tuple(
                "T-ALL-03", "The response does not echo a request ID and was not sent in the AS4 conversation of a DATA_REQUEST."));
    }

    @Test
    void errorResponseWithoutRequestIdToARequestWithoutRequestIdPasses() {
        MessageRecord probe = send(Fixtures.dataRequestWithoutRequestId("S1"), T0);
        String response = "<dataResponse xmlns='urn:tst:mini:messages' status='400'><description>x</description></dataResponse>";

        assertThat(ids(exchange(answer(probe, response, T0.plusSeconds(1))))).doesNotContain("T-ALL-03");
        assertThat(history.responseTo(message(0))).isPresent();
    }

    @Test
    void errorResponseWithAGeneratedRequestIdToARequestWithoutRequestIdPasses() {
        MessageRecord probe = send(Fixtures.dataRequestWithoutRequestId("S1"), T0);

        assertThat(ids(exchange(answer(probe, errorResponse(DATA_ERROR, "generated-1"), T0.plusSeconds(1))))).doesNotContain("T-ALL-03");
        assertThat(history.responseTo(message(0))).isPresent();
    }

    @Test
    void responseMustEchoTheRequestIdOfTheRequestItRefersTo() {
        MessageRecord first = send(dataRequest("req-1", "S1"), T0);
        MessageRecord second = send(dataRequest("req-2", "S1"), T0);
        String withoutRequestId = "<dataResponse xmlns='urn:tst:mini:messages' status='404'><description>x</description></dataResponse>";

        assertThat(exchange(answer(first, withoutRequestId, T0))).extracting(Finding::testCaseId, Finding::detail).contains(tuple(
                "T-ALL-03", "The response does not echo the request ID of the DATA_REQUEST with request ID req-1 it answers (per its AS4 RefToMessageId)."));
        assertThat(exchange(answer(second, errorResponse(DATA_ERROR, "req-1"), T0))).extracting(Finding::testCaseId, Finding::detail)
                .containsExactly(tuple("T-ALL-03", "The response echoes request ID req-1 but answers the DATA_REQUEST with request ID req-2 (per its AS4 RefToMessageId)."));
    }

    @Test
    void responseOfTheWrongKindDoesNotAnswerARequest() {
        send(dataRequest("req-1", "S1"), T0);

        assertThat(ids(exchange(fromPlatform(errorResponse("follow-up-ack-error", "req-1"), T0))))
                .containsExactly("T-ALL-03", "T-ALL-03");
    }

    @Test
    void followUpAcknowledgementsSharingAnEchoArePairedByTheirReference() {
        MessageRecord first = send(followUp("fu-1", "req-1"), T0);
        MessageRecord second = send(followUp("fu-2", "req-1"), T0);
        MessageRecord third = send(followUp("fu-3", "req-1"), T0);
        // all acknowledgements echo the referenced data request and arrive out of order in new conversations
        MessageRecord thirdAck = reply(third, followUpAck("req-1"), T0.plusSeconds(1), UUID.randomUUID().toString());
        MessageRecord firstAck = reply(first, followUpAck("req-1"), T0.plusSeconds(2), UUID.randomUUID().toString());
        MessageRecord secondAck = reply(second, followUpAck("req-1"), T0.plusSeconds(3), UUID.randomUUID().toString());

        assertThat(exchange(thirdAck)).isEmpty();
        assertThat(exchange(firstAck)).isEmpty();
        assertThat(exchange(secondAck)).isEmpty();
        assertThat(history.responseTo(message(0))).map(ParsedMessage::record).contains(firstAck);
        assertThat(history.responseTo(message(1))).map(ParsedMessage::record).contains(secondAck);
        assertThat(history.responseTo(message(2))).map(ParsedMessage::record).contains(thirdAck);
        assertThat(exchange(reply(first, followUpAck("fu-1"), T0.plusSeconds(4), UUID.randomUUID().toString())))
                .extracting(Finding::testCaseId, Finding::detail)
                .containsExactly(tuple("T-ALL-03", "The FOLLOW_UP with request ID fu-1 was already answered."));
    }

    @Test
    void withoutReferenceFollowUpAcknowledgementsArePairedByConversationOrEcho() {
        send(followUp("fu-1", "req-1"), T0);
        MessageRecord second = send(followUp("fu-2", "req-1"), T0);
        MessageRecord secondAck = fromPlatform(followUpAck("req-1"), T0.plusSeconds(1), second.conversationId());
        MessageRecord firstAck = fromPlatform(followUpAck("fu-1"), T0.plusSeconds(2));
        String missingReference = "The response carries no AS4 RefToMessageId referring to the FOLLOW_UP it answers.";

        assertThat(details(exchange(secondAck))).containsExactly(missingReference);
        assertThat(details(exchange(firstAck))).containsExactly(missingReference);
        assertThat(history.responseTo(message(0))).map(ParsedMessage::record).contains(firstAck);
        assertThat(history.responseTo(message(1))).map(ParsedMessage::record).contains(secondAck);
    }

    @Test
    void withoutReferenceASharedConversationFallsBackToTheRequestId() {
        MessageRecord first = toPlatform(dataRequest("req-1", "S1"), T0, "shared");
        MessageRecord second = toPlatform(dataRequest("req-2", "S1"), T0, "shared");
        exchange(first);
        exchange(second);
        MessageRecord secondResponse = fromPlatform(errorResponse(DATA_ERROR, "req-2"), T0, "shared");

        assertThat(ids(exchange(secondResponse))).containsExactly("T-ALL-03");
        assertThat(ids(exchange(fromPlatform(errorResponse(DATA_ERROR, "req-1"), T0, "shared")))).containsExactly("T-ALL-03");
        assertThat(history.responseTo(message(1))).map(ParsedMessage::record).contains(secondResponse);
    }

    @Test
    void secondResponseToTheSameRequestFailsCorrelation() {
        MessageRecord request = send(dataRequest("req-1", "S1"), T0);
        exchange(answer(request, errorResponse(DATA_ERROR, "req-1"), T0));

        assertThat(exchange(answer(request, errorResponse(DATA_ERROR, "req-1"), T0))).singleElement()
                .satisfies(finding -> assertThat(finding.detail()).isEqualTo(
                        "The DATA_REQUEST with request ID req-1 was already answered."));
    }

    @Test
    void repeatedRequestIdsArePairedByReference() {
        MessageRecord first = send(dataRequest("req-1", "S1"), T0);
        MessageRecord second = send(dataRequest("req-1", "S1"), T0.plusSeconds(100));

        assertThat(exchange(answer(second, errorResponse(DATA_ERROR, "req-1"), T0.plusSeconds(110)))).isEmpty();
        assertThat(details(exchange(answer(first, errorResponse(DATA_ERROR, "req-1"), T0.plusSeconds(120)))))
                .containsExactly("Answered after 120.0 s, the limit is 60.0 s.");
    }

    @Test
    void withoutReferenceRepeatedRequestIdsAreAnsweredInOrder() {
        send(dataRequest("req-1", "S1"), T0);
        send(dataRequest("req-1", "S1"), T0.plusSeconds(100));

        assertThat(exchange(fromPlatform(errorResponse(DATA_ERROR, "req-1"), T0.plusSeconds(30)))).hasSize(1);
        assertThat(exchange(fromPlatform(errorResponse(DATA_ERROR, "req-1"), T0.plusSeconds(130)))).hasSize(1);
        assertThat(details(exchange(fromPlatform(errorResponse(DATA_ERROR, "req-1"), T0.plusSeconds(131)))))
                .contains("The DATA_REQUEST with request ID req-1 was already answered.");
    }

    @Test
    void reusedUploadRequestIdFailsUniqueness() {
        assertThat(exchange(fromPlatform(Fixtures.identifierUpload("upload-1", DATASET_ID, DATASET), T0))).isEmpty();
        assertThat(exchange(fromPlatform(Fixtures.identifierUpload("upload-1", DATASET_ID, DATASET), T0)))
                .extracting(Finding::testCaseId, Finding::detail)
                .containsExactly(tuple("T-ALL-03", "The request ID upload-1 was already used by an earlier identifier upload."));
    }

    @Test
    void concurrentResponsesToTheSameRequestAreCheckedAtomically() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 20; round++) {
                MessageHistory shared = new MessageHistory(FORMAT);
                MessageRecord request = toPlatform(dataRequest("req-1", "S1"), T0);
                ConformanceChecks.checkAndAppend(request, shared, CONTEXT);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<List<Finding>>> results = IntStream.range(0, threads)
                        .mapToObj(i -> answer(request, errorResponse(DATA_ERROR, "req-1"), T0))
                        .map(response -> executor.submit(() -> {
                            start.await();
                            return ConformanceChecks.checkAndAppend(response, shared, CONTEXT).findings();
                        }))
                        .toList();
                start.countDown();
                List<List<Finding>> findings = results.stream().map(result -> {
                    try {
                        return result.get();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();

                assertThat(findings).filteredOn(List::isEmpty).hasSize(1);
                assertThat(findings).filteredOn(list -> !list.isEmpty()).hasSize(threads - 1).allSatisfy(list ->
                        assertThat(list).singleElement().extracting(Finding::detail).asString().contains("already answered"));
                assertThat(shared.messages()).hasSize(threads + 1);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reusedAs4MessageIdFailsUniqueness() {
        MessageRecord first = fromPlatform(Fixtures.identifierUpload(DATASET_ID, DATASET), T0);
        exchange(first);
        MessageRecord second = new MessageRecord(first.as4MessageId(), T0.plusSeconds(1), first.direction(),
                first.fromParty(), first.toParty(), first.conversationId(), null, Fixtures.identifierUpload(DATASET_ID, DATASET));

        assertThat(exchange(second)).extracting(Finding::testCaseId, Finding::detail)
                .containsExactly(tuple("T-ALL-03", "The AS4 message ID " + first.as4MessageId() + " was already used."));
    }

    @Test
    void gateMessagesSentByThePlatformAreUnsolicited() {
        assertThat(ids(exchange(fromPlatform(dataRequest("req-1", "S1"), T0)))).containsExactly("T-ALL-03");
        assertThat(ids(exchange(fromPlatform(FORMAT.render("upload-status-success", Map.of("requestId", "x")), T0))))
                .containsExactly("T-ALL-03");
    }

    @Test
    void lateResponsesFailResponseTime() {
        MessageRecord request = send(dataRequest("req-1", "S1"), T0);
        MessageRecord followUp = send(followUp("fu-1", "req-1"), T0);

        assertThat(exchange(answer(request, errorResponse(DATA_ERROR, "req-1"), T0.plusMillis(61_500))))
                .extracting(Finding::testCaseId, Finding::detail)
                .containsExactly(tuple("T-ALL-04", "Answered after 61.5 s, the limit is 60.0 s."));
        assertThat(exchange(answer(followUp, followUpAck("fu-1"), T0.plusSeconds(120))))
                .extracting(Finding::testCaseId, Finding::detail)
                .containsExactly(tuple("T-ALL-04", "Answered after 120.0 s, the limit is 60.0 s."));
    }

    @Test
    void responsesAtTheLimitAreInTime() {
        MessageRecord request = send(dataRequest("req-1", "S1"), T0);
        MessageRecord followUp = send(followUp("fu-1", "req-1"), T0);

        assertThat(exchange(answer(request, errorResponse(DATA_ERROR, "req-1"), T0.plusSeconds(60)))).isEmpty();
        assertThat(exchange(answer(followUp, followUpAck("fu-1"), T0.plusSeconds(60)))).isEmpty();
    }

    @Test
    void checkOverdueReportsUnansweredDataRequestsAndUnacknowledgedFollowUps() {
        MessageRecord answered = toPlatform(dataRequest("req-1", "S1"), T0);
        MessageRecord unanswered = toPlatform(dataRequest("req-2", "S1"), T0);
        MessageRecord unacknowledged = toPlatform(followUp("fu-1", "req-1"), T0);
        MessageRecord notYetDue = toPlatform(dataRequest("req-3", "S1"), T0.plusSeconds(30));
        MessageRecord withoutRequestId = toPlatform(Fixtures.dataRequestWithoutRequestId("S1"), T0);
        List.of(answered, unanswered, unacknowledged, notYetDue, withoutRequestId).forEach(history::append);
        history.append(answer(answered, errorResponse(DATA_ERROR, "other"), T0.plusSeconds(1)));

        assertThat(ConformanceChecks.checkOverdue(history, T0.plusSeconds(61), CONTEXT))
                .extracting(Finding::testCaseId, Finding::as4MessageId, Finding::detail).containsExactly(
                        tuple("T-ALL-04", unanswered.as4MessageId(), "No response to the DATA_REQUEST with request ID req-2 within 60.0 s."),
                        tuple("T-ALL-03", unacknowledged.as4MessageId(), "The FOLLOW_UP with request ID fu-1 was not acknowledged within 60.0 s."));
        assertThat(ConformanceChecks.checkOverdue(history, T0.plusSeconds(60), CONTEXT)).isEmpty();
    }

    @Test
    void checkOverdueScalesLinearly() {
        int requests = 10_000;
        IntStream.range(0, requests).forEach(i -> {
            MessageRecord request = toPlatform(dataRequest("req-" + i, "S1"), T0);
            history.append(request);
            if (i % 2 == 0) {
                history.append(answer(request, errorResponse(DATA_ERROR, "req-" + i), T0));
            }
        });

        long start = System.nanoTime();
        List<Finding> overdue = ConformanceChecks.checkOverdue(history, T0.plusSeconds(61), CONTEXT);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        assertThat(overdue).hasSize(requests / 2);
    }

    @Test
    void pruneRemovesOldMessagesButKeepsTheIdentifierBaseline() {
        upload(DATASET, true);
        MessageRecord oldRequest = send(dataRequest("req-old", "S1 S2"), T0);
        exchange(answer(oldRequest, errorResponse(DATA_ERROR, "req-old"), T0.plusSeconds(1)));
        MessageRecord pending = send(dataRequest("req-pending", "S1 S2"), T0.plusSeconds(2));
        MessageRecord recent = send(dataRequest("req-1", "S1 S2"), T0.plusSeconds(200));

        history.prune(T0.plusSeconds(100));

        assertThat(history.messages()).extracting(ParsedMessage::kind)
                .containsExactly(MessageKind.IDENTIFIER_UPLOAD, MessageKind.DATA_REQUEST);
        assertThat(history.allResponsesTo(FORMAT.parse(oldRequest))).isEmpty();
        assertThat(details(ConformanceChecks.checkOverdue(history, T0.plusSeconds(1000), CONTEXT)))
                .containsExactly("No response to the DATA_REQUEST with request ID req-1 within 60.0 s.");
        assertThat(ids(exchange(answer(recent, dataResponse("req-1", FULL_RESPONSE.replace("AB123", "AB321")), T0.plusSeconds(201)))))
                .containsExactly("T-DAT-05");
        assertThat(details(exchange(answer(pending, errorResponse(DATA_ERROR, "req-pending"), T0.plusSeconds(201)))))
                .singleElement().asString().startsWith("The AS4 RefToMessageId " + pending.as4MessageId() + " refers to no");
    }

    @Test
    @SuppressWarnings("deprecation")
    void historyCorrelatesResponsesWithRequests() {
        MessageRecord requestRecord = toPlatform(dataRequest("req-1", "S1"), T0);
        ParsedMessage request = history.append(requestRecord);
        ParsedMessage response = history.append(answer(requestRecord, errorResponse(DATA_ERROR, "req-1"), T0));
        ParsedMessage duplicate = history.append(answer(requestRecord, errorResponse(DATA_ERROR, "req-1"), T0));

        assertThat(history.requestAnsweredBy(response)).containsSame(request);
        assertThat(history.requestAnsweredBy(duplicate)).isEmpty();
        assertThat(history.requestsAnswerableBy(duplicate)).containsExactly(request);
        assertThat(history.requestsAnswerableBy(history.append(fromPlatform(errorResponse(DATA_ERROR, "req-unknown"), T0))))
                .isEmpty();
        assertThat(history.responseTo(request)).containsSame(response);
        assertThat(history.allResponsesTo(request)).containsExactly(response, duplicate);
        assertThat(history.responseTo(response)).isEmpty();
        assertThat(history.allResponsesTo(response)).isEmpty();
        assertThat(history.records()).hasSize(4);
    }

    @Test
    void roundTripOfTheExpectedContentPassesWhateverItsIndentation() {
        MessageRecord request = toPlatform(dataRequest("req-1", "S1 S2"), T0);
        history.append(request);
        MessageRecord response = answer(request, dataResponse("req-1", FULL_RESPONSE.replaceAll(">\\s+<", "><")), T0);
        history.append(response);

        assertThat(ConformanceChecks.checkRoundTrip(response, history, DATASET).findings()).isEmpty();
    }

    @Test
    void roundTripReportsChangedAddedAndMissingValues() {
        String dataset = FULL_RESPONSE
                .replace("AB123", "AB321")
                .replace("<city>Berlin</city>", "")
                .replace("<name>Sender Ltd</name>", "")
                .replace("<hazard>none</hazard>", "")
                .replace("<weight unit=\"KGM\">12500</weight>", "<weight unit=\"TNE\">12.5</weight>");
        MessageRecord request = toPlatform(dataRequest("req-1", "S1 S2"), T0);
        history.append(request);

        assertThat(ConformanceChecks.checkRoundTrip(answer(request, dataResponse("req-1", dataset), T0), history, DATASET).findings())
                .extracting(Finding::testCaseId, Finding::detail).containsExactly(
                        tuple("T-DAT-06", "hazard: expected [none] but found []"),
                        tuple("T-DAT-06", "receiver/address/city: expected [Berlin] but found []"),
                        tuple("T-DAT-06", "sender/name: expected [Sender Ltd] but found []"),
                        tuple("T-DAT-06", "vehicle/plate: expected [AB123] but found [AB321]"),
                        tuple("T-DAT-06", "weight: expected [12500] but found [12.5]"),
                        tuple("T-DAT-06", "weight/@unit: expected [KGM] but found [TNE]"));
    }

    @Test
    void roundTripRevealsPaddedFreeText() {
        assertThat(roundTripDifferences(FULL_RESPONSE.replace("<name>Sender Ltd</name>", "<name> Sender Ltd  </name>")))
                .containsExactly("sender/name: expected [Sender Ltd] but found [ Sender Ltd  ]");
    }

    @Test
    void roundTripIgnoresWhitespaceThatTheTypeOfAValueCollapses() {
        assertThat(roundTripDifferences(FULL_RESPONSE.replace("<plate>AB123</plate>", "<plate> AB123  </plate>")
                .replace("<hazard>none</hazard>", "<hazard>\n    none\n  </hazard>"))).isEmpty();
    }

    @Test
    void roundTripTreatsCanonicallyEquivalentCharactersAsEqual() {
        MessageRecord request = toPlatform(dataRequest("req-1", "S1 S2"), T0);
        history.append(request);
        // o with diaeresis as one character in the expected data set and as o plus combining diaeresis in the response
        String decomposed = FULL_RESPONSE.replace("Sender Ltd", "So\u0308hne Ltd");

        assertThat(ConformanceChecks.checkRoundTrip(answer(request, dataResponse("req-1", decomposed), T0), history,
                DATASET.replace("Sender Ltd", "S\u00F6hne Ltd")).findings()).isEmpty();
    }

    @Test
    void roundTripListsAddedAttributesButReportsChangedAndMissingOnes() {
        MessageRecord request = toPlatform(dataRequest("req-1", "S1 S2"), T0);
        history.append(request);
        String withLanguage = FULL_RESPONSE.replace("<name>Sender Ltd</name>", "<name lang=\"en\">Sender Ltd</name>");

        ConformanceChecks.RoundTripResult added = ConformanceChecks.checkRoundTrip(
                answer(request, dataResponse("req-1", withLanguage), T0), history, DATASET);
        assertThat(added.findings()).isEmpty();
        assertThat(added.addedAttributes()).containsExactly("sender/name/@lang");
        // the language is part of the expected data set here, so that changing or dropping it changes the data
        assertThat(ConformanceChecks.checkRoundTrip(answer(request, dataResponse("req-1", withLanguage.replace("\"en\"", "\"de\"")), T0),
                history, withLanguage).findings())
                .extracting(Finding::detail).containsExactly("sender/name/@lang: expected [en] but found [de]");
        assertThat(ConformanceChecks.checkRoundTrip(answer(request, dataResponse("req-1", FULL_RESPONSE), T0), history, withLanguage).findings())
                .extracting(Finding::detail).containsExactly("sender/name/@lang: expected [en] but found []");
    }

    @Test
    void roundTripToleratesOmittedOptionalElementsAndLifecycleFields() {
        String dataset = FULL_RESPONSE
                .replace("<department>Shipping</department>", "")
                .replace("<street>Main Street 1</street>", "")
                .replace("<kind>PX</kind>", "<kind>PY</kind>")
                .replace("202609150800", "202609151012")
                .replace("<name>Receiver GmbH</name>", "<name>Receiver GmbH</name><signature>signed</signature>")
                .replace("</hazard>", "</hazard><delivery><at>202609161435</at></delivery>");
        MessageRecord request = toPlatform(dataRequest("req-1", "S1 S2"), T0);
        history.append(request);
        MessageRecord onlyS2 = toPlatform(dataRequest("req-2", "S2"), T0);
        history.append(onlyS2);
        // the sender's name is mandatory in S1 but optional in S2
        String withoutSenderName = FORMAT.filterBySubsets(DATASET, List.of("S2")).replace("<name>Sender Ltd</name>", "");

        ConformanceChecks.RoundTripResult full = ConformanceChecks.checkRoundTrip(
                answer(request, dataResponse("req-1", dataset), T0), history, DATASET);
        assertThat(full.findings()).extracting(Finding::detail).containsExactly("item/kind: expected [PX] but found [PY]");
        // the lifecycle fields are not compared and thus not listed either
        assertThat(full.omittedOptional()).containsExactly("sender/address/street", "sender/department");
        ConformanceChecks.RoundTripResult s2 = ConformanceChecks.checkRoundTrip(
                answer(onlyS2, dataResponse("req-2", withoutSenderName), T0), history, DATASET);
        assertThat(s2.findings()).isEmpty();
        assertThat(s2.omittedOptional()).containsExactly("sender/name");
    }

    @Test
    void roundTripIgnoresTheOrderOfRepeatedElements() {
        String twoItems = DATASET.replace("</item>", "</item><item><count>7</count></item>");
        String reordered = DATASET.replace("<item>", "<item><count>7</count></item><item>");
        MessageRecord request = toPlatform(dataRequest("req-1", "S2"), T0);
        history.append(request);
        MessageRecord response = answer(request, dataResponse("req-1", FORMAT.filterBySubsets(reordered, List.of("S2"))), T0);

        assertThat(ConformanceChecks.checkRoundTrip(response, history, twoItems).findings()).isEmpty();
    }

    @Test
    void roundTripNeedsACorrelatedDataResponse() {
        MessageRecord uncorrelated = fromPlatform(dataResponse("req-x", FULL_RESPONSE), T0);
        MessageRecord error = fromPlatform(errorResponse(DATA_ERROR, "req-x"), T0);

        assertThat(ConformanceChecks.checkRoundTrip(uncorrelated, history, DATASET).findings()).singleElement()
                .satisfies(finding -> assertThat(finding.detail()).contains("cannot be correlated"));
        assertThat(ConformanceChecks.checkRoundTrip(error, history, DATASET).findings()).isEmpty();
    }

    @Test
    void roundTripWithInvalidExpectedDataYieldsAFinding() {
        MessageRecord request = toPlatform(dataRequest("req-1", "S1"), T0);
        history.append(request);

        assertThat(ConformanceChecks.checkRoundTrip(answer(request, dataResponse("req-1", FULL_RESPONSE), T0), history, "<shipment").findings())
                .singleElement().satisfies(finding -> assertThat(finding.detail()).startsWith("The check cannot be evaluated"));
    }
}
