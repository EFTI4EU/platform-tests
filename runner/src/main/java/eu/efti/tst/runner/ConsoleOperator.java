package eu.efti.tst.runner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Operator interaction on the console. The console is read in the background, so that the runner keeps receiving the
 * platform's messages while it waits for the operator.
 */
final class ConsoleOperator implements Operator {

    private static final Pattern UUID = Pattern.compile("\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}");
    /** Queued once the console input has ended; it stays queued, so that every later read fails too. */
    private static final Optional<String> END = Optional.empty();

    private final BlockingQueue<Optional<String>> lines = new LinkedBlockingQueue<>();
    private final PrintStream out;

    ConsoleOperator(BufferedReader in, PrintStream out) {
        this.out = out;
        Thread reader = new Thread(() -> read(in, lines), "console-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private static void read(BufferedReader in, BlockingQueue<Optional<String>> lines) {
        try {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                lines.add(Optional.of(line));
            }
        } catch (IOException e) {
            // like the end of the input: the run cannot go on without the operator either way
        }
        lines.add(END);
    }

    /** The next line entered within the timeout, empty if none was; {@code expected} names the input in the error message. */
    private Optional<String> nextLine(Duration timeout, String expected) {
        Optional<String> line;
        try {
            line = lines.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
        if (line == null) {
            return Optional.empty();
        }
        if (line.isEmpty()) {
            lines.add(END);
            throw new IllegalStateException("No operator input: the console input was closed before " + expected
                    + ". Start the runner in an interactive terminal.");
        }
        return line;
    }

    @Override
    public void progress(String message) {
        out.println(LocalTime.now().truncatedTo(ChronoUnit.SECONDS) + "  " + message);
    }

    @Override
    public String createDataset(String instruction, Duration limit) {
        out.println();
        out.println(">>> " + instruction);
        Instant deadline = Instant.now().plus(limit);
        while (true) {
            out.print("Data set ID: ");
            out.flush();
            String entered = nextLine(Duration.between(Instant.now(), deadline), "a data set ID was entered")
                    .orElseThrow(() -> new IllegalStateException("No data set ID was entered within " + limit.toMinutes()
                            + " min, so the run was stopped."))
                    .strip();
            if (UUID.matcher(entered).matches()) {
                return entered;
            }
            if (!entered.isEmpty()) {
                out.println("\"" + entered + "\" is not a data set ID: it is a UUID such as "
                        + "0b6f3f3e-3c1a-4f6e-9d4b-2a7c5e8f1a90. Please enter it again.");
            }
        }
    }

    @Override
    public void instruct(String instruction) {
        // Enter pressed before the instruction was shown was meant for an earlier one.
        lines.removeIf(Optional::isPresent);
        out.println();
        out.println(">>> " + instruction);
        out.println("The runner continues as soon as the platform sends the expected identifiers. If it does not, press "
                + "Enter once you have done this step.");
    }

    @Override
    public boolean confirmed() {
        return nextLine(Duration.ZERO, "the step was confirmed").isPresent();
    }
}
