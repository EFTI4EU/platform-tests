package eu.efti.tst.core;

import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Everything a test run produced: its metadata, the test case results and the transcript of all exchanged
 * messages. Serializable to JSON; {@link HtmlReport} renders it for humans.
 *
 * @param meta       metadata of the run
 * @param results    test case results; a test case without result is reported as not applicable
 * @param transcript all messages exchanged during the run, in order
 * @param incomplete why the run stopped before all requested data sets were through (e.g. interrupted), null if it
 *                   completed
 */
public record RunReport(Meta meta, List<TestCaseResult> results, List<MessageRecord> transcript, String incomplete) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * @param platformUnderTest product name and version of the platform
     * @param company           company operating the platform
     * @param platformId        platform identifier in the test environment
     * @param gateId            gate identifier the test runner used
     * @param suiteVersion      name and version of the test suite
     * @param formatVersion     message format version used
     * @param profile           profiles tested, as far as the requested data sets cover any
     * @param datasets          data sets reported per data set (requested or not), in report order
     * @param responseTimeLimit response time limit for data responses and follow-up acknowledgements (T-ALL-04)
     * @param start             start of the run
     * @param end               end of the run
     */
    public record Meta(String platformUnderTest, String company, String platformId, String gateId, String suiteVersion,
                       String formatVersion, String profile, List<Dataset> datasets, Duration responseTimeLimit,
                       Instant start, Instant end) {

        public Meta {
            datasets = List.copyOf(datasets);
        }
    }

    /**
     * @param key      data set key, e.g. DS1
     * @param title    what the data set covers, e.g. "Road (basic road, P01)"
     * @param testData name of its test data, null if there are none yet
     * @param status   what became of it in this run
     */
    public record Dataset(String key, String title, String testData, Status status) {

        /** NOT_COMPLETED: requested, but the run stopped before its run ended. */
        public enum Status { USED, NOT_COMPLETED, NOT_REQUESTED }

        boolean requested() {
            return status != Status.NOT_REQUESTED;
        }
    }

    public RunReport {
        results = List.copyOf(results);
        transcript = List.copyOf(transcript);
    }

    /** Whether the run completed and no test case failed. */
    public boolean passed() {
        return incomplete == null && results.stream().noneMatch(result -> result.outcome() == TestCaseResult.Outcome.FAILED);
    }

    public String toJson() {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(this);
    }

    public static RunReport fromJson(String json) {
        return JSON.readValue(json, RunReport.class);
    }
}
