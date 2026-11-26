package eu.efti.tst.runner;

import eu.efti.tst.core.ConformanceChecks;
import eu.efti.tst.core.Finding;
import eu.efti.tst.core.Format;
import eu.efti.tst.core.MessageHistory;
import eu.efti.tst.core.MessageKind;
import eu.efti.tst.core.MessageRecord;
import eu.efti.tst.core.ParsedMessage;
import eu.efti.tst.core.TestCaseIds;
import eu.efti.tst.core.TestCaseResult;
import eu.efti.tst.core.TestCatalogue;
import eu.efti.tst.runner.RunEvidence.DatasetRun;
import eu.efti.tst.runner.RunEvidence.ReceivedMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Derives one result per test case of the {@link TestCatalogue} (per requested data set where the section says
 * so) from the evidence of a run. Data sets that were not requested get no result.
 */
final class Results {

    /** AS4 status of a message whose receipt the gate's access point received. */
    static final String ACKNOWLEDGED = "ACKNOWLEDGED";
    /** AS4 status of a message whose receipt the gate's access point received with a warning (e.g. an AS4 error of severity warning). */
    static final String ACKNOWLEDGED_WITH_WARNING = "ACKNOWLEDGED_WITH_WARNING";

    private static final String STOPPED = "not executed: the run was stopped";

    private Results() {
    }

    private static TestCaseResult notApplicable(String testCaseId, String datasetKey, String note) {
        return new TestCaseResult(testCaseId, datasetKey, TestCaseResult.Outcome.NOT_APPLICABLE, note, List.of());
    }

    private static List<Finding> findingsOf(String testCaseId, Stream<ReceivedMessage> received) {
        return received.flatMap(message -> message.findings().stream())
                .filter(finding -> finding.testCaseId().equals(testCaseId))
                .toList();
    }

    private static boolean isAcknowledged(String status) {
        return ACKNOWLEDGED.equals(status) || ACKNOWLEDGED_WITH_WARNING.equals(status);
    }

    private static boolean isRequestToPlatform(ParsedMessage message) {
        return message.record().direction() == MessageRecord.Direction.TO_PLATFORM
                && Arrays.stream(MessageKind.values()).anyMatch(kind -> kind.answers().equals(Optional.of(message.kind())));
    }

    private static TestCaseResult notExecuted(String testCaseId, DatasetRun run) {
        return new TestCaseResult(testCaseId, run.key(), TestCaseResult.Outcome.FAILED, "not executed: " + run.abortReason(),
                List.of());
    }

    /** The distinct paths of all comparisons after the label, nothing if there are none. */
    private static Stream<String> listed(String label, List<ConformanceChecks.RoundTripResult> comparisons,
                                         Function<ConformanceChecks.RoundTripResult, List<String>> paths) {
        List<String> distinct = comparisons.stream().flatMap(comparison -> paths.apply(comparison).stream())
                .distinct().sorted().toList();
        return distinct.isEmpty() ? Stream.empty() : Stream.of(label + String.join(", ", distinct));
    }

    private static TestCaseResult roundTrip(DatasetRun run, MessageHistory history) {
        if (run.dataResponses().isEmpty()) {
            return notApplicable(TestCaseIds.DAT_06, run.key(), "not evaluated: no data request of T-RUN-04 was answered with data");
        }
        List<ConformanceChecks.RoundTripResult> comparisons = run.dataResponses().stream()
                .map(response -> ConformanceChecks.checkRoundTrip(response, history, run.expectedDataset()))
                .toList();
        String note = Stream.concat(
                listed("optional elements of the test data set not returned: ", comparisons,
                        ConformanceChecks.RoundTripResult::omittedOptional),
                listed("attributes not in the test data set: ", comparisons,
                        ConformanceChecks.RoundTripResult::addedAttributes)).collect(Collectors.joining("; "));
        return StepChecks.result(TestCaseIds.DAT_06, run.key(),
                comparisons.stream().flatMap(comparison -> comparison.findings().stream()).toList(),
                note.isEmpty() ? null : note);
    }

