package eu.efti.tst.core;

import java.util.List;

/**
 * Result of one test case, for one data set or for the whole run.
 *
 * @param testCaseId ID of the test case in the {@link TestCatalogue}
 * @param datasetKey data set the result belongs to (e.g. {@code DS1}), null for run-wide test cases
 * @param outcome    the result
 * @param note       short remark shown with the result (e.g. the observed behaviour), null if none
 * @param findings   the violations that made the test case fail
 */
public record TestCaseResult(String testCaseId, String datasetKey, Outcome outcome, String note, List<Finding> findings) {

    public enum Outcome { PASSED, FAILED, NOT_APPLICABLE }

    public TestCaseResult {
        findings = List.copyOf(findings);
    }
}
