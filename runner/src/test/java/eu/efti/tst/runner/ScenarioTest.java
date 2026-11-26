package eu.efti.tst.runner;

import eu.efti.tst.core.ConformanceContext;
import eu.efti.tst.core.Finding;
import eu.efti.tst.core.HtmlReport;
import eu.efti.tst.core.IdentifierType;
import eu.efti.tst.core.MessageKind;
import eu.efti.tst.core.ParsedMessage;
import eu.efti.tst.core.RunReport;
import eu.efti.tst.core.TestCaseIds;
import eu.efti.tst.core.TestCaseResult;
import eu.efti.tst.core.TestCaseResult.Outcome;
import eu.efti.tst.core.TestCatalogue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static eu.efti.tst.runner.Fixtures.DS1_PLAN;
import static eu.efti.tst.runner.Fixtures.FORMAT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/** The whole scenario against an in-memory platform, without Domibus. */
class ScenarioTest {

    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final ConformanceContext CONTEXT = new ConformanceContext(Fixtures.GATE, Fixtures.PLATFORM, TIMEOUT,
            ConformanceContext.Mode.TEST_RUN);

    private static final Duration OPERATOR_LIMIT = Duration.ofSeconds(1);
    private static final Duration CLOSING_WAIT = SimulatedPlatform.SECOND_RESPONSE_DELAY.multipliedBy(3);

    private static Scenario scenario(SimulatedPlatform platform) {
        GateEngine engine = new GateEngine(FORMAT, platform, CONTEXT, Duration.ofMillis(10), TIMEOUT, message -> { });
        return new Scenario(engine, platform, TIMEOUT, CLOSING_WAIT, OPERATOR_LIMIT);
    }

    private static RunEvidence run(SimulatedPlatform platform, DatasetPlan plan) {
        return scenario(platform).run(List.of(plan));
    }

    private static RunEvidence run(SimulatedPlatform platform) {
        return run(platform, DS1_PLAN);
    }

    private static List<TestCaseResult> results(SimulatedPlatform platform) {
        return Results.aggregate(TestCatalogue.load(), run(platform));
    }

    private static List<TestCaseResult> failed(List<TestCaseResult> results) {
        return results.stream().filter(result -> result.outcome() == Outcome.FAILED).toList();
    }

    /** Outcome per test case ID, with the data set key appended for per-data-set results. */
    private static Map<String, Outcome> outcomes(List<TestCaseResult> results) {
        return results.stream().collect(Collectors.toMap(
                result -> result.testCaseId() + (result.datasetKey() == null ? "" : "/" + result.datasetKey()),
                TestCaseResult::outcome));
    }

    private static TestCaseResult result(List<TestCaseResult> results, String testCaseId) {
        return results.stream().filter(result -> result.testCaseId().equals(testCaseId)).findFirst().orElseThrow();
    }

    private static List<String> details(TestCaseResult result) {
        return result.findings().stream().map(Finding::detail).toList();
    }

    private static Stream<String> requestableDatasets() {
        return Fixtures.CATALOGUE.datasets().stream().filter(dataset -> dataset.testData() != null)
                .map(DatasetCatalogue.Dataset::key);
    }

    @ParameterizedTest
    @ValueSource(ints = {60, 255, 512})
    void followUpTextHasExactlyTheGivenLengthAndHoldsUmlauts(int length) {
        assertThat(Scenario.followUpText(length)).hasSize(length).contains("zuständige Behörde");
    }

