package eu.efti.tst.runner;

import eu.efti.tst.core.Finding;
import eu.efti.tst.core.MessageHistory;
import eu.efti.tst.core.MessageRecord;
import eu.efti.tst.core.ParsedMessage;
import eu.efti.tst.core.TestCaseResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a run observed, from which {@link Results} derives the test case results.
 *
 * @param runs            lifecycle runs that ended, in execution order
 * @param negativeResults results of the negative cases
 * @param probes          the fault-injected requests of the negative cases
 * @param received        the messages received from the platform with the findings of the conformance checks
 * @param overdue         findings of {@link eu.efti.tst.core.ConformanceChecks#checkOverdue} at the end of the run
 * @param receiptStatuses AS4 status of every message sent to the platform at the end of the run, by AS4 message ID
 * @param history         all messages exchanged
 * @param responseTimesNotEvaluated number of responses whose response time an outage of the transport distorted
 */
record RunEvidence(List<DatasetRun> runs, List<TestCaseResult> negativeResults, List<NegativeCases.Probe> probes,
                   List<ReceivedMessage> received, List<Finding> overdue, Map<String, String> receiptStatuses,
                   MessageHistory history, int responseTimesNotEvaluated) {

    /**
     * @param steps           results of the executed lifecycle steps; steps after a failed one are missing
     * @param expectedDataset data set the returned data sets are compared with (T-DAT-06)
     * @param dataResponses   data responses of the scenario that carry a data set
     * @param activeDatasetId data set ID as uploaded by the platform on activation, empty unless the activation
     *                        (T-RUN-02) passed
     * @param lodgedRequestId request ID of the latest data request the scenario sent for the data set
     * @param dataRequestId   request ID of the data request of T-RUN-04, empty unless it was sent
     */
    record DatasetRun(String key, List<TestCaseResult> steps, String expectedDataset,
                      List<MessageRecord> dataResponses, Optional<String> activeDatasetId, Optional<String> lodgedRequestId,
                      Optional<String> dataRequestId) {

        /** Why the lifecycle ended before its last step: the first failed step, if any. */
        String abortReason() {
            return steps.stream()
                    .filter(step -> step.outcome() == TestCaseResult.Outcome.FAILED)
                    .map(step -> step.testCaseId() + " failed")
                    .findFirst()
                    .orElse("the run was aborted");
        }
    }

    /**
     * A message received from the platform with the findings of the conformance checks on it.
     *
     * @param datasetKey the data set the message belongs to, else the one whose run it occurred in
     */
    record ReceivedMessage(String datasetKey, ParsedMessage message, List<Finding> findings) {
    }
}
