package eu.efti.tst.core;

import java.util.List;
import java.util.Set;

/**
 * IDs of the test cases whose evaluation the runner implements. Which test cases exist and their titles come from the
 * {@link TestCatalogue}; a test case without an evaluation here is reported as not applicable.
 */
public final class TestCaseIds {

    public static final String RUN_01 = "T-RUN-01";
    public static final String RUN_02 = "T-RUN-02";
    public static final String RUN_03 = "T-RUN-03";
    public static final String RUN_04 = "T-RUN-04";
    public static final String RUN_05 = "T-RUN-05";
    public static final String RUN_06 = "T-RUN-06";
    public static final List<String> LIFECYCLE = List.of(RUN_01, RUN_02, RUN_03, RUN_04, RUN_05, RUN_06);

    public static final String DAT_01 = "T-DAT-01";
    public static final String DAT_02 = "T-DAT-02";
    public static final String DAT_03 = "T-DAT-03";
    public static final String DAT_04 = "T-DAT-04";
    public static final String DAT_05 = "T-DAT-05";
    public static final String DAT_06 = "T-DAT-06";

    public static final String ALL_01 = "T-ALL-01";
    public static final String ALL_02 = "T-ALL-02";
    public static final String ALL_03 = "T-ALL-03";
    public static final String ALL_04 = "T-ALL-04";

    /** Test cases evaluated from the findings of {@link ConformanceChecks#checkAndAppend} alone. */
    public static final Set<String> MONITORED = Set.of(DAT_01, DAT_02, DAT_03, DAT_04, DAT_05, ALL_03);

    public static final String NEG_01 = "T-NEG-01";
    public static final String NEG_02 = "T-NEG-02";
    public static final String NEG_03 = "T-NEG-03";
    public static final String NEG_04 = "T-NEG-04";
    public static final String NEG_05 = "T-NEG-05";
    public static final String NEG_06 = "T-NEG-06";
    public static final String NEG_07 = "T-NEG-07";
    public static final String NEG_08 = "T-NEG-08";
    public static final String NEG_09 = "T-NEG-09";
    public static final String NEG_10 = "T-NEG-10";
    /** Negative cases that are executed against the primary data set. */
    public static final List<String> NEGATIVE_EXECUTED = List.of(NEG_02, NEG_03, NEG_04, NEG_05, NEG_06, NEG_07, NEG_08, NEG_09);

    private TestCaseIds() {
    }
}