    @Test
    void conformantPlatformPassesAllApplicableTestCases() throws IOException {
        SimulatedPlatform platform = new SimulatedPlatform();
        Instant start = Instant.now();

        RunEvidence evidence = run(platform);
        List<TestCaseResult> results = Results.aggregate(TestCatalogue.load(), evidence);

        assertThat(results).filteredOn(result -> result.outcome() != Outcome.PASSED)
                .extracting(TestCaseResult::testCaseId, TestCaseResult::outcome)
                .containsExactlyInAnyOrder(tuple("T-NEG-01", Outcome.NOT_APPLICABLE), tuple("T-NEG-10", Outcome.NOT_APPLICABLE));
        assertThat(result(results, "T-NEG-04").note()).startsWith("observed: foreign platform ID: status "
                + FORMAT.invalidRequestStatus() + "; foreign gate ID: success status; ");
        assertThat(result(results, "T-NEG-05").note()).isEqualTo("observed: data request with an unknown subset: status "
                + FORMAT.invalidRequestStatus());
        assertThat(result(results, "T-RUN-02").note()).contains("UIL are not checked");
        // what the format cannot evaluate is stated with the result
        assertThat(result(results, "T-DAT-01").note()).contains("field lengths and code lists are only checked as far as the schema");
        assertThat(result(results, "T-DAT-02").note()).contains("gate and platform identifier, data set ID in responses, and message timestamp are not checked");
        assertThat(result(results, "T-DAT-03").note()).contains("only the mode of transport and the dangerous goods indicator are checked");
        assertThat(result(results, "T-ALL-03").note()).contains("has no message instance ID");
        assertThat(result(results, "T-NEG-04").note()).contains("whether a success response carries the injected identifier is not checked");
        assertThat(result(results, "T-NEG-08").note()).contains("whether a success acknowledgement carries the injected identifier is not checked");
        assertThat(result(results, "T-RUN-05").note()).contains("(b) not applicable");
        // the draft was refused as a draft, not as an unknown data set (the ID was typed in upper case)
        assertThat(evidence.history().messages()).filteredOn(message -> message.kind() == MessageKind.DATA_RESPONSE)
                .first().satisfies(response -> assertThat(response.description()).contains("The data set is a draft."));
        // the uploads on carrier signature, plate correction and consignee signature were each accepted
        assertThat(platform.received).filteredOn(message -> message.kind() == MessageKind.UPLOAD_STATUS)
                .extracting(ParsedMessage::success).containsExactly(true, true, true);
        // each upload status refers to the upload it answers, the requests to nothing
        assertThat(platform.received).filteredOn(message -> message.kind() == MessageKind.UPLOAD_STATUS)
                .extracting(message -> evidence.history().messages().stream()
                        .filter(upload -> upload.record().as4MessageId().equals(message.record().refToMessageId()))
                        .findFirst().orElseThrow().kind())
                .containsOnly(MessageKind.IDENTIFIER_UPLOAD);
        assertThat(platform.received).filteredOn(message -> message.kind() != MessageKind.UPLOAD_STATUS)
                .allSatisfy(request -> assertThat(request.record().refToMessageId()).isNull());
        // every exchange is tagged with its test case: the requests by the runner, the uploads by the platform
        assertThat(evidence.history().messages().stream().map(message -> message.record().testCaseId().orElseThrow()).distinct())
                .containsExactly("T-RUN-01", "T-RUN-02", "T-RUN-03", "T-RUN-04", "T-RUN-05", "T-NEG-02", "T-NEG-03", "T-NEG-04",
                        "T-NEG-05", "T-NEG-06", "T-NEG-07", "T-NEG-08", "T-NEG-09", "T-RUN-06");
        // after activation the requests use the data set ID as the platform uploaded it, not as typed
        assertThat(platform.received).filteredOn(message -> message.kind() == MessageKind.DATA_REQUEST)
                .extracting(message -> message.datasetId().orElseThrow())
                .startsWith(platform.datasetId.toUpperCase(), platform.datasetId);
        assertThat(evidence.receiptStatuses()).hasSize(platform.received.size()).containsValue(Results.ACKNOWLEDGED);

        RunReport report = new RunReport(Main.meta(new RunnerConfig.Report("Simulated platform (unit test)", "1.0",
                        "Musterfracht GmbH"), Fixtures.CATALOGUE, List.of(Fixtures.DS1_CONFIG), List.of("DS1"), CONTEXT,
                FORMAT.version(), start, Instant.now()), results, evidence.history().records(), null);
        assertThat(report.passed()).isTrue();
        Files.writeString(Path.of("target", "sample-report.html"), HtmlReport.render(report, TestCatalogue.load(), FORMAT));
    }

    @ParameterizedTest
    @MethodSource("requestableDatasets")
    void conformantPlatformPassesEveryDataSet(String key) {
        DatasetPlan plan = Fixtures.plan(key);

        List<TestCaseResult> results = Results.aggregate(TestCatalogue.load(), run(new SimulatedPlatform().holding(plan), plan));

        assertThat(failed(results)).isEmpty();
    }

