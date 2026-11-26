package eu.efti.tst.runner;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsoleOperatorTest {

    private static final Duration LIMIT = Duration.ofSeconds(5);

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private ConsoleOperator operator(Reader input) {
        return new ConsoleOperator(new BufferedReader(input), new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    private ConsoleOperator operator(String input) {
        return operator(new StringReader(input));
    }

    /** Whether the operator confirms within the time, as the console is read in the background. */
    private static boolean confirmedWithin(Operator operator, Duration time) throws InterruptedException {
        Instant deadline = Instant.now().plus(time);
        while (Instant.now().isBefore(deadline)) {
            if (operator.confirmed()) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    @Test
    void dataSetIdIsAskedForAgainUntilItIsAUuid() {
        String entered = operator("\nDS1-0001\n  0B6F3F3E-3c1a-4f6e-9d4b-2a7c5e8f1a90 \n").createDataset("create it", LIMIT);

        assertThat(entered).isEqualTo("0B6F3F3E-3c1a-4f6e-9d4b-2a7c5e8f1a90");
        assertThat(output.toString(StandardCharsets.UTF_8)).contains(">>> create it")
                .contains("\"DS1-0001\" is not a data set ID").containsOnlyOnce("Please enter it again");
    }

    /** Scripts driving the runner rely on these lines, see "Driving a run from a script" in testdata/README.md. */
    @Test
    void instructionStartsItsLineAndTheDataSetIdIsAskedForRightAfterIt() {
        operator("0b6f3f3e-3c1a-4f6e-9d4b-2a7c5e8f1a90\n").createDataset("create it", LIMIT);

        assertThat(output.toString(StandardCharsets.UTF_8).lines().toList()).containsExactly("", ">>> create it", "Data set ID: ");
    }

    @Test
    void closedInputIsNoOperatorInput() {
        assertThatThrownBy(() -> operator("not-a-uuid\n").createDataset("create it", LIMIT))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("No operator input");
    }

    @Test
    void noDataSetIdWithinTheLimitStopsTheRun() throws IOException {
        try (PipedWriter typed = new PipedWriter()) {
            ConsoleOperator operator = operator(new PipedReader(typed));

            assertThatThrownBy(() -> operator.createDataset("create it", Duration.ofMillis(100)))
                    .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("No data set ID was entered within");
        }
    }

    @Test
    void instructionReturnsAtOnceAndEnterConfirmsIt() throws IOException, InterruptedException {
        try (PipedWriter typed = new PipedWriter()) {
            ConsoleOperator operator = operator(new PipedReader(typed));

            operator.instruct("sign it");
            assertThat(operator.confirmed()).isFalse();
            typed.write("\n");
            typed.flush();

            assertThat(confirmedWithin(operator, LIMIT)).isTrue();
            assertThat(operator.confirmed()).isFalse();
            assertThat(output.toString(StandardCharsets.UTF_8)).contains(">>> sign it").contains("press Enter once you have done");
        }
    }

    @Test
    void enterBeforeTheInstructionDoesNotConfirmIt() throws IOException, InterruptedException {
        try (PipedWriter typed = new PipedWriter()) {
            ConsoleOperator operator = operator(new PipedReader(typed));
            typed.write("\n");
            typed.flush();
            Thread.sleep(200);

            operator.instruct("correct it");

            assertThat(confirmedWithin(operator, Duration.ofMillis(200))).isFalse();
        }
    }

    @Test
    void closedInputIsNoConfirmation() {
        ConsoleOperator operator = operator("");
        operator.instruct("sign it");

        assertThatThrownBy(() -> confirmedWithin(operator, LIMIT))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("No operator input");
    }
}