    private static boolean returnsDataset(ParsedMessage message) {
        return message.kind() == MessageKind.DATA_RESPONSE && message.dataset().isPresent();
    }

    /** Why a data conformance check has no evidence among the messages received for a data set, empty if it has. */
    private static Optional<String> missingEvidence(String testCaseId, List<ParsedMessage> received, MessageHistory history) {
        String missing = switch (testCaseId) {
            case TestCaseIds.DAT_01, TestCaseIds.DAT_02 -> received.isEmpty() ? "message" : null;
            case TestCaseIds.DAT_03 -> received.stream().noneMatch(message -> message.kind() == MessageKind.IDENTIFIER_UPLOAD)
                    ? "identifier upload" : null;
            case TestCaseIds.DAT_04 -> received.stream().noneMatch(Results::returnsDataset) ? "data set" : null;
            case TestCaseIds.DAT_05 -> received.stream().filter(Results::returnsDataset)
                    .noneMatch(message -> history.requestAnsweredBy(message).flatMap(ParsedMessage::datasetId)
                            .flatMap(history::latestAcceptedUpload).isPresent())
                    ? "data set after an accepted identifier upload" : null;
            default -> null;
        };
        return Optional.ofNullable(missing).map(kind -> "no " + kind + " received in this run");
    }

    private static TestCaseResult monitored(String testCaseId, DatasetRun run, List<ReceivedMessage> received,
                                            MessageHistory history) {
        List<ReceivedMessage> ofDataset = received.stream().filter(message -> run.key().equals(message.datasetKey())).toList();
        List<Finding> findings = findingsOf(testCaseId, ofDataset.stream());
        Optional<String> missingEvidence = missingEvidence(testCaseId,
                ofDataset.stream().map(ReceivedMessage::message).toList(), history);
        return findings.isEmpty() && missingEvidence.isPresent()
                ? notApplicable(testCaseId, run.key(), missingEvidence.get())
                : StepChecks.result(testCaseId, run.key(), findings, null);
    }

    private static TestCaseResult datasetResult(String testCaseId, DatasetRun run, List<ReceivedMessage> received,
                                                MessageHistory history) {
        Optional<TestCaseResult> step = run.steps().stream().filter(result -> result.testCaseId().equals(testCaseId)).findFirst();
        if (step.isPresent()) {
            return step.get();
        }
        if (TestCaseIds.LIFECYCLE.contains(testCaseId)) {
            return notExecuted(testCaseId, run);
        }
        if (TestCaseIds.DAT_06.equals(testCaseId)) {
            return roundTrip(run, history);
        }
        if (TestCaseIds.MONITORED.contains(testCaseId)) {
            return monitored(testCaseId, run, received, history);
        }
        return notApplicable(testCaseId, run.key(), "not evaluated by this test runner");
    }

    private static List<ParsedMessage> sentToPlatform(MessageHistory history) {
        return history.messages().stream()
                .filter(message -> message.record().direction() == MessageRecord.Direction.TO_PLATFORM)
                .toList();
    }

    private static TestCaseResult connection(MessageHistory history, Map<String, String> receiptStatuses) {
        List<Finding> failures = new ArrayList<>();
        List<ParsedMessage> sent = sentToPlatform(history);
        if (sent.isEmpty()) {
            failures.add(new Finding(TestCaseIds.ALL_01, null, "No message could be sent to the platform."));
        }
        long acknowledged = sent.stream().filter(message -> isAcknowledged(receiptStatuses.get(message.record().as4MessageId()))).count();
        if (!sent.isEmpty() && acknowledged == 0) {
            failures.add(new Finding(TestCaseIds.ALL_01, null, "No message sent to the platform was acknowledged with an AS4 receipt."));
        }
        long received = history.messages().stream().filter(message -> message.record().direction() == MessageRecord.Direction.FROM_PLATFORM).count();
        if (received == 0) {
            failures.add(new Finding(TestCaseIds.ALL_01, null, "No message was received from the platform."));
        }
        return StepChecks.result(TestCaseIds.ALL_01, null, failures, "evidence: " + acknowledged + " of " + sent.size()
                + " message(s) to the platform acknowledged with an AS4 receipt, " + received + " message(s) received from "
                + "the platform; the access points of this test setup are connected over plain HTTP, so TLS is not used");
    }