    @Test
    void dataSetWhoseSubsetLacksTheTransportMeansPassesWithANote() {
        DatasetPlan air = Fixtures.plan("DS4");

        List<TestCaseResult> results = Results.aggregate(TestCatalogue.load(), run(new SimulatedPlatform().holding(air), air));

        assertThat(result(results, "T-RUN-04").note()).contains("corrected value is not compared");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DS1", "DS2", "DS3"})
    void omittingTheOptionalTransportMeansIdPassesWithNotes(String key) {
        DatasetPlan plan = Fixtures.plan(key);

        List<TestCaseResult> results = Results.aggregate(TestCatalogue.load(),
                run(new SimulatedPlatform(SimulatedPlatform.Fault.OMITS_TRANSPORT_MEANS_ID).holding(plan), plan));

        assertThat(failed(results)).isEmpty();
        assertThat(result(results, "T-RUN-04").note()).contains("corrected value is not compared");
        assertThat(result(results, "T-DAT-06").note()).contains("optional elements of the test data set not returned");
    }

    @ParameterizedTest
    @CsvSource({"DS1C, T-DAT-04", "DS1-DG, T-DAT-06", "DS2-DG, T-DAT-06", "DS3-DG, T-DAT-06"})
    void omittingTheTransportMeansIdFailsWhereItIsNotOptional(String key, String failingTestCase) {
        DatasetPlan plan = Fixtures.plan(key);

        List<TestCaseResult> results = Results.aggregate(TestCatalogue.load(),
                run(new SimulatedPlatform(SimulatedPlatform.Fault.OMITS_TRANSPORT_MEANS_ID).holding(plan), plan));

        assertThat(outcomes(results)).containsEntry(failingTestCase + "/" + key, Outcome.FAILED);
    }

    @Test
    void equalValuesInAnotherNotationPass() {
        // the cabotage data set holds all of them: weights, country codes, an umlaut in a city and a goods description
        DatasetPlan plan = Fixtures.plan("DS1C");

        List<TestCaseResult> results = Results.aggregate(TestCatalogue.load(),
                run(new SimulatedPlatform(SimulatedPlatform.Fault.OTHER_NOTATION_IN_RESPONSES).holding(plan), plan));

        assertThat(failed(results)).isEmpty();
        assertThat(result(results, "T-DAT-06").note()).contains("attributes not in the test data set: ")
                .contains("identificationText/@languageId");
    }

    @Test
    void cabotageResponseWithoutTheRegisteredDeliveryDateFailsIdentifierConsistency() {
        DatasetPlan plan = Fixtures.plan("DS1C");

        List<TestCaseResult> results = Results.aggregate(TestCatalogue.load(),
                run(new SimulatedPlatform(SimulatedPlatform.Fault.OMITS_ACTUAL_DELIVERY_DATE).holding(plan), plan));

        assertThat(details(result(results, "T-DAT-05")))
                .singleElement().asString().startsWith("ACTUAL_DELIVERY_DATE: registered [").endsWith("but the data set does not contain it.");
    }

    @Test
    void anImpossibleCarrierAcceptanceDateFailsSyntaxAndTheActivationStep() {
        Map<String, Outcome> outcomes = outcomes(results(new SimulatedPlatform(SimulatedPlatform.Fault.ZERO_CARRIER_ACCEPTANCE_DATE)));

        assertThat(outcomes).containsEntry("T-DAT-01/DS1", Outcome.FAILED).containsEntry("T-RUN-02/DS1", Outcome.FAILED);
    }

    /** As in production, the outage limit is longer than the wait for a response. */
    private static Scenario scenarioWithOutageLimit(SimulatedPlatform platform, Duration outageLimit) {
        return new Scenario(new GateEngine(FORMAT, platform, CONTEXT, Duration.ofMillis(10), outageLimit, message -> { }),
                platform, TIMEOUT, CLOSING_WAIT, OPERATOR_LIMIT);
    }

    private static SimulatedPlatform platformWithOutage(String testCaseId, Duration outage) {
        SimulatedPlatform platform = new SimulatedPlatform();
        platform.outageAtTestCase = testCaseId;
        platform.outage = outage;
        return platform;
    }

    @Test
    void outageDuringAResponseWaitThatEndsWithinTheLimitIsNotHeldAgainstThePlatform() {
        SimulatedPlatform platform = platformWithOutage("T-RUN-04", TIMEOUT.multipliedBy(2));

        List<TestCaseResult> results = Results.aggregate(TestCatalogue.load(),
                scenarioWithOutageLimit(platform, Duration.ofSeconds(5)).run(List.of(DS1_PLAN)));

        assertThat(failed(results)).isEmpty();
        assertThat(result(results, "T-ALL-04").note()).contains("not evaluated");
    }

    @Test
    void outageBeyondTheLimitDuringAResponseWaitStopsTheRunWithoutFailingThePlatform() {
        SimulatedPlatform platform = platformWithOutage("T-RUN-04", Duration.ofSeconds(30));
        Scenario scenario = scenarioWithOutageLimit(platform, TIMEOUT.multipliedBy(2));

        assertThatThrownBy(() -> scenario.run(List.of(DS1_PLAN))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("for more than");
        assertThat(scenario.stepsSoFar()).extracting(TestCaseResult::testCaseId).doesNotContain("T-RUN-04");
        // the request is unanswered for longer than the timeout, but the gate could not fetch a response
        assertThat(failed(Results.stopped(TestCatalogue.load(), scenario.evidenceSoFar(), List.of("DS1"), scenario.stepsSoFar())))
                .isEmpty();
    }

    @Test
    void unansweredRequestIsHeldAgainstThePlatformInARunStoppedWithoutOutage() {
        DatasetPlan ds2 = Fixtures.plan("DS2");
        SimulatedPlatform platform = new SimulatedPlatform(SimulatedPlatform.Fault.SILENT_REJECTIONS).holding(DS1_PLAN, ds2);
        platform.stopsAtDataSet = 2;
        Scenario scenario = scenario(platform);

        assertThatThrownBy(() -> scenario.run(List.of(DS1_PLAN, ds2))).isInstanceOf(IllegalStateException.class);

        assertThat(outcomes(Results.stopped(TestCatalogue.load(), scenario.evidenceSoFar(), List.of("DS2"), scenario.stepsSoFar())))
                .containsEntry("T-ALL-04", Outcome.FAILED);
    }

    @Test
    void lateResponseToARequestSentAfterAnOutageStillFailsTheResponseTime() {
        SimulatedPlatform slow = platformWithOutage("T-RUN-01", Duration.ofMillis(50));
        slow.responseDelay = TIMEOUT.plusMillis(10);

        List<TestCaseResult> results = Results.aggregate(TestCatalogue.load(),
                scenarioWithOutageLimit(slow, Duration.ofSeconds(5)).run(List.of(DS1_PLAN)));

        assertThat(outcomes(results)).containsEntry("T-ALL-04", Outcome.FAILED);
    }

    @Test
    void unansweredRequestIsHeldAgainstThePlatformAfterAWaitAsLongAsWithoutOutage() {
        SimulatedPlatform platform = new SimulatedPlatform(SimulatedPlatform.Fault.SILENT_REJECTIONS);
        platform.outageAtTestCase = "T-NEG-03";
        platform.outage = Duration.ofMillis(50);

        List<TestCaseResult> results = Results.aggregate(TestCatalogue.load(),
                scenarioWithOutageLimit(platform, Duration.ofSeconds(5)).run(List.of(DS1_PLAN)));

        assertThat(outcomes(results)).containsEntry("T-NEG-03", Outcome.FAILED);
    }

    @Test
    void staleIdentifierUpdateAbortsTheLifecycleButNotTheNegativeCases() {
        List<TestCaseResult> results = results(new SimulatedPlatform(SimulatedPlatform.Fault.STALE_UPLOAD_AFTER_CORRECTION));

        assertThat(outcomes(results)).containsEntry("T-RUN-01/DS1", Outcome.PASSED).containsEntry("T-RUN-02/DS1", Outcome.PASSED)
                .containsEntry("T-RUN-03/DS1", Outcome.FAILED).containsEntry("T-RUN-04/DS1", Outcome.FAILED)
                .containsEntry("T-RUN-06/DS1", Outcome.FAILED).containsEntry("T-DAT-06/DS1", Outcome.NOT_APPLICABLE)
                .containsEntry("T-NEG-03", Outcome.PASSED).containsEntry("T-ALL-02", Outcome.PASSED);
        // the unchanged upload is not the step's upload, but evaluated after the timeout, so the finding names the value
        assertThat(details(result(results, "T-RUN-03"))).containsExactly("Identifier TRANSPORT_MEANS: expected "
                + DS1_PLAN.identifiersCorrected().get(IdentifierType.TRANSPORT_MEANS) + " but found "
                + DS1_PLAN.identifiersAsCreated().get(IdentifierType.TRANSPORT_MEANS) + ".");
        assertThat(result(results, "T-RUN-06").note()).isEqualTo("not executed: T-RUN-03 failed");
        assertThat(result(results, "T-DAT-06").note()).isEqualTo("not evaluated: no data request of T-RUN-04 was answered with data");
    }

    @Test
    void platformAcceptingInvalidRequestsFailsTheNegativeCasesWithoutFalseMonitoringFindings() {
        List<TestCaseResult> results = results(new SimulatedPlatform(SimulatedPlatform.Fault.ACCEPTS_EVERYTHING));

        assertThat(outcomes(results)).containsEntry("T-RUN-01/DS1", Outcome.PASSED)
                .containsEntry("T-NEG-02", Outcome.FAILED).containsEntry("T-NEG-03", Outcome.FAILED)
                .containsEntry("T-NEG-05", Outcome.FAILED).containsEntry("T-NEG-06", Outcome.FAILED)
                .containsEntry("T-NEG-07", Outcome.FAILED).containsEntry("T-NEG-09", Outcome.FAILED)
                .containsEntry("T-NEG-04", Outcome.PASSED)
                // the success responses to the requests without request ID are judged by T-NEG-02/06 only
                .containsEntry("T-ALL-03", Outcome.PASSED).containsEntry("T-ALL-04", Outcome.PASSED);
        assertThat(details(result(results, "T-NEG-02"))).containsExactlyInAnyOrder(
                "schema-invalid data request: answered with success status instead of an error.",
                "schema-invalid data request: the response carries a data set.",
                "data request without request ID: answered with success status instead of an error.",
                "data request without request ID: the response carries a data set.");
        assertThat(details(result(results, "T-NEG-05"))).containsExactly(
                "data request with an unknown subset: answered with success status instead of an error.",
                "data request with an unknown subset: the response carries a data set.");
        assertThat(details(result(results, "T-NEG-09"))).containsExactly(
                "follow-up for an unknown request ID: answered with success status instead of an error.");
    }

    @Test
    void errorWithoutDescriptionFailsTheUnknownDataSetCaseAndTheStatusLogic() {
        List<TestCaseResult> results = results(new SimulatedPlatform(SimulatedPlatform.Fault.BLANK_ERROR_DESCRIPTIONS));

        assertThat(failed(results)).extracting(TestCaseResult::testCaseId).containsExactly("T-DAT-02", "T-NEG-03");
        assertThat(details(result(results, "T-NEG-03"))).containsExactly(
                "data request for an unknown data set: the error response carries no error description.");
    }

    @Test
    void unansweredFollowUpsFailTheStepAndTheCorrelationButNotTheResponseTime() {
        List<TestCaseResult> results = results(new SimulatedPlatform(SimulatedPlatform.Fault.IGNORES_FOLLOW_UPS));

        assertThat(outcomes(results)).containsEntry("T-RUN-04/DS1", Outcome.PASSED).containsEntry("T-RUN-05/DS1", Outcome.FAILED)
                .containsEntry("T-RUN-06/DS1", Outcome.FAILED).containsEntry("T-DAT-06/DS1", Outcome.PASSED)
                // the follow-ups were received (AS4 receipt), only not answered
                .containsEntry("T-ALL-02", Outcome.PASSED).containsEntry("T-ALL-04", Outcome.PASSED)
                .containsEntry("T-NEG-08", Outcome.FAILED).containsEntry("T-ALL-03", Outcome.FAILED);
        assertThat(details(result(results, "T-ALL-03"))).isNotEmpty()
                .allSatisfy(detail -> assertThat(detail).startsWith("The FOLLOW_UP").endsWith("was not acknowledged within 0.3 s."));
    }

    @Test
    void lateResponsesFailOnlyTheResponseTime() {
        SimulatedPlatform slow = new SimulatedPlatform();
        // late (the measured response time includes the full delay), but as early as possible before the gate stops
        // waiting at 1.2 times the timeout
        slow.responseDelay = TIMEOUT.plusMillis(10);

        List<TestCaseResult> results = results(slow);

        assertThat(results).filteredOn(result -> result.outcome() == Outcome.FAILED)
                .extracting(TestCaseResult::testCaseId).containsExactly("T-ALL-04");
        assertThat(result(results, "T-ALL-04").findings()).isNotEmpty()
                .allSatisfy(finding -> assertThat(finding.detail()).startsWith("Answered after"));
    }

    @Test
    void uploadOfTheDraftFailsTheDraftStep() {
        List<TestCaseResult> results = results(new SimulatedPlatform(SimulatedPlatform.Fault.REGISTERS_DRAFT));

        assertThat(details(result(results, "T-RUN-01"))).singleElement().asString()
                .startsWith("The platform sent an identifier upload for the data set before the runner asked for the carrier signature");
        assertThat(result(results, "T-RUN-02").note()).isEqualTo("not executed: T-RUN-01 failed");
        assertNegativeCasesNotExecuted(results);
        // only the upload was received, so the checks of returned data sets have no evidence
        assertThat(result(results, "T-DAT-03").outcome()).isEqualTo(Outcome.PASSED);
        assertThat(result(results, "T-DAT-04")).extracting(TestCaseResult::outcome, TestCaseResult::note)
                .containsExactly(Outcome.NOT_APPLICABLE, "no data set received in this run");
    }

    @Test
    void resentUploadsAreNotTakenForTheNextStep() {
        List<TestCaseResult> results = results(new SimulatedPlatform(SimulatedPlatform.Fault.RESENDS_PREVIOUS_UPLOAD));

        assertThat(results).filteredOn(result -> result.testCaseId().startsWith("T-RUN"))
                .extracting(TestCaseResult::outcome).containsOnly(Outcome.PASSED);
        // resending with the same request ID is a uniqueness violation, though
        assertThat(details(result(results, "T-ALL-03"))).isNotEmpty()
                .allSatisfy(detail -> assertThat(detail).contains("already used by an earlier identifier upload"));
    }

    @Test
    void stepDoneBeforeTheRunnerAskedIsAcceptedWithANote() {
        List<TestCaseResult> results = results(new SimulatedPlatform(SimulatedPlatform.Fault.OPERATOR_AHEAD));

        assertThat(result(results, "T-RUN-02").outcome()).isEqualTo(Outcome.PASSED);
        assertThat(result(results, "T-RUN-02").note()).doesNotContain("the identifier upload arrived before the runner asked");
        assertThat(result(results, "T-RUN-03")).extracting(TestCaseResult::outcome, TestCaseResult::note)
                .containsExactly(Outcome.PASSED, "the identifier upload arrived before the runner asked for this step");
        assertThat(result(results, "T-RUN-06").outcome()).isEqualTo(Outcome.PASSED);
    }

    @Test
    void stepsAdvanceOnTheExpectedUploadWithoutConfirmation() {
        SimulatedPlatform platform = new SimulatedPlatform(SimulatedPlatform.Fault.OPERATOR_CORRECTS_IN_ATTEMPTS);
        platform.confirms = false;

        List<TestCaseResult> results = results(platform);

        // the updates without and with a wrong correction before the right one are not the step's upload
        assertThat(results).filteredOn(result -> result.testCaseId().startsWith("T-RUN"))
                .extracting(TestCaseResult::outcome).containsOnly(Outcome.PASSED);
        assertThat(result(results, "T-RUN-03").note()).startsWith("2 earlier identifier upload(s) did not count for this step");
    }

    @Test
    void partialUpdateBeforeTheFullOneIsOnlyNotedOnTheStep() {
        List<TestCaseResult> results = results(new SimulatedPlatform(SimulatedPlatform.Fault.PARTIAL_UPDATE_BEFORE_FULL));

        assertThat(failed(results)).isEmpty();
        assertThat(result(results, "T-RUN-03").note()).startsWith("1 earlier identifier upload(s) did not count for this step");
    }

    @Test
    void runStopsIfNoUploadComesAndTheOperatorDoesNotConfirm() {
        SimulatedPlatform platform = new SimulatedPlatform(SimulatedPlatform.Fault.NO_UPLOAD_AFTER_CORRECTION);
        platform.confirms = false;
        Scenario scenario = scenario(platform);

        assertThatThrownBy(() -> scenario.run(List.of(DS1_PLAN))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not confirm the step");
        assertThat(scenario.stepsSoFar()).extracting(TestCaseResult::testCaseId, TestCaseResult::outcome)
                .containsExactly(tuple("T-RUN-01", Outcome.PASSED), tuple("T-RUN-02", Outcome.PASSED));
    }

    @Test
    void deviatingUploadIsExplainedOnceAndFailsTheStepAtTheLimitWithoutConfirmation() {
        SimulatedPlatform platform = new SimulatedPlatform(SimulatedPlatform.Fault.STALE_UPLOAD_AFTER_CORRECTION);
        platform.confirms = false;

        List<TestCaseResult> results = results(platform);

        assertThat(result(results, "T-RUN-02").outcome()).isEqualTo(Outcome.PASSED);
        assertThat(details(result(results, "T-RUN-03"))).singleElement().asString().startsWith("Identifier TRANSPORT_MEANS: expected");
        assertThat(platform.progress).filteredOn(line -> line.contains("does not count")).singleElement().asString()
                .startsWith("[DS1] This identifier upload does not count for T-RUN-03:\n    Identifier TRANSPORT_MEANS: expected")
                .contains("press Enter");
    }

    /** Scripts driving the runner rely on this form, see "Driving a run from a script" in testdata/README.md. */
    @Test
    void instructionsNameTheDataSetAndTheStep() {
        SimulatedPlatform platform = new SimulatedPlatform();

        run(platform);

        assertThat(platform.instructions).hasSize(4);
        assertThat(platform.instructions.get(0)).startsWith("[DS1] Step 1 of 4: ");
        assertThat(platform.instructions.get(1)).startsWith("[DS1] Step 2 of 4: ");
        assertThat(platform.instructions.get(2)).startsWith("[DS1] Step 3 of 4: ");
        assertThat(platform.instructions.get(3)).startsWith("[DS1] Step 4 of 4: ");
    }

    @Test
    void mistypedDataSetIdIsPointedOut() {
        SimulatedPlatform platform = new SimulatedPlatform(SimulatedPlatform.Fault.MISTYPED_ID);

        List<TestCaseResult> results = results(platform);

        assertThat(result(results, "T-RUN-01").outcome()).isEqualTo(Outcome.PASSED);
        assertThat(details(result(results, "T-RUN-02"))).singleElement().asString()
                .contains("The platform uploaded identifiers for other data set IDs (" + platform.datasetId + ")");
        assertNegativeCasesNotExecuted(results);
    }

    @Test
    void elementsOutsideTheSingleRequestedSubsetFailTheDataRequestStep() {
        List<TestCaseResult> results = results(new SimulatedPlatform(SimulatedPlatform.Fault.IGNORES_SUBSETS));

        assertThat(details(result(results, "T-RUN-04"))).isNotEmpty()
                .allSatisfy(detail -> assertThat(detail).startsWith("(b) subset EU01: ").endsWith(" is not part of the requested subsets."));
        assertThat(result(results, "T-DAT-04").outcome()).isEqualTo(Outcome.FAILED);
    }

    /** The negative cases were reported as not executed, and no probe was sent. */
    private static void assertNegativeCasesNotExecuted(List<TestCaseResult> results) {
        assertThat(results).filteredOn(result -> TestCaseIds.NEGATIVE_EXECUTED.contains(result.testCaseId()))
                .hasSize(TestCaseIds.NEGATIVE_EXECUTED.size())
                .allSatisfy(result -> assertThat(result).extracting(TestCaseResult::outcome, TestCaseResult::note).containsExactly(
                        Outcome.NOT_APPLICABLE, "not executed: no data set was activated"));
    }

    @Test
    void negativeCasesAreNotRunWithoutActivation() {
        RunEvidence evidence = run(new SimulatedPlatform(SimulatedPlatform.Fault.REGISTERS_DRAFT));

        assertThat(evidence.probes()).isEmpty();
        assertThat(evidence.history().messages()).filteredOn(message -> message.kind() == MessageKind.FOLLOW_UP).isEmpty();
    }

    @Test
    void draftAnsweredAgainWithDataFailsTheDraftStep() {
        List<TestCaseResult> results = results(new SimulatedPlatform(SimulatedPlatform.Fault.ANSWERS_DRAFT_AGAIN_WITH_DATA));

        // the first response is the expected error, the second one is evaluated as well
        assertThat(details(result(results, "T-RUN-01"))).containsExactly(
                "Data request for the draft: answered with success status instead of an error.",
                "Data request for the draft: the response carries a data set.");
        assertThat(details(result(results, "T-ALL-03"))).singleElement().asString().contains("was already answered");
        // the data of the draft is not the content the round trip expects
        assertThat(result(results, "T-DAT-06")).extracting(TestCaseResult::outcome, TestCaseResult::note)
                .containsExactly(Outcome.NOT_APPLICABLE, "not evaluated: no data request of T-RUN-04 was answered with data");
        assertNegativeCasesNotExecuted(results);
    }

    @Test
    void unansweredDraftRequestFailsTheDraftStepAndTheResponseTime() {
        List<TestCaseResult> results = results(new SimulatedPlatform(SimulatedPlatform.Fault.IGNORES_DRAFT_REQUESTS));

        assertThat(details(result(results, "T-RUN-01")))
                .containsExactly("Data request for the draft: no response within the response timeout.");
        assertThat(details(result(results, "T-ALL-04"))).singleElement().asString().startsWith("No response to the DATA_REQUEST");
    }

    @Test
    void unansweredProbesFailTheNegativeCasesThatRequireAResponse() {
        List<TestCaseResult> results = results(new SimulatedPlatform(SimulatedPlatform.Fault.SILENT_REJECTIONS));

        assertThat(results).filteredOn(result -> result.testCaseId().startsWith("T-RUN"))
                .extracting(TestCaseResult::outcome).containsOnly(Outcome.PASSED);
        assertThat(details(result(results, "T-NEG-02")))
                .containsExactly("schema-invalid data request: no response within the response timeout.");
        assertThat(details(result(results, "T-NEG-04")))
                .containsExactly("foreign platform ID: no response within the response timeout.");
        assertThat(details(result(results, "T-NEG-05")))
                .containsExactly("data request with an unknown subset: no response within the response timeout.");
        assertThat(details(result(results, "T-NEG-09")))
                .containsExactly("follow-up for an unknown request ID: no response within the response timeout.");
        // the probes without request ID may stay unanswered
        assertThat(details(result(results, "T-NEG-06")))
                .containsExactly("schema-invalid follow-up: no response within the response timeout.");
    }

    @Test
    void successResponseEchoingAnInjectedIdentifierFailsTheNegativeCase() {
        List<TestCaseResult> results = results(new SimulatedPlatform(SimulatedPlatform.Fault.ECHOES_UIL));

        assertThat(details(result(results, "T-NEG-04")))
                .containsExactly("foreign gate ID: the success response carries the injected identifier " + Scenario.FOREIGN_GATE_ID + ".");
        assertThat(details(result(results, "T-NEG-08")))
                .containsExactly("foreign gate ID: the success response carries the injected identifier " + Scenario.FOREIGN_GATE_ID + ".");
    }

    @Test
    void acknowledgementNotEchoingTheDataRequestFailsTheFollowUpStep() {
        List<TestCaseResult> results = results(new SimulatedPlatform(SimulatedPlatform.Fault.ECHOES_OWN_FOLLOW_UP_ID));

        assertThat(details(result(results, "T-RUN-05"))).singleElement().asString()
                .contains("the acknowledgement echoes request ID").endsWith("the request ID of the data request the follow-up refers to.");
        // the format accepts the follow-up's own request ID for the correlation
        assertThat(result(results, "T-ALL-03").outcome()).isEqualTo(Outcome.PASSED);
    }

    @Test
    void failingDataRequestAfterDeliveryFailsTheConsigneeStepOfARoadDataSet() {
        List<TestCaseResult> results = results(new SimulatedPlatform(SimulatedPlatform.Fault.REJECTS_AFTER_DELIVERY));

        assertThat(details(result(results, "T-RUN-06"))).containsExactly(
                "Data request after delivery: answered with status " + FORMAT.notFoundStatus() + " instead of success.");
    }

    @Test
    void secondResponseArrivingAfterTheLastStepFailsTheCorrelation() {
        List<TestCaseResult> results = results(new SimulatedPlatform(SimulatedPlatform.Fault.ANSWERS_AGAIN_AFTER_DELIVERY));

        assertThat(failed(results)).extracting(TestCaseResult::testCaseId).containsExactly("T-ALL-03");
        assertThat(details(result(results, "T-ALL-03"))).singleElement().asString().endsWith("was already answered.");
    }

    @Test
    void nonRoadDataSetIsNotRequestedAfterDelivery() {
        DatasetPlan nonRoad = Fixtures.plan("DS2");

        List<TestCaseResult> results = Results.aggregate(TestCatalogue.load(),
                run(new SimulatedPlatform(SimulatedPlatform.Fault.REJECTS_AFTER_DELIVERY).holding(nonRoad), nonRoad));

        assertThat(result(results, "T-RUN-06")).extracting(TestCaseResult::outcome, TestCaseResult::note)
                .containsExactly(Outcome.PASSED, "retrievability after delivery is only checked for road data sets");
    }

    @Test
    void platformNotAnsweringAfterNonRoadDeliveryPassesTheNegativeCases() {
        DatasetPlan rail = Fixtures.plan("DS2");
        SimulatedPlatform platform = new SimulatedPlatform(SimulatedPlatform.Fault.SILENT_AFTER_NON_ROAD_DELIVERY).holding(rail, DS1_PLAN);

        List<TestCaseResult> results = Results.aggregate(TestCatalogue.load(), scenario(platform).run(List.of(rail, DS1_PLAN)));

        assertThat(failed(results)).isEmpty();
        assertThat(outcomes(results)).containsEntry("T-NEG-03", Outcome.PASSED).containsEntry("T-NEG-05", Outcome.PASSED)
                .containsEntry("T-NEG-09", Outcome.PASSED).containsEntry("T-NEG-10", Outcome.PASSED);
    }

    @Test
    void responsesInNewConversationsArePairedByTheirRefToMessageId() {
        assertThat(failed(results(new SimulatedPlatform(SimulatedPlatform.Fault.NEW_CONVERSATIONS)))).isEmpty();
    }

    @Test
    void responsesWithoutRefToMessageIdFailOnlyTheCorrelation() {
        List<TestCaseResult> results = results(new SimulatedPlatform(SimulatedPlatform.Fault.OMITS_REF_TO_MESSAGE_ID));

        assertThat(failed(results)).extracting(TestCaseResult::testCaseId).containsExactly("T-ALL-03");
        assertThat(details(result(results, "T-ALL-03"))).isNotEmpty()
                .allSatisfy(detail -> assertThat(detail).startsWith("The response carries no AS4 RefToMessageId"));
    }

    private static List<ParsedMessage> followUpsOf(SimulatedPlatform platform, String testCaseId) {
        return platform.received.stream().filter(message -> message.kind() == MessageKind.FOLLOW_UP)
                .filter(message -> message.record().testCaseId().filter(testCaseId::equals).isPresent()).toList();
    }

    @Test
    void dataSetsRunOneAfterTheOtherWithOneRunOfTheNegativeCases() {
        DatasetPlan rail = Fixtures.plan("DS2");
        SimulatedPlatform platform = new SimulatedPlatform().holding(DS1_PLAN, rail);

        RunEvidence evidence = scenario(platform).run(List.of(DS1_PLAN, rail));
        List<TestCaseResult> results = Results.aggregate(TestCatalogue.load(), evidence);

        assertThat(failed(results)).isEmpty();
        assertThat(outcomes(results)).containsEntry("T-RUN-06/DS1", Outcome.PASSED).containsEntry("T-RUN-06/DS2", Outcome.PASSED)
                .containsEntry("T-DAT-06/DS1", Outcome.PASSED).containsEntry("T-DAT-06/DS2", Outcome.PASSED)
                .containsEntry("T-NEG-10", Outcome.PASSED);
        assertThat(followUpsOf(platform, "T-NEG-07")).hasSize(1);
        // the negative cases run against the first data set, T-NEG-10 mixes the two
        assertThat(followUpsOf(platform, "T-NEG-08")).allSatisfy(probe -> assertThat(probe.datasetId()).contains(platform.datasetIdOf(0)));
        assertThat(followUpsOf(platform, "T-NEG-10")).singleElement().satisfies(probe -> {
            assertThat(probe.datasetId()).contains(platform.datasetIdOf(1));
            assertThat(probe.referencedRequestId()).contains(evidence.runs().getFirst().dataRequestId().orElseThrow());
        });
    }

    @Test
    void platformNotCheckingThePairOfRequestAndDataSetFailsTheFollowUpMismatch() {
        DatasetPlan rail = Fixtures.plan("DS2");
        SimulatedPlatform platform = new SimulatedPlatform(SimulatedPlatform.Fault.IGNORES_REQUEST_OF_OTHER_DATA_SET).holding(DS1_PLAN, rail);

        List<TestCaseResult> results = Results.aggregate(TestCatalogue.load(), scenario(platform).run(List.of(DS1_PLAN, rail)));

        assertThat(failed(results)).extracting(TestCaseResult::testCaseId).containsExactly("T-NEG-10");
    }

    @Test
    void negativeCasesRunAgainstTheNextDataSetIfTheFirstIsNotActivated() {
        DatasetPlan rail = Fixtures.plan("DS2");
        SimulatedPlatform platform = new SimulatedPlatform(SimulatedPlatform.Fault.REGISTERS_FIRST_DRAFT).holding(DS1_PLAN, rail);

        List<TestCaseResult> results = Results.aggregate(TestCatalogue.load(), scenario(platform).run(List.of(DS1_PLAN, rail)));

        assertThat(outcomes(results)).containsEntry("T-RUN-01/DS1", Outcome.FAILED).containsEntry("T-RUN-06/DS2", Outcome.PASSED)
                .containsEntry("T-NEG-03", Outcome.PASSED).containsEntry("T-NEG-10", Outcome.NOT_APPLICABLE);
        assertThat(followUpsOf(platform, "T-NEG-08")).allSatisfy(probe -> assertThat(probe.datasetId()).contains(platform.datasetIdOf(1)));
        // the failure of the first data set does not spoil the results of the second
        assertThat(failed(results)).extracting(TestCaseResult::datasetKey).containsOnly("DS1");
    }

    @Test
    void followUpMismatchNeedsADataRequestAfterActivation() {
        DatasetPlan rail = Fixtures.plan("DS2");
        // Both data sets are activated, but the plate correction of both fails, so neither gets to the data request.
        SimulatedPlatform platform = new SimulatedPlatform(SimulatedPlatform.Fault.STALE_UPLOAD_AFTER_CORRECTION).holding(DS1_PLAN, rail);

        RunEvidence evidence = scenario(platform).run(List.of(DS1_PLAN, rail));

        assertThat(evidence.runs()).allSatisfy(run -> assertThat(run.activeDatasetId()).isPresent());
        assertThat(result(Results.aggregate(TestCatalogue.load(), evidence), "T-NEG-10"))
                .extracting(TestCaseResult::outcome, TestCaseResult::note)
                .containsExactly(Outcome.NOT_APPLICABLE, "needs two data sets activated during the run, one of them with a data request");
        assertThat(followUpsOf(platform, "T-NEG-10")).isEmpty();
    }

    @Test
    void aSingleSubsetIsRequestedOnceAndCheckedForElementsOutsideIt() {
        DatasetPlan rail = Fixtures.plan("DS2");
        assertThat(rail.subsets()).hasSize(1);
        SimulatedPlatform conformant = new SimulatedPlatform().holding(rail);

        List<TestCaseResult> results = Results.aggregate(TestCatalogue.load(), run(conformant, rail));

        assertThat(failed(results)).isEmpty();
        assertThat(result(results, "T-RUN-04").note()).contains("(b) not applicable");
        assertThat(conformant.received).filteredOn(message -> message.kind() == MessageKind.DATA_REQUEST)
                .filteredOn(message -> message.record().testCaseId().filter("T-RUN-04"::equals).isPresent()).hasSize(1);

        SimulatedPlatform unfiltered = new SimulatedPlatform(SimulatedPlatform.Fault.DOES_NOT_FILTER).holding(rail);
        assertThat(result(Results.aggregate(TestCatalogue.load(), run(unfiltered, rail)), "T-RUN-04").outcome()).isEqualTo(Outcome.FAILED);
    }

    @Test
    void lateMessageOfAnEarlierDataSetCountsForThatDataSet() {
        DatasetPlan rail = Fixtures.plan("DS2");
        SimulatedPlatform platform = new SimulatedPlatform(SimulatedPlatform.Fault.LATE_UPLOAD_OF_FIRST_DATA_SET).holding(DS1_PLAN, rail);

        RunEvidence evidence = scenario(platform).run(List.of(DS1_PLAN, rail));

        assertThat(evidence.received()).filteredOn(message -> message.message().datasetId().filter(platform.datasetIdOf(0)::equalsIgnoreCase).isPresent())
                .isNotEmpty().allSatisfy(message -> assertThat(message.datasetKey()).isEqualTo("DS1"));
    }
}
