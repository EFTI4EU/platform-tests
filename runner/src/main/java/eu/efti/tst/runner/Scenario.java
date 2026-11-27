package eu.efti.tst.runner;

import eu.efti.tst.core.ConformanceChecks;
import eu.efti.tst.core.ConformanceContext;
import eu.efti.tst.core.Finding;
import eu.efti.tst.core.IdentifierSet;
import eu.efti.tst.core.IdentifierType;
import eu.efti.tst.core.MessageRecord;
import eu.efti.tst.core.ParsedMessage;
import eu.efti.tst.core.TestCaseIds;
import eu.efti.tst.core.TestCaseResult;
import eu.efti.tst.runner.NegativeCases.Probe;
import eu.efti.tst.runner.RunEvidence.DatasetRun;
import eu.efti.tst.runner.StepChecks.Expectation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Drives the test scenario: the lifecycle of every requested data set one after the other (section 2.1 of the report),
 * and the negative cases (section 2.4) before the consignee signature of the first data set that is activated.
 */
final class Scenario {

    static final String FOREIGN_GATE_ID = "TEST-FOREIGN-GATE";
    static final String FOREIGN_PLATFORM_ID = "TEST-FOREIGN-PLATFORM";
    private static final int OPERATOR_STEPS = 4;
    private static final String OPERATOR_GUIDE = "testdata/README.md";

    private final GateEngine engine;
    private final Operator operator;
    private final Duration receiptGrace;
    private final Duration closingWait;
    private final Duration operatorLimit;
    private final ConformanceContext context;
    private final String followUpText;
    // These lists are also read by the shutdown hook that reports an interrupted run.
    private final List<TestCaseResult> stepsSoFar = new CopyOnWriteArrayList<>();
    private final List<DatasetRun> finishedRuns = new CopyOnWriteArrayList<>();
    private final List<TestCaseResult> negativeResults = new CopyOnWriteArrayList<>();
    private final List<Probe> probes = new CopyOnWriteArrayList<>();
    private boolean negativeCasesRun;
    private boolean followUpMismatchRun;

    /**
     * @param receiptGrace  how long to wait at the end for AS4 receipts of messages still in transit (T-ALL-02)
     * @param closingWait   how long to keep polling the platform's messages after the last step
     * @param operatorLimit how long to wait for the operator to do a step before the run is stopped
     */
    Scenario(GateEngine engine, Operator operator, Duration receiptGrace, Duration closingWait, Duration operatorLimit) {
        this.engine = engine;
        this.operator = operator;
        this.receiptGrace = receiptGrace;
        this.closingWait = closingWait;
        this.operatorLimit = operatorLimit;
        this.context = engine.context();
        this.followUpText = followUpText(engine.format().maxFollowUpTextLength());
    }

    /**
     * A follow-up text of exactly the given length, the maximum the platform must accept. The umlauts come early, so
     * that a short maximum keeps them: a platform that cannot process non-ASCII text shows in its acknowledgement.
     */
    static String followUpText(int length) {
        String text = "Test follow-up communication of the zuständige Behörde (competent authority), filled to the maximum length of " + length
                + " characters: ";
        return (text + "0123456789".repeat(length / 10 + 1)).substring(0, length);
    }

    /** The results of the lifecycle steps determined so far, for the report of a run that stopped. */
    List<TestCaseResult> stepsSoFar() {
        return List.copyOf(stepsSoFar);
    }

    /** The keys of the data sets whose run ended, for the report. */
    List<String> finishedKeys() {
        return finishedRuns.stream().map(DatasetRun::key).toList();
    }

    /** What the run observed so far of the data sets whose run ended, for the report of a run that stopped. */
    RunEvidence evidenceSoFar() {
        return evidence(Map.of(), engine.overdueAtStop());
    }