    /** T-ALL-02: every message sent to the platform was acknowledged with an AS4 receipt. */
    private static TestCaseResult acknowledged(MessageHistory history, Map<String, String> receiptStatuses) {
        List<ParsedMessage> sent = sentToPlatform(history);
        List<String> statuses = sent.stream().map(message -> receiptStatuses.get(message.record().as4MessageId())).toList();
        long withWarning = statuses.stream().filter(ACKNOWLEDGED_WITH_WARNING::equals).count();
        return StepChecks.result(TestCaseIds.ALL_02, null, sent.stream()
                .filter(message -> !isAcknowledged(receiptStatuses.get(message.record().as4MessageId())))
                .map(message -> StepChecks.finding(TestCaseIds.ALL_02, message.record(), "The " + message.kind()
                        + message.requestId().map(id -> " with request ID " + id).orElse(" without request ID")
                        + " was not acknowledged by the platform's access point (AS4 status "
                        + receiptStatuses.get(message.record().as4MessageId()) + ")."))
                .toList(), withWarning == 0 ? null
                : withWarning + " message(s) acknowledged with a warning (AS4 status " + ACKNOWLEDGED_WITH_WARNING + ")");
    }

    /** The longest time between a request to the platform and its response. */
    static Optional<Duration> maxResponseTime(MessageHistory history) {
        return history.messages().stream()
                .filter(Results::isRequestToPlatform)
                .flatMap(request -> history.responseTo(request).stream()
                        .map(response -> Duration.between(request.record().timestamp(), response.record().timestamp())))
                .max(Comparator.naturalOrder());
    }

    private static TestCaseResult responseTime(List<Finding> findings, MessageHistory history, int notEvaluated) {
        List<String> notes = new ArrayList<>();
        maxResponseTime(history).ifPresent(max -> notes.add(String.format(Locale.ROOT, "max %.1f s", max.toMillis() / 1000.0)));
        notes.add("measured from the submission to the gate's access point, so a delayed AS4 receipt (e.g. after retries) "
                + "counts against the platform");
        if (notEvaluated > 0) {
            notes.add("the response time of " + notEvaluated + " response(s) is not evaluated, as the gate's access point "
                    + "could not be reached while they were pending");
        }
        return StepChecks.result(TestCaseIds.ALL_04, null, findings, String.join("; ", notes));
    }

    private static TestCaseResult runWideResult(String testCaseId, List<ReceivedMessage> received, RunEvidence evidence) {
        // overdue requests are findings of the test case they name (unanswered data requests T-ALL-04, follow-ups T-ALL-03)
        List<Finding> findings = Stream.concat(findingsOf(testCaseId, received.stream()).stream(),
                evidence.overdue().stream().filter(finding -> finding.testCaseId().equals(testCaseId))).toList();
        return switch (testCaseId) {
            case TestCaseIds.ALL_01 -> connection(evidence.history(), evidence.receiptStatuses());
            case TestCaseIds.ALL_02 -> acknowledged(evidence.history(), evidence.receiptStatuses());
            case TestCaseIds.ALL_04 -> responseTime(findings, evidence.history(), evidence.responseTimesNotEvaluated());
            default -> evidence.negativeResults().stream()
                    .filter(result -> result.testCaseId().equals(testCaseId))
                    .findFirst()
                    .orElseGet(() -> TestCaseIds.MONITORED.contains(testCaseId)
                            ? StepChecks.result(testCaseId, null, findings, null)
                            : notApplicable(testCaseId, null, "not evaluated by this test runner"));
        };
    }

