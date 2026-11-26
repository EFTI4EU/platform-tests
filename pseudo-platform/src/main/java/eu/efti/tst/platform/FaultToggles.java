package eu.efti.tst.platform;

/**
 * Deliberate misbehaviour, so that a demo run can show failing test cases.
 *
 * @param ignoreDataRequests  never answer data requests
 * @param ignoreSubsetFilter  return the full data set regardless of the requested subsets
 * @param skipUploadOnUpdate  send no identifier upload when an active data set is updated: the runner waits for it
 *                            (72 s after the operator confirms the step), then fails T-RUN-03
 */
public record FaultToggles(boolean ignoreDataRequests, boolean ignoreSubsetFilter, boolean skipUploadOnUpdate) {

    public static final FaultToggles NONE = new FaultToggles(false, false, false);
}
