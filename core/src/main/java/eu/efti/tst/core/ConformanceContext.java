package eu.efti.tst.core;

import java.time.Duration;

/**
 * What the gate side knows in advance about the exchange with one platform.
 *
 * @param gateId          identifier of the gate
 * @param platformId      registered identifier of the platform
 * @param responseTimeout time within which every request must be answered, measured from the time the gate side sent
 *                        the request (see {@link ConformanceChecks})
 * @param mode            whether the messages stem from a test run or from production traffic
 */
public record ConformanceContext(String gateId, String platformId, Duration responseTimeout, Mode mode) {

    /** The response time limit of Art. 3(3)(d) of Impl. Reg. (EU) 2024/1942, which T-ALL-04 has to apply. */
    public static final Duration LEGAL_RESPONSE_TIMEOUT = Duration.ofSeconds(60);

    /** Where the checked messages come from; a gate checks less on production traffic than a test run does. */
    public enum Mode {
        /** A test run with prescribed test data: all checks apply. */
        TEST_RUN,
        /**
         * Continuous monitoring of production traffic: only the format of each message (T-DAT-01, T-DAT-02) and the
         * exchange rules (T-ALL-03, T-ALL-04) are checked. The composition and consistency of the data (T-DAT-03 to
         * T-DAT-05) depend on the consignment, whose content the gate does not judge.
         */
        MONITORING
    }

    /**
     * A context for a test run.
     *
     * @deprecated state the mode explicitly with {@link #ConformanceContext(String, String, Duration, Mode)}
     */
    @Deprecated
    public ConformanceContext(String gateId, String platformId, Duration responseTimeout) {
        this(gateId, platformId, responseTimeout, Mode.TEST_RUN);
    }
}
