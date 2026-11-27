package eu.efti.tst.platform;

import eu.efti.tst.core.Format;
import eu.efti.tst.core.IdentifierType;
import eu.efti.tst.core.MessageKind;
import eu.efti.tst.core.MessageRecord;
import eu.efti.tst.core.ParsedMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformBehaviourTest {

    private static final Path FORMAT_DIR = Path.of("..", "formats", "ri-v0.5");
    private static final Format FORMAT = Format.load(FORMAT_DIR);
    private static final PlatformBehaviour BEHAVIOUR = PlatformBehaviour.load(FORMAT_DIR);
    private static final String DS1 = read(FORMAT_DIR.resolve("testdata/ds1-road.xml"));
    private static final String GATE = "tstgate";
    private static final String PLATFORM = "pseudoplatform";
    private static final String DATASET_ID = "0b6f3f3e-3c1a-4f6e-9d4b-2a7c5e8f1a90";
    private static final OffsetDateTime SIGNATURE_TIME = OffsetDateTime.parse("2026-09-16T14:35:20+02:00");
    private static final String LODGED_REQUEST_ID = "3f1c9a52-6d0e-4b7a-8c21-9e4d7f6a1b03";

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Map<String, DataSet> dataSets(DataSet.Status status) {
        return Map.of(DATASET_ID, new DataSet(DATASET_ID, DS1, status));
    }

    private static ParsedMessage received(String template, String datasetId, String subsetIds) {
        return received(template, UUID.randomUUID().toString(), LODGED_REQUEST_ID, datasetId, subsetIds);
    }

    private static ParsedMessage received(String template, String requestId, String referencedRequestId, String datasetId,
                                          String subsetIds) {
        Map<String, String> values = new HashMap<>(Map.of("requestId", requestId,
                "referencedRequestId", referencedRequestId, "gateId", GATE, "platformId", PLATFORM,
                "datasetId", datasetId, "subsetIds", subsetIds, "message", "Please check the plate"));
        return parse(FORMAT.render(template, values), MessageRecord.Direction.TO_PLATFORM);
    }

    private static ParsedMessage parse(String payload, MessageRecord.Direction direction) {
        return FORMAT.parse(new MessageRecord("as4-" + UUID.randomUUID(), Instant.now(), direction, GATE, PLATFORM,
                UUID.randomUUID().toString(), null, payload));
    }

    /** The data request the follow-ups refer to, for the data set they refer to unless a test says otherwise. */
    private static List<ParsedMessage> lodgedFor(String datasetId) {
        return List.of(received("data-request", LODGED_REQUEST_ID, LODGED_REQUEST_ID, datasetId, "EU01"));
    }

    /** Parses the answer and asserts that it is a valid message of the given kind echoing the request ID. */
    private static ParsedMessage answerOf(ParsedMessage request, Map<String, DataSet> dataSets, FaultToggles toggles,
                                          MessageKind expectedKind) {
        Optional<String> answer = BEHAVIOUR.answer(request, dataSets, lodgedFor(DATASET_ID), toggles);
        assertThat(answer).isPresent();
        assertThat(FORMAT.validateSchema(answer.get())).isEmpty();
        assertThat(FORMAT.validateRules(answer.get())).isEmpty();
        ParsedMessage parsed = parse(answer.get(), MessageRecord.Direction.FROM_PLATFORM);
        assertThat(parsed.kind()).isEqualTo(expectedKind);
        assertThat(parsed.requestId()).isEqualTo(FORMAT.correlationKey(request));
        return parsed;
    }

    private static ParsedMessage dataResponse(ParsedMessage request, Map<String, DataSet> dataSets, FaultToggles toggles) {
        return answerOf(request, dataSets, toggles, MessageKind.DATA_RESPONSE);
    }

    private static ParsedMessage followUpAck(ParsedMessage request, Map<String, DataSet> dataSets) {
        return answerOf(request, dataSets, FaultToggles.NONE, MessageKind.FOLLOW_UP_ACK);
    }

    @Test
    void acceptsTheTestDataSet() {
        assertThat(BEHAVIOUR.validateDataset(DS1)).isEmpty();
    }

    @Test
    void reportsSchemaErrorsWithLinesOfTheDataSet() {
        String withUnknownElement = DS1.replace("<grossWeight", "<unknownElement/><grossWeight");
        int expectedLine = (int) DS1.substring(0, DS1.indexOf("<grossWeight")).lines().count();

        List<String> errors = BEHAVIOUR.validateDataset(withUnknownElement);

        assertThat(errors).isNotEmpty();
        assertThat(errors.getFirst()).startsWith(expectedLine + ":").contains("unknownElement");
    }

    @Test
    void reportsSchemaErrorsWithLinesOfTheDataSetDespiteWhitespaceBeforeTheXmlDeclaration() {
        String withUnknownElement = "\n\n  " + DS1.replace("<grossWeight", "<unknownElement/><grossWeight");
        int expectedLine = (int) DS1.substring(0, DS1.indexOf("<grossWeight")).lines().count() + 2;

        List<String> errors = BEHAVIOUR.validateDataset(withUnknownElement);

        assertThat(errors).isNotEmpty();
        assertThat(errors.getFirst()).startsWith(expectedLine + ":").contains("unknownElement");
    }

    @Test
    void rejectsInputThatIsNoDataSet() {
        assertThat(BEHAVIOUR.validateDataset("")).isNotEmpty();
        assertThat(BEHAVIOUR.validateDataset("<consignment")).isNotEmpty();
        assertThat(BEHAVIOUR.validateDataset("<other xmlns=\"urn:x\"/>")).isNotEmpty();
    }

    @Test
    void identifierUploadCarriesTheIdentifiersOfTheDataSet() {
        String requestId = UUID.randomUUID().toString();

        String upload = BEHAVIOUR.identifierUpload(requestId, new DataSet(DATASET_ID, DS1, DataSet.Status.ACTIVE));

        assertThat(FORMAT.validateSchema(upload)).isEmpty();
        assertThat(FORMAT.validateRules(upload)).isEmpty();
        ParsedMessage parsed = parse(upload, MessageRecord.Direction.FROM_PLATFORM);
        assertThat(parsed.kind()).isEqualTo(MessageKind.IDENTIFIER_UPLOAD);
        assertThat(parsed.requestId()).contains(requestId);
        assertThat(parsed.datasetId()).contains(DATASET_ID);
        assertThat(FORMAT.identifiers(parsed.identifiers().orElseThrow())).isEqualTo(FORMAT.identifiers(DS1));
    }

    @Test
    void consigneeSignatureSetsTheActualDeliveryDate() {
        String signed = BEHAVIOUR.signAsConsignee(DS1, SIGNATURE_TIME);

        assertThat(BEHAVIOUR.validateDataset(signed)).isEmpty();
        assertThat(FORMAT.identifiers(signed).get(IdentifierType.ACTUAL_DELIVERY_DATE))
                .containsExactly("2026-09-16T12:35:00Z|205");
        String upload = BEHAVIOUR.identifierUpload("r1", new DataSet(DATASET_ID, signed, DataSet.Status.INACTIVE));
        assertThat(FORMAT.identifiers(parse(upload, MessageRecord.Direction.FROM_PLATFORM).identifiers().orElseThrow())
                .get(IdentifierType.ACTUAL_DELIVERY_DATE)).containsExactly("2026-09-16T12:35:00Z|205");
    }

    @Test
    void consigneeSignatureReplacesAnEarlierDeliveryDateAndKeepsAllElse() {
        String signedTwice = BEHAVIOUR.signAsConsignee(BEHAVIOUR.signAsConsignee(DS1, SIGNATURE_TIME),
                OffsetDateTime.parse("2026-09-17T08:05:00Z"));

        assertThat(BEHAVIOUR.validateDataset(signedTwice)).isEmpty();
        assertThat(FORMAT.identifiers(signedTwice).get(IdentifierType.ACTUAL_DELIVERY_DATE))
                .containsExactly("2026-09-17T08:05:00Z|205");
        assertThat(FORMAT.identifiers(signedTwice).get(IdentifierType.TRANSPORT_MEANS))
                .isEqualTo(FORMAT.identifiers(DS1).get(IdentifierType.TRANSPORT_MEANS));
    }

    @Test
    void answersDataRequestWithTheDataSetFilteredBySubsets() {
        ParsedMessage request = received("data-request", DATASET_ID, "EU01");

        ParsedMessage response = dataResponse(request, dataSets(DataSet.Status.ACTIVE), FaultToggles.NONE);

        assertThat(response.success()).isTrue();
        String dataset = response.dataset().orElseThrow();
        assertThat(dataset).isEqualTo(FORMAT.filterBySubsets(DS1, List.of("EU01")));
        // DS1 holds the mode of transport outside EU01, so an unfiltered data set would carry it
        assertThat(FORMAT.identifiers(dataset).get(IdentifierType.MODE_OF_TRANSPORT)).isEmpty();
    }

    @Test
    void answersDataRequestWithTheFullDataSetWhenTheSubsetFilterIsIgnored() {
        ParsedMessage request = received("data-request", DATASET_ID, "EU01");

        ParsedMessage response = dataResponse(request, dataSets(DataSet.Status.ACTIVE),
                new FaultToggles(false, true, false));

        assertThat(response.success()).isTrue();
        assertThat(FORMAT.identifiers(response.dataset().orElseThrow())).isEqualTo(FORMAT.identifiers(DS1));
    }

    @Test
    void recognisesRoadTransportByTheModeOfTransport() {
        assertThat(BEHAVIOUR.isRoadTransport(DS1)).isTrue();
        assertThat(BEHAVIOUR.isRoadTransport(read(FORMAT_DIR.resolve("testdata/ds2-rail.xml")))).isFalse();
    }

    @Test
    void answersDataRequestForAnInactiveDataSet() {
        ParsedMessage request = received("data-request", DATASET_ID, "EU01");

        assertThat(dataResponse(request, dataSets(DataSet.Status.INACTIVE), FaultToggles.NONE).success()).isTrue();
    }

    @Test
    void answersDataRequestForADraftOrArchivableOrUnknownDataSetWithNotFound() {
        ParsedMessage forDraft = received("data-request", DATASET_ID, "EU01");
        ParsedMessage forReadyToArchive = received("data-request", DATASET_ID, "EU01");
        ParsedMessage forUnknown = received("data-request", UUID.randomUUID().toString(), "EU01");

        for (ParsedMessage response : List.of(
                dataResponse(forDraft, dataSets(DataSet.Status.DRAFT), FaultToggles.NONE),
                dataResponse(forReadyToArchive, dataSets(DataSet.Status.READY_TO_ARCHIVE), FaultToggles.NONE),
                dataResponse(forUnknown, dataSets(DataSet.Status.ACTIVE), FaultToggles.NONE))) {
            assertThat(response.success()).isFalse();
            assertThat(response.status()).contains(FORMAT.notFoundStatus());
            assertThat(response.description()).hasValueSatisfying(text -> assertThat(text).contains("Unknown data set"));
            assertThat(response.dataset()).isEmpty();
        }
    }

    @Test
    void answersSchemaInvalidDataRequestWithBadRequest() {
        ParsedMessage request = received("data-request-invalid", DATASET_ID, "EU01");

        ParsedMessage response = dataResponse(request, dataSets(DataSet.Status.ACTIVE), FaultToggles.NONE);

        assertThat(response.status()).contains(FORMAT.invalidRequestStatus());
        assertThat(response.dataset()).isEmpty();
    }

    @Test
    void answersDataRequestWithoutRequestIdWithBadRequestWithoutRequestId() {
        ParsedMessage request = received("data-request-missing-request-id", DATASET_ID, "EU01");

        ParsedMessage response = dataResponse(request, dataSets(DataSet.Status.ACTIVE), FaultToggles.NONE);

        assertThat(response.requestId()).isEmpty();
        assertThat(response.status()).contains(FORMAT.invalidRequestStatus());
        assertThat(response.dataset()).isEmpty();
    }

    @Test
    void answersDataRequestWithAnUnknownSubsetWithBadRequestAndNoData() {
        ParsedMessage request = received("data-request-unknown-subset", DATASET_ID, "EU01");

        ParsedMessage response = dataResponse(request, dataSets(DataSet.Status.ACTIVE), FaultToggles.NONE);

        assertThat(response.status()).contains(FORMAT.invalidRequestStatus());
        assertThat(response.dataset()).isEmpty();
    }

    @Test
    void doesNotAnswerDataRequestsWhenToggled() {
        ParsedMessage request = received("data-request", DATASET_ID, "EU01");

        assertThat(BEHAVIOUR.answer(request, dataSets(DataSet.Status.ACTIVE), List.of(), new FaultToggles(true, false, false)))
                .isEmpty();
    }

    @Test
    void acknowledgesFollowUpForAKnownDataSet() {
        ParsedMessage followUp = received("follow-up", DATASET_ID, "");

        assertThat(followUpAck(followUp, dataSets(DataSet.Status.ACTIVE)).success()).isTrue();
    }

    @Test
    void acknowledgesFollowUpForAnUnknownDataSetOrADraftWithNotFound() {
        ParsedMessage forUnknown = received("follow-up", UUID.randomUUID().toString(), "");
        ParsedMessage forDraft = received("follow-up", DATASET_ID, "");

        assertThat(followUpAck(forUnknown, dataSets(DataSet.Status.ACTIVE)).status()).contains(FORMAT.notFoundStatus());
        assertThat(followUpAck(forDraft, dataSets(DataSet.Status.DRAFT)).status()).contains(FORMAT.notFoundStatus());
    }

    @Test
    void acknowledgesFollowUpWithAnUnknownRequestIdWithNotFound() {
        ParsedMessage followUp = received("follow-up", UUID.randomUUID().toString(), UUID.randomUUID().toString(), DATASET_ID, "");

        assertThat(followUpAck(followUp, dataSets(DataSet.Status.ACTIVE)).status()).contains(FORMAT.notFoundStatus());
    }

    @Test
    void acknowledgesFollowUpReferringToADataRequestForAnotherDataSetWithNotFound() {
        String otherDatasetId = UUID.randomUUID().toString();
        Map<String, DataSet> dataSets = new HashMap<>(dataSets(DataSet.Status.ACTIVE));
        dataSets.put(otherDatasetId, new DataSet(otherDatasetId, DS1, DataSet.Status.ACTIVE));
        ParsedMessage followUp = received("follow-up", otherDatasetId, "");

        assertThat(followUpAck(followUp, dataSets).status()).contains(FORMAT.notFoundStatus());
    }

    @Test
    void acknowledgesFollowUpsWhenDataRequestsAreIgnored() {
        ParsedMessage followUp = received("follow-up", DATASET_ID, "");

        assertThat(answerOf(followUp, dataSets(DataSet.Status.ACTIVE), new FaultToggles(true, false, false),
                MessageKind.FOLLOW_UP_ACK).success()).isTrue();
    }

    @Test
    void acknowledgesSchemaInvalidFollowUpWithBadRequest() {
        ParsedMessage followUp = received("follow-up-invalid", DATASET_ID, "");

        assertThat(followUpAck(followUp, dataSets(DataSet.Status.ACTIVE)).status()).contains(FORMAT.invalidRequestStatus());
    }

    @Test
    void acknowledgesFollowUpWithoutRequestIdWithBadRequestWithoutRequestId() {
        ParsedMessage followUp = received("follow-up-missing-request-id", DATASET_ID, "");

        ParsedMessage ack = followUpAck(followUp, dataSets(DataSet.Status.ACTIVE));

        assertThat(ack.requestId()).isEmpty();
        assertThat(ack.status()).contains(FORMAT.invalidRequestStatus());
    }

    @Test
    void doesNotAnswerUploadStatusesOrMalformedMessages() {
        ParsedMessage uploadStatus = parse(FORMAT.render("upload-status-success", Map.of("requestId", "r1")),
                MessageRecord.Direction.TO_PLATFORM);
        ParsedMessage malformed = parse("<uilQuery", MessageRecord.Direction.TO_PLATFORM);

        assertThat(BEHAVIOUR.answer(uploadStatus, dataSets(DataSet.Status.ACTIVE), List.of(), FaultToggles.NONE)).isEmpty();
        assertThat(BEHAVIOUR.answer(malformed, dataSets(DataSet.Status.ACTIVE), List.of(), FaultToggles.NONE)).isEmpty();
    }

    @Test
    void failsToLoadAMissingFormat() {
        assertThatThrownBy(() -> PlatformBehaviour.load(Path.of("does-not-exist")))
                .isInstanceOf(RuntimeException.class);
    }
}