    private static List<ReceivedMessage> withoutExpectedReactions(RunEvidence evidence) {
        return evidence.received().stream()
                .map(message -> new ReceivedMessage(message.datasetKey(), message.message(), message.findings().stream()
                        .filter(finding -> !NegativeCases.isExpectedReaction(finding, message.message(), evidence.probes(),
                                evidence.history()))
                        .toList()))
                .toList();
    }

    /** Adds what the format cannot evaluate of the test case to the note, as the outcome would otherwise claim it was checked. */
    private static TestCaseResult withLimitation(TestCaseResult result, Format format) {
        return format.limitation(result.testCaseId())
                .filter(limitation -> result.outcome() != TestCaseResult.Outcome.NOT_APPLICABLE)
                .map(limitation -> new TestCaseResult(result.testCaseId(), result.datasetKey(), result.outcome(),
                        result.note() == null ? limitation : result.note() + "; " + limitation, result.findings()))
                .orElse(result);
    }

    static List<TestCaseResult> aggregate(TestCatalogue catalogue, RunEvidence evidence) {
        List<ReceivedMessage> received = withoutExpectedReactions(evidence);
        return catalogue.sections().stream()
                .flatMap(section -> section.testCases().stream().flatMap(testCase -> section.perDataset()
                        ? evidence.runs().stream().map(run -> datasetResult(testCase.id(), run, received, evidence.history()))
                        : Stream.of(runWideResult(testCase.id(), received, evidence))))
                .map(result -> withLimitation(result, evidence.history().format()))
                .toList();
    }

    private static TestCaseResult stoppedRunWideResult(String testCaseId, List<ReceivedMessage> received, RunEvidence evidence) {
        // The AS4 receipts need a grace period at the end of a run, and without messages the other exchange rule tests say nothing.
        if (testCaseId.equals(TestCaseIds.ALL_01) || testCaseId.equals(TestCaseIds.ALL_02)
                || testCaseId.startsWith("T-ALL") && evidence.history().messages().isEmpty()) {
            return notApplicable(testCaseId, null, STOPPED);
        }
        boolean negativeCase = testCaseId.equals(TestCaseIds.NEG_01) || testCaseId.equals(TestCaseIds.NEG_10)
                || TestCaseIds.NEGATIVE_EXECUTED.contains(testCaseId);
        return negativeCase && evidence.negativeResults().stream().noneMatch(result -> result.testCaseId().equals(testCaseId))
                ? notApplicable(testCaseId, null, STOPPED)
                : runWideResult(testCaseId, received, evidence);
    }

    /**
     * The results of a run that stopped before all requested data sets were through: the data sets of the evidence as
     * usual, and of the others only the steps determined so far, since steps not reached did not fail.
     *
     * @param unfinished the keys of the requested data sets whose run did not end
     * @param stepsSoFar the results of all lifecycle steps of the run so far
     */
    static List<TestCaseResult> stopped(TestCatalogue catalogue, RunEvidence evidence, List<String> unfinished,
                                        List<TestCaseResult> stepsSoFar) {
        List<ReceivedMessage> received = withoutExpectedReactions(evidence);
        return catalogue.sections().stream()
                .flatMap(section -> section.testCases().stream().flatMap(testCase -> section.perDataset()
                        ? Stream.concat(evidence.runs().stream().map(run -> datasetResult(testCase.id(), run, received, evidence.history())),
                                unfinished.stream().map(key -> stepsSoFar.stream()
                                        .filter(step -> step.testCaseId().equals(testCase.id()) && key.equals(step.datasetKey()))
                                        .findFirst()
                                        .orElseGet(() -> notApplicable(testCase.id(), key, STOPPED))))
                        : Stream.of(stoppedRunWideResult(testCase.id(), received, evidence))))
                .toList();
    }
}