    private RunEvidence evidence(Map<String, String> receiptStatuses, List<Finding> overdue) {
        return new RunEvidence(List.copyOf(finishedRuns), List.copyOf(negativeResults), List.copyOf(probes), engine.received(),
                overdue, receiptStatuses, engine.history(), engine.responseTimesNotEvaluated());
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    private ParsedMessage dataRequest(String testCaseId, String template, String gateId, String platformId, String datasetId,
                                      List<String> subsets) {
        return engine.request(testCaseId, template, Map.of("requestId", newRequestId(), "gateId", gateId, "platformId", platformId,
                "datasetId", datasetId, "subsetIds", String.join(" ", subsets)));
    }

    private ParsedMessage followUp(String testCaseId, String template, String gateId, String platformId, String datasetId,
                                   String referencedRequestId) {
        return engine.request(testCaseId, template, Map.of("requestId", newRequestId(), "referencedRequestId", referencedRequestId,
                "gateId", gateId, "platformId", platformId, "datasetId", datasetId, "message", followUpText));
    }

    /** The identifier upload of an operator step, the step's findings on it and notes on how it came. */
    private record StepUpload(Optional<ParsedMessage> upload, List<Finding> failures, List<String> notes) {
    }

    /** Mutable state of one data set's lifecycle run. */
    private final class Lifecycle {
        final DatasetPlan plan;
        final List<TestCaseResult> steps = new ArrayList<>();
        final List<MessageRecord> dataResponses = new ArrayList<>();
        String datasetId;
        String activeDatasetId;
        String lodgedRequestId;
        String dataRequestId;
        Optional<ParsedMessage> previousUpload = Optional.empty();
        Optional<ParsedMessage> explainedUpload = Optional.empty();

        Lifecycle(DatasetPlan plan) {
            this.plan = plan;
        }

        String instruction(int step, String action) {
            return "[" + plan.key() + "] Step " + step + " of " + OPERATOR_STEPS + ": " + action;
        }

        boolean record(String testCaseId, List<Finding> failures, List<String> notes) {
            TestCaseResult result = StepChecks.result(testCaseId, plan.key(), failures, notes.isEmpty() ? null : String.join("; ", notes));
            steps.add(result);
            stepsSoFar.add(result);
            operator.progress("[" + plan.key() + "] " + testCaseId + " " + result.outcome()
                    + failures.stream().map(Finding::detail).collect(Collectors.joining("\n    ", failures.isEmpty() ? "" : ":\n    ", "")));
            return result.outcome() == TestCaseResult.Outcome.PASSED;
        }

        /** Waits for the response and keeps it for the round trip if it carries a data set. */
        Optional<ParsedMessage> awaitResponse(ParsedMessage request) {
            Optional<ParsedMessage> response = engine.awaitResponse(request);
            response.filter(message -> message.dataset().isPresent()).ifPresent(message -> dataResponses.add(message.record()));
            return response;
        }

        /** The findings of the step on its upload. */
        List<Finding> registration(String testCaseId, Optional<ParsedMessage> upload, IdentifierSet expected,
                                   Set<IdentifierType> setByPlatform) {
            return upload.map(message -> StepChecks.registration(testCaseId, message, engine.format(), context, expected, setByPlatform))
                    .orElseGet(() -> List.of(StepChecks.noUpload(testCaseId, datasetId, engine.history().messages())));
        }

        /** Says once per upload why the latest one does not count for the step, as the runner otherwise just seems to wait. */
        void explainLatestUpload(String testCaseId, List<ParsedMessage> uploads, IdentifierSet expected, Set<IdentifierType> setByPlatform) {
            StepChecks.latestUpload(uploads, previousUpload).filter(latest -> !explainedUpload.equals(Optional.of(latest))).ifPresent(latest -> {
                explainedUpload = Optional.of(latest);
                operator.progress("[" + plan.key() + "] This identifier upload does not count for " + testCaseId + ":\n    "
                        + StepChecks.registration(testCaseId, latest, engine.format(), context, expected, setByPlatform).stream()
                        .map(Finding::detail).collect(Collectors.joining("\n    "))
                        + "\nThe runner keeps waiting for one that does. If none will come, press Enter to have this one evaluated.");
            });
        }

        /**
         * Asks for the step and waits for its identifier upload (see {@link StepChecks#stepUpload}): until the operator
         * confirms the step (at most the operator limit), as they may still be at it, then as long as for a response.
         * If it does not come, the latest upload after the previous step's upload is evaluated, so that the findings name
         * the wrong values. The uploads that came before the evaluated one are named in a note.
         *
         * @param expected the identifier values the upload must carry (see {@link StepChecks#registration})
         * @throws IllegalStateException if no upload came and the operator did not confirm the step within the limit
         */
        StepUpload performAndAwaitUpload(String testCaseId, String instruction, IdentifierSet expected, Set<IdentifierType> setByPlatform) {
            int prompt = engine.mark();
            operator.instruct(instruction);
            Supplier<List<ParsedMessage>> uploads = () -> StepChecks.uploadsFor(datasetId, engine.history().messages());
            Supplier<Optional<ParsedMessage>> stepUpload = () -> {
                Optional<ParsedMessage> found = StepChecks.stepUpload(uploads.get(), previousUpload, engine.format(), context, expected, setByPlatform);
                if (found.isEmpty()) {
                    explainLatestUpload(testCaseId, uploads.get(), expected, setByPlatform);
                }
                return found;
            };
            boolean doneOrConfirmed = engine.await(() -> stepUpload.get().isPresent() || operator.confirmed() ? Optional.of(true) : Optional.empty(),
                    operatorLimit).isPresent();
            if (!doneOrConfirmed && StepChecks.latestUpload(uploads.get(), previousUpload).isEmpty()) {
                throw new IllegalStateException("No identifier upload came for this step and the operator did not confirm the step within "
                        + operatorLimit.toMinutes() + " min, so the run was stopped.");
            }
            if (stepUpload.get().isEmpty()) {
                operator.progress("Waiting up to " + engine.responseWait().toSeconds() + " s for the identifier upload of the platform ...");
            }
            Optional<ParsedMessage> upload = engine.await(stepUpload, engine.responseWait())
                    .or(() -> StepChecks.latestUpload(uploads.get(), previousUpload));
            List<ParsedMessage> skipped = StepChecks.skippedUploads(uploads.get(), previousUpload, upload);
            upload.ifPresent(message -> previousUpload = Optional.of(message));
            boolean early = upload.filter(message -> engine.history().messages().indexOf(message) < prompt).isPresent();
            List<String> notes = new ArrayList<>(early ? List.of("the identifier upload arrived before the runner asked for this step") : List.of());
            StepChecks.skippedUploadsNote(skipped).ifPresent(notes::add);
            return new StepUpload(upload, registration(testCaseId, upload, expected, setByPlatform), notes);
        }

        boolean createDraft() {
            datasetId = operator.createDataset(instruction(1, "create the data set with its values, exactly as given "
                    + "(identifier of the transport means " + plan.config().transportMeansTypo() + "), without any signature. "
                    + "Then enter the data set ID your platform shows."), operatorLimit);
            engine.bind(datasetId, plan.key());
            ParsedMessage request = dataRequest(TestCaseIds.RUN_01, "data-request", context.gateId(), context.platformId(),
                    datasetId, plan.subsets());
            lodgedRequestId = request.requestId().orElseThrow();
            // Not via awaitResponse: data wrongly returned for the draft is not the content the round trip expects.
            engine.awaitResponse(request);
            // Evaluated right before the carrier signature is asked for, so that every response to the request and every
            // upload of the draft counts.
            engine.poll();
            List<Finding> failures = new ArrayList<>(StepChecks.verify(TestCaseIds.RUN_01, "Data request for the draft",
                    Expectation.ERROR, request, engine.history().allResponsesTo(request), Optional.empty()));
            failures.addAll(StepChecks.draftRegistrations(TestCaseIds.RUN_01,
                    StepChecks.uploadsFor(datasetId, engine.history().messages())));
            return record(TestCaseIds.RUN_01, failures, List.of());
        }

        boolean activate() {
            StepUpload step = performAndAwaitUpload(TestCaseIds.RUN_02,
                    instruction(2, "acting as the carrier, apply the carrier signature to the data set."),
                    plan.identifiersAsCreated(), Set.of());
            // Requests use the data set ID as the platform spells it in its upload.
            step.upload().flatMap(ParsedMessage::datasetId).ifPresent(id -> datasetId = id);
            List<String> notes = new ArrayList<>(step.notes());
            step.upload().flatMap(upload -> StepChecks.uncheckedUil(upload, engine.format())).ifPresent(notes::add);
            boolean passed = record(TestCaseIds.RUN_02, step.failures(), notes);
            if (passed) {
                activeDatasetId = datasetId;
            }
            return passed;
        }

        boolean correctPlate() {
            StepUpload step = performAndAwaitUpload(TestCaseIds.RUN_03, instruction(3, "correct the "
                    + "identifier of the transport means from " + plan.config().transportMeansTypo() + " to "
                    + plan.config().transportMeansCorrected() + " and change nothing else."), plan.identifiersCorrected(), Set.of());
            return record(TestCaseIds.RUN_03, step.failures(), step.notes());
        }

        boolean requestData() {
            ParsedMessage all = dataRequest(TestCaseIds.RUN_04, "data-request", context.gateId(), context.platformId(), datasetId, plan.subsets());
            lodgedRequestId = all.requestId().orElseThrow();
            dataRequestId = lodgedRequestId;
            String allLabel = "(a) subsets " + plan.subsets();
            Optional<ParsedMessage> allResponse = awaitResponse(all);
            List<Finding> failures = new ArrayList<>(StepChecks.dataReturned(TestCaseIds.RUN_04, allLabel, all, allResponse,
                    engine.format(), Optional.of(plan.identifiersCorrected())));
            List<String> notes = new ArrayList<>();
            if (failures.isEmpty()) {
                StepChecks.correctionNotReturned(allLabel, allResponse, engine.format(), plan.config().transportMeansCorrected())
                        .ifPresent(notes::add);
            }
            if (plan.subsets().size() == 1) {
                failures.addAll(StepChecks.outsideSubsets(TestCaseIds.RUN_04, allLabel, all, allResponse, engine.format()));
                notes.add("(b) not applicable: the data set has a single subset");
                return record(TestCaseIds.RUN_04, failures, notes);
            }
            String singleSubset = plan.subsets().getFirst();
            String label = "(b) subset " + singleSubset;
            ParsedMessage single = dataRequest(TestCaseIds.RUN_04, "data-request", context.gateId(), context.platformId(), datasetId,
                    List.of(singleSubset));
            Optional<ParsedMessage> singleResponse = awaitResponse(single);
            failures.addAll(StepChecks.dataReturned(TestCaseIds.RUN_04, label, single, singleResponse, engine.format(), Optional.empty()));
            failures.addAll(StepChecks.outsideSubsets(TestCaseIds.RUN_04, label, single, singleResponse, engine.format()));
            return record(TestCaseIds.RUN_04, failures, notes);
        }

        boolean followUpOnRequest() {
            ParsedMessage request = followUp(TestCaseIds.RUN_05, "follow-up", context.gateId(), context.platformId(), datasetId, lodgedRequestId);
            String label = "(a) follow-up with a text of " + followUpText.length() + " characters";
            // Paired by its AS4 RefToMessageId, which is what distinguishes acknowledgements of follow-ups referring to
            // the same data request.
            Optional<ParsedMessage> ack = engine.awaitResponse(request);
            List<Finding> failures = new ArrayList<>(StepChecks.verify(TestCaseIds.RUN_05, label, Expectation.SUCCESS, request,
                    ack.stream().toList(), Optional.empty()));
            failures.addAll(StepChecks.echoesDataRequest(TestCaseIds.RUN_05, label, lodgedRequestId, ack));
            return record(TestCaseIds.RUN_05, failures, List.of("(a) sent without attachment and (b) not applicable: message "
                    + "format " + engine.format().version() + " provides no attachments"));
        }

        boolean deliver() {
            StepUpload step = performAndAwaitUpload(TestCaseIds.RUN_06,
                    instruction(4, "acting as the consignee, apply the consignee signature to the data set."),
                    plan.identifiersCorrected(), StepChecks.SET_AT_CONSIGNEE_SIGNATURE);
            List<Finding> failures = new ArrayList<>(step.failures());
            List<String> notes = new ArrayList<>(step.notes());
            if (!plan.road()) {
                notes.add("retrievability after delivery is only checked for road data sets");
                return record(TestCaseIds.RUN_06, failures, notes);
            }
            ParsedMessage request = dataRequest(TestCaseIds.RUN_06, "data-request", context.gateId(), context.platformId(),
                    datasetId, plan.subsets());
            failures.addAll(StepChecks.dataReturned(TestCaseIds.RUN_06, "Data request after delivery", request,
                    awaitResponse(request), engine.format(), Optional.empty()));
            return record(TestCaseIds.RUN_06, failures, notes);
        }

        DatasetRun toRun() {
            return new DatasetRun(plan.key(), steps, plan.expectedDataset(), dataResponses,
                    Optional.ofNullable(activeDatasetId), Optional.ofNullable(lodgedRequestId), Optional.ofNullable(dataRequestId));
        }
    }

    private Probe malformed(String testCaseId, String label, ParsedMessage request) {
        return new Probe(testCaseId, label, NegativeCases.forMalformed(request, engine.format()), Optional.empty(), request);
    }

    private List<Probe> sendProbes(DatasetPlan plan, String datasetId, String requestId) {
        String gate = context.gateId();
        String platform = context.platformId();
        List<String> subsets = plan.subsets();
        String unknownDatasetId = UUID.randomUUID().toString();
        Optional<String> none = Optional.empty();
        return List.of(
                malformed(TestCaseIds.NEG_02, "schema-invalid data request",
                        dataRequest(TestCaseIds.NEG_02, "data-request-invalid", gate, platform, datasetId, subsets)),
                malformed(TestCaseIds.NEG_02, "data request without request ID",
                        dataRequest(TestCaseIds.NEG_02, "data-request-missing-request-id", gate, platform, datasetId, subsets)),
                new Probe(TestCaseIds.NEG_03, "data request for an unknown data set", Expectation.ERROR_WITH_DESCRIPTION, none,
                        dataRequest(TestCaseIds.NEG_03, "data-request", gate, platform, unknownDatasetId, subsets)),
                new Probe(TestCaseIds.NEG_04, "foreign platform ID", Expectation.ANY, Optional.of(FOREIGN_PLATFORM_ID),
                        dataRequest(TestCaseIds.NEG_04, "data-request", gate, FOREIGN_PLATFORM_ID, datasetId, subsets)),
                new Probe(TestCaseIds.NEG_04, "foreign gate ID", Expectation.ANY, Optional.of(FOREIGN_GATE_ID),
                        dataRequest(TestCaseIds.NEG_04, "data-request", FOREIGN_GATE_ID, platform, datasetId, subsets)),
                new Probe(TestCaseIds.NEG_05, "data request with an unknown subset", Expectation.ERROR_RECORDED, none,
                        dataRequest(TestCaseIds.NEG_05, "data-request-unknown-subset", gate, platform, datasetId,
                                List.of(plan.subsets().getFirst()))),
                malformed(TestCaseIds.NEG_06, "schema-invalid follow-up",
                        followUp(TestCaseIds.NEG_06, "follow-up-invalid", gate, platform, datasetId, requestId)),
                malformed(TestCaseIds.NEG_06, "follow-up without request ID",
                        followUp(TestCaseIds.NEG_06, "follow-up-missing-request-id", gate, platform, datasetId, requestId)),
                new Probe(TestCaseIds.NEG_07, "follow-up for an unknown data set", Expectation.ERROR, none,
                        followUp(TestCaseIds.NEG_07, "follow-up", gate, platform, unknownDatasetId, requestId)),
                new Probe(TestCaseIds.NEG_08, "foreign platform ID", Expectation.ANY, Optional.of(FOREIGN_PLATFORM_ID),
                        followUp(TestCaseIds.NEG_08, "follow-up", gate, FOREIGN_PLATFORM_ID, datasetId, requestId)),
                new Probe(TestCaseIds.NEG_08, "foreign gate ID", Expectation.ANY, Optional.of(FOREIGN_GATE_ID),
                        followUp(TestCaseIds.NEG_08, "follow-up", FOREIGN_GATE_ID, platform, datasetId, requestId)),
                new Probe(TestCaseIds.NEG_09, "follow-up for an unknown request ID", Expectation.ERROR, none,
                        followUp(TestCaseIds.NEG_09, "follow-up", gate, platform, datasetId, newRequestId())));
    }

    private Optional<Boolean> allAnswered(List<Probe> probes) {
        return probes.stream().allMatch(probe -> engine.history().responseTo(probe.request()).isPresent())
                ? Optional.of(true) : Optional.empty();
    }

    /** Waits for the responses to the probes at once, so that they cost a single wait, and evaluates them per test case. */
    private void evaluate(List<Probe> sent) {
        probes.addAll(sent);
        engine.await(() -> allAnswered(sent), engine.responseWait());
        sent.stream().collect(Collectors.groupingBy(Probe::testCaseId, LinkedHashMap::new, Collectors.toList()))
                .forEach((id, group) -> {
                    TestCaseResult result = NegativeCases.evaluate(id, group, engine.history());
                    operator.progress(id + " " + result.outcome() + (result.note() == null ? "" : " (" + result.note() + ")"));
                    negativeResults.add(result);
                });
    }

    /** The probes need an active data set (and the request ID of a data request for it) to fail for the injected fault only. */
    private void negativeCases(DatasetPlan plan, DatasetRun primary) {
        operator.progress("Negative cases against data set " + primary.key());
        List<Probe> sent = sendProbes(plan, primary.activeDatasetId().orElseThrow(), primary.lodgedRequestId().orElseThrow());
        // Probes the platform cannot correlate may stay unanswered, and a late response to them (also a success) counts,
        // so unless all probes are answered they are waited for as long as any request.
        if (sent.stream().anyMatch(Probe::uncorrelatable)) {
            operator.progress("Waiting up to " + engine.responseWait().toSeconds()
                    + " s for responses to the probes without request ID ...");
        }
        evaluate(sent);
    }

    /**
     * T-NEG-10, for the first pair of an activated data set and the data request of T-RUN-04 of another one. A request
     * for a draft does not qualify: it was refused, so an error would not show that the platform checks the pair. An
     * earlier run with a data request is paired with the current data set, so the data set of the pair is not delivered.
     */
    private void followUpMismatch(DatasetRun current) {
        List<DatasetRun> runs = Stream.concat(finishedRuns.stream(), Stream.of(current)).toList();
        runs.stream()
                .flatMap(requested -> requested.dataRequestId().stream().flatMap(requestId -> runs.stream()
                        .filter(other -> other != requested)
                        .flatMap(other -> other.activeDatasetId().stream())
                        .map(datasetId -> Map.entry(requestId, datasetId))))
                .findFirst()
                .ifPresent(pair -> {
                    followUpMismatchRun = true;
                    operator.progress("Negative case on a follow-up mixing two data sets");
                    evaluate(List.of(new Probe(TestCaseIds.NEG_10, "follow-up with the request ID of another data set",
                            Expectation.ERROR, Optional.empty(), followUp(TestCaseIds.NEG_10, "follow-up", context.gateId(),
                            context.platformId(), pair.getValue(), pair.getKey()))));
                });
    }

    private void negativeCasesBeforeDelivery(DatasetPlan plan, DatasetRun run) {
        if (!negativeCasesRun && run.activeDatasetId().isPresent()) {
            negativeCasesRun = true;
            negativeCases(plan, run);
        }
        if (!followUpMismatchRun) {
            followUpMismatch(run);
        }
    }

    private DatasetRun lifecycle(DatasetPlan plan) {
        engine.attributeTo(plan.key());
        operator.progress("[" + plan.key() + "] " + plan.config().title() + ": for its values see " + OPERATOR_GUIDE);
        Lifecycle lifecycle = new Lifecycle(plan);
        // Each step needs the state the previous ones produced, so a failed step ends the data set's run.
        boolean readyForDelivery = lifecycle.createDraft() && lifecycle.activate() && lifecycle.correctPlate()
                && lifecycle.requestData() && lifecycle.followUpOnRequest();
        // After delivery a platform may refuse requests for the data set or not answer them at all (non-road data sets
        // are no longer available online), so the probes would no longer fail for their injected fault only.
        negativeCasesBeforeDelivery(plan, lifecycle.toRun());
        boolean completed = readyForDelivery && lifecycle.deliver();
        operator.progress("[" + plan.key() + "] lifecycle " + (completed ? "completed" : "aborted"));
        return lifecycle.toRun();
    }

    private void reportUnexecutedNegativeCases() {
        negativeResults.add(new TestCaseResult(TestCaseIds.NEG_01, null, TestCaseResult.Outcome.NOT_APPLICABLE,
                "the TLS configuration of the platform's access point is not tested by the test runner", List.of()));
        if (!followUpMismatchRun) {
            negativeResults.add(new TestCaseResult(TestCaseIds.NEG_10, null, TestCaseResult.Outcome.NOT_APPLICABLE,
                    "needs two data sets activated during the run, one of them with a data request", List.of()));
        }
        if (!negativeCasesRun) {
            TestCaseIds.NEGATIVE_EXECUTED.forEach(id -> negativeResults.add(new TestCaseResult(id, null,
                    TestCaseResult.Outcome.NOT_APPLICABLE, "not executed: no data set was activated", List.of())));
        }
    }

    /**
     * Runs the scenario for the data sets in order. A failed step only ends the run of its data set. Before the run
     * ends, the platform's messages are polled for the closing wait, so that those arriving right after the last step
     * are checked as well (e.g. a second response to its request, T-ALL-03).
     */
    RunEvidence run(List<DatasetPlan> plans) {
        plans.forEach(plan -> finishedRuns.add(lifecycle(plan)));
        reportUnexecutedNegativeCases();
        engine.await(Optional::empty, closingWait);
        operator.progress("Querying the AS4 receipt status of the messages sent ...");
        Map<String, String> receiptStatuses = engine.receiptStatuses(receiptGrace);
        return evidence(receiptStatuses, ConformanceChecks.checkOverdue(engine.history(), Instant.now(), context));
    }
}
