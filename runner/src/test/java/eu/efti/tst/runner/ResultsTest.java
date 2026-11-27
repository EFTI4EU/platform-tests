package eu.efti.tst.runner;

import eu.efti.tst.core.Finding;
import eu.efti.tst.core.MessageHistory;
import eu.efti.tst.core.MessageRecord;
import eu.efti.tst.core.ParsedMessage;
import eu.efti.tst.core.TestCaseResult;
import eu.efti.tst.core.TestCaseResult.Outcome;
import eu.efti.tst.core.TestCatalogue;
import eu.efti.tst.runner.RunEvidence.DatasetRun;
import eu.efti.tst.runner.RunEvidence.ReceivedMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static eu.efti.tst.runner.Fixtures.DATASET_ID;
import static eu.efti.tst.runner.Fixtures.DS1_CORRECTED;
import static eu.efti.tst.runner.Fixtures.FORMAT;
import static eu.efti.tst.runner.Fixtures.T0;
import static eu.efti.tst.runner.Fixtures.fromPlatform;
import static eu.efti.tst.runner.Fixtures.toPlatform;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class ResultsTest {

    private static final TestCatalogue CATALOGUE = TestCatalogue.load();

    private final MessageHistory history = new MessageHistory(FORMAT);

    private static TestCaseResult passed(String testCaseId) {
        return new TestCaseResult(testCaseId, "DS1", Outcome.PASSED, null, List.of());
    }

    private static TestCaseResult failed(String testCaseId) {
        return new TestCaseResult(testCaseId, "DS1", Outcome.FAILED, null, List.of(new Finding(testCaseId, null, "failed")));
    }

    private static DatasetRun run(List<TestCaseResult> steps, List<MessageRecord> dataResponses) {
        return new DatasetRun("DS1", steps, DS1_CORRECTED, dataResponses, Optional.of(DATASET_ID), Optional.of("req-1"), Optional.of("req-1"));
    }

    private ParsedMessage exchange(MessageRecord record) {
        return Fixtures.exchange(history, record).message();
    }

    /** Receives a message for DS1 with its findings, as the engine does. */
    private ReceivedMessage receive(MessageRecord record) {
        var checked = Fixtures.exchange(history, record);
        return new ReceivedMessage("DS1", checked.message(), checked.findings());
    }

    private List<TestCaseResult> aggregate(DatasetRun run, List<ReceivedMessage> received, List<Finding> overdue,
                                           Map<String, String> receiptStatuses) {
        return Results.aggregate(CATALOGUE, new RunEvidence(List.of(run), List.of(), List.of(), received, overdue,
                receiptStatuses, history, 0));
    }

    private List<TestCaseResult> aggregate(DatasetRun run, List<ReceivedMessage> received) {
        return aggregate(run, received, List.of(), Map.of());
    }

    private static TestCaseResult result(List<TestCaseResult> results, String testCaseId) {
        return results.stream().filter(result -> result.testCaseId().equals(testCaseId)).findFirst().orElseThrow();
    }

    /** An identifier upload for DS1 accepted by the gate. */
    private ReceivedMessage acceptedUpload() {
        ReceivedMessage upload = receive(fromPlatform(Fixtures.identifierUpload(DATASET_ID, DS1_CORRECTED), T0));
        MessageRecord record = upload.message().record();
        exchange(new MessageRecord(UUID.randomUUID() + "@gate", T0, MessageRecord.Direction.TO_PLATFORM, Fixtures.GATE_PARTY,
                Fixtures.PLATFORM_PARTY, record.conversationId(), record.as4MessageId(),
                FORMAT.render("upload-status-success", Map.of("requestId", upload.message().requestId().orElseThrow()))));
        return upload;
    }

    @Test
    void everyCatalogueTestCaseHasOneResultPerConfiguredDataSetOrRun() {
        List<TestCaseResult> results = aggregate(run(List.of(), List.of()), List.of());

        assertThat(results).extracting(TestCaseResult::testCaseId).doesNotHaveDuplicates()
                .containsExactlyElementsOf(CATALOGUE.sections().stream()
                        .flatMap(section -> section.testCases().stream()).map(TestCatalogue.TestCase::id).toList());
        assertThat(results).filteredOn(result -> result.testCaseId().startsWith("T-RUN") || result.testCaseId().startsWith("T-DAT"))
                .extracting(TestCaseResult::datasetKey).containsOnly("DS1");
        assertThat(results).filteredOn(result -> result.testCaseId().startsWith("T-ALL") || result.testCaseId().startsWith("T-NEG"))
                .extracting(TestCaseResult::datasetKey).containsOnlyNulls();
        // without results from the negative scenario they are not applicable rather than passed
        assertThat(result(results, "T-NEG-03").outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
    }

    @Test
    void stepsAfterAFailedStepAreNotExecuted() {
        List<TestCaseResult> results = aggregate(run(List.of(passed("T-RUN-01"), passed("T-RUN-02"), failed("T-RUN-03")), List.of()),
                List.of());

        assertThat(results).filteredOn(result -> result.testCaseId().startsWith("T-RUN"))
                .extracting(TestCaseResult::outcome, TestCaseResult::note)
                .containsExactly(
                        tuple(Outcome.PASSED, null),
                        tuple(Outcome.PASSED, null),
                        tuple(Outcome.FAILED, null),
                        tuple(Outcome.FAILED, "not executed: T-RUN-03 failed"),
                        tuple(Outcome.FAILED, "not executed: T-RUN-03 failed"),
                        tuple(Outcome.FAILED, "not executed: T-RUN-03 failed"));
        assertThat(result(results, "T-DAT-06")).extracting(TestCaseResult::outcome, TestCaseResult::note)
                .containsExactly(Outcome.NOT_APPLICABLE, "not evaluated: no data request of T-RUN-04 was answered with data");
    }

    @Test
    void findingsFailTheirTestCaseForTheDataSetAndRunWide() {
        ReceivedMessage message = receive(fromPlatform("<notAMessage/>", T0));
        Finding syntax = new Finding("T-DAT-01", "m1", "Schema violation");
        Finding correlation = new Finding("T-ALL-03", "m2", "unsolicited");
        Finding otherDataset = new Finding("T-DAT-02", "m3", "wrong ID");

        List<TestCaseResult> results = aggregate(run(List.of(), List.of()), List.of(
                new ReceivedMessage("DS1", message.message(), List.of(syntax, correlation)),
                new ReceivedMessage("DS2", message.message(), List.of(otherDataset))));

        assertThat(result(results, "T-DAT-01").findings()).containsExactly(syntax);
        assertThat(result(results, "T-DAT-02").outcome()).isEqualTo(Outcome.PASSED);
        assertThat(result(results, "T-ALL-03").findings()).containsExactly(correlation);
    }

    @Test
    void dataChecksWithoutMessagesOfTheirKindAreNotApplicable() {
        assertThat(aggregate(run(List.of(), List.of()), List.of()))
                .filteredOn(result -> List.of("T-DAT-01", "T-DAT-02", "T-DAT-03", "T-DAT-04", "T-DAT-05").contains(result.testCaseId()))
                .extracting(TestCaseResult::outcome, TestCaseResult::note)
                .containsExactly(
                        tuple(Outcome.NOT_APPLICABLE, "no message received in this run"),
                        tuple(Outcome.NOT_APPLICABLE, "no message received in this run"),
                        tuple(Outcome.NOT_APPLICABLE, "no identifier upload received in this run"),
                        tuple(Outcome.NOT_APPLICABLE, "no data set received in this run"),
                        tuple(Outcome.NOT_APPLICABLE, "no data set after an accepted identifier upload received in this run"));

        ReceivedMessage upload = acceptedUpload();
        List<TestCaseResult> withUpload = aggregate(run(List.of(), List.of()), List.of(upload));
        assertThat(result(withUpload, "T-DAT-03").outcome()).isEqualTo(Outcome.PASSED);
        assertThat(result(withUpload, "T-DAT-04").outcome()).isEqualTo(Outcome.NOT_APPLICABLE);

        ParsedMessage request = exchange(toPlatform(Fixtures.dataRequest("data-request", "req-1", DATASET_ID, "EU01 EU02"), T0));
        ReceivedMessage response = receive(Fixtures.answer(request,
                Fixtures.dataResponse("req-1", DS1_CORRECTED, List.of("EU01", "EU02")), T0));
        assertThat(aggregate(run(List.of(), List.of()), List.of(upload, response)))
                .filteredOn(result -> result.testCaseId().startsWith("T-DAT-0") && !result.testCaseId().equals("T-DAT-06"))
                .extracting(TestCaseResult::outcome).containsOnly(Outcome.PASSED);
    }

    @Test
    void roundTripComparesEveryReturnedDataSet() {
        ParsedMessage request = exchange(toPlatform(Fixtures.dataRequest("data-request", "req-1", DATASET_ID, "EU01 EU03"), T0));
        MessageRecord response = Fixtures.answer(request, Fixtures.dataResponse("req-1", DS1_CORRECTED, List.of("EU01", "EU03")), T0);
        MessageRecord wrong = Fixtures.answer(request, Fixtures.dataResponse("req-1", Fixtures.DS1, List.of("EU01", "EU03")), T0);

        assertThat(result(aggregate(run(List.of(), List.of(response)), List.of()), "T-DAT-06").outcome())
                .isEqualTo(Outcome.PASSED);
        assertThat(result(aggregate(run(List.of(), List.of(response, wrong)), List.of()), "T-DAT-06").findings())
                .singleElement().satisfies(finding -> {
                    assertThat(finding.testCaseId()).isEqualTo("T-DAT-06");
                    assertThat(finding.as4MessageId()).isEqualTo(wrong.as4MessageId());
                    assertThat(finding.detail()).contains("BAB123", "BAB132");
                });
    }

    @Test
    void roundTripListsOmittedOptionalElementsWithoutFailing() {
        // The expected data set gets an optional element that the responses do not carry.
        String omitted = "consignor/definedContactDetails/telephone/completeNumber";
        String expected = DS1_CORRECTED.replaceFirst("<consignor>",
                "<consignor><definedContactDetails><telephone><completeNumber>+49 341 0000000</completeNumber></telephone></definedContactDetails>");
        ParsedMessage request = exchange(toPlatform(Fixtures.dataRequest("data-request", "req-1", DATASET_ID, "EU01 EU02"), T0));
        MessageRecord first = Fixtures.answer(request, Fixtures.dataResponse("req-1", DS1_CORRECTED, List.of("EU01", "EU02")), T0);
        MessageRecord second = Fixtures.answer(request, Fixtures.dataResponse("req-1", DS1_CORRECTED, List.of("EU01", "EU02")), T0);
        DatasetRun run = new DatasetRun("DS1", List.of(), expected, List.of(first, second), Optional.of(DATASET_ID),
                Optional.of("req-1"), Optional.of("req-1"));

        TestCaseResult result = result(aggregate(run, List.of()), "T-DAT-06");

        assertThat(result.outcome()).isEqualTo(Outcome.PASSED);
        assertThat(result.note()).isEqualTo("optional elements of the test data set not returned: " + omitted);
    }

    @Test
    void exchangeRuleTestsUseTheHistoryAndTheReceipts() {
        ParsedMessage first = exchange(toPlatform(Fixtures.dataRequest("data-request", "req-1", DATASET_ID, "EU01"), T0));
        ParsedMessage second = exchange(toPlatform(Fixtures.dataRequest("data-request", "req-2", DATASET_ID, "EU01"), T0));
        exchange(Fixtures.answer(first, Fixtures.errorResponse("data-response-error", "req-1", "x"), T0.plusMillis(2400)));
        Finding overdue = new Finding("T-ALL-04", second.record().as4MessageId(), "No response");
        Map<String, String> receipts = Map.of(first.record().as4MessageId(), Results.ACKNOWLEDGED,
                second.record().as4MessageId(), "SEND_FAILURE");

        List<TestCaseResult> results = aggregate(run(List.of(), List.of()), List.of(), List.of(overdue), receipts);

        assertThat(result(results, "T-ALL-01").outcome()).isEqualTo(Outcome.PASSED);
        assertThat(result(results, "T-ALL-02").findings()).extracting(Finding::as4MessageId, Finding::detail)
                .containsExactly(tuple(second.record().as4MessageId(), "The DATA_REQUEST with request ID req-2 was not "
                        + "acknowledged by the platform's access point (AS4 status SEND_FAILURE)."));
        assertThat(result(results, "T-ALL-04")).satisfies(result -> {
            assertThat(result.findings()).containsExactly(overdue);
            assertThat(result.note()).isEqualTo("max 2.4 s; measured from the submission to the gate's access point, so a "
                    + "delayed AS4 receipt (e.g. after retries) counts against the platform");
        });
        assertThat(Results.maxResponseTime(history)).contains(Duration.ofMillis(2400));
        assertThat(result(aggregate(run(List.of(), List.of()), List.of(), List.of(), Map.of(
                first.record().as4MessageId(), Results.ACKNOWLEDGED, second.record().as4MessageId(), Results.ACKNOWLEDGED)),
                "T-ALL-02").outcome()).isEqualTo(Outcome.PASSED);
    }

    @Test
    void connectionNeedsMessagesInBothDirectionsAndAReceipt() {
        ParsedMessage request = exchange(toPlatform(Fixtures.dataRequest("data-request", "req-1", DATASET_ID, "EU01"), T0));

        assertThat(result(aggregate(run(List.of(), List.of()), List.of(), List.of(),
                Map.of(request.record().as4MessageId(), "SEND_FAILURE")), "T-ALL-01").findings())
                .extracting(Finding::detail).containsExactly(
                        "No message sent to the platform was acknowledged with an AS4 receipt.",
                        "No message was received from the platform.");
        exchange(Fixtures.answer(request, Fixtures.errorResponse("data-response-error", "req-1", "x"), T0));
        assertThat(result(aggregate(run(List.of(), List.of()), List.of(), List.of(),
                Map.of(request.record().as4MessageId(), Results.ACKNOWLEDGED_WITH_WARNING)), "T-ALL-01"))
                .extracting(TestCaseResult::outcome, TestCaseResult::note).containsExactly(Outcome.PASSED,
                        "evidence: 1 of 1 message(s) to the platform acknowledged with an AS4 receipt, 1 message(s) received "
                                + "from the platform; the access points of this test setup are connected over plain HTTP, so TLS is not used");
    }

    @Test
    void receiptWithWarningCountsAndIsNoted() {
        ParsedMessage warned = exchange(toPlatform(Fixtures.dataRequest("data-request", "req-1", DATASET_ID, "EU01"), T0));

        assertThat(result(aggregate(run(List.of(), List.of()), List.of(), List.of(),
                Map.of(warned.record().as4MessageId(), Results.ACKNOWLEDGED_WITH_WARNING)), "T-ALL-02"))
                .extracting(TestCaseResult::outcome, TestCaseResult::note).containsExactly(Outcome.PASSED,
                        "1 message(s) acknowledged with a warning (AS4 status ACKNOWLEDGED_WITH_WARNING)");
    }

    @Test
    void overdueRequestsFailTheTestCaseTheyName() {
        Finding unanswered = new Finding("T-ALL-04", "m1", "No response");
        Finding unacknowledged = new Finding("T-ALL-03", "m2", "not acknowledged");

        List<TestCaseResult> results = aggregate(run(List.of(), List.of()), List.of(), List.of(unanswered, unacknowledged), Map.of());

        assertThat(result(results, "T-ALL-04").findings()).containsExactly(unanswered);
        assertThat(result(results, "T-ALL-03").findings()).containsExactly(unacknowledged);
    }
}
