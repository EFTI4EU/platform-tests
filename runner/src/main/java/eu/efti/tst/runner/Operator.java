package eu.efti.tst.runner;

import java.time.Duration;

/** The platform operator who drives the data set through its lifecycle following the operator script. */
interface Operator {

    /** Shows the progress of the run. */
    void progress(String message);

    /**
     * Asks the operator to create the data set and returns the data set ID entered (a UUID).
     *
     * @throws IllegalStateException if no operator input is available or no data set ID was entered within the limit
     */
    String createDataset(String instruction, Duration limit);

    /** Asks the operator to perform a step and returns at once; see {@link #confirmed}. */
    void instruct(String instruction);

    /**
     * Whether the operator has confirmed since the last instruction that they performed it; does not wait.
     *
     * @throws IllegalStateException if no operator input is available
     */
    boolean confirmed();
}
