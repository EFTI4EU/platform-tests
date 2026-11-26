package eu.efti.tst.runner;

import eu.efti.tst.core.CheckedMessage;
import eu.efti.tst.core.ConformanceChecks;
import eu.efti.tst.core.ConformanceContext;
import eu.efti.tst.core.Finding;
import eu.efti.tst.core.Format;
import eu.efti.tst.core.MessageHistory;
import eu.efti.tst.core.MessageKind;
import eu.efti.tst.core.MessageRecord;
import eu.efti.tst.core.ParsedMessage;
import eu.efti.tst.core.TestCaseIds;
import eu.efti.tst.domibus.DomibusException;
import eu.efti.tst.runner.RunEvidence.ReceivedMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The gate side of the exchange: sends requests, polls the platform's messages, runs the conformance checks on every
 * message and answers identifier uploads with an upload status. Single-threaded: messages are only polled while the
 * scenario waits, which is the only time it needs them.
 *
 * <p>Transport errors are retried as long as the transport is unavailable for no longer than the outage limit;
 * beyond it, or if a failed message may have been sent anyway, an {@link IllegalStateException} ends the run as a
 * technical error. An outage is never held against the platform: waits only count while the transport works, the
 * response time of a response pending during an outage is not evaluated, and a run that stops does not report the
 * requests pending during an outage as unanswered.
 */
final class GateEngine {

    /** Late responses only fail T-ALL-04, so the gate waits this many response timeouts for them. */
    private static final double RESPONSE_WAIT_FACTOR = 1.2;
    /** AS4 statuses of a message that the access point is still sending (or retrying). */
    private static final Set<String> IN_TRANSIT = Set.of("READY_TO_SEND", "SEND_ENQUEUED", "SEND_IN_PROGRESS",
            "WAITING_FOR_RECEIPT", "WAITING_FOR_RETRY");

    private final Format format;
    private final Transport transport;
    private final ConformanceContext context;
    private final Duration pollInterval;
    private final Duration outageLimit;
    private final Consumer<String> log;
    private final MessageHistory history;
    // Also read by the shutdown hook that reports an interrupted run.
    private final List<ReceivedMessage> received = new CopyOnWriteArrayList<>();
    private volatile int responseTimesNotEvaluated;
    private final Map<String, String> datasetKeys = new HashMap<>();
    private final Set<String> receivedIds = new HashSet<>();
    private String datasetKey;
    private Instant failingSince;
    // Also read by the shutdown hook that reports an interrupted run.
    private volatile Instant lastTransportFailure = Instant.MIN;

    GateEngine(Format format, Transport transport, ConformanceContext context, Duration pollInterval,
               Duration outageLimit, Consumer<String> log) {
        this.format = format;
        this.transport = transport;
        this.context = context;
        this.pollInterval = pollInterval;
        this.outageLimit = outageLimit;
        this.log = log;
        this.history = new MessageHistory(format);
    }

    Format format() {
        return format;
    }

    ConformanceContext context() {
        return context;
    }

    MessageHistory history() {
        return history;
    }

    List<ReceivedMessage> received() {
        return List.copyOf(received);
    }

    /** The number of responses whose response time an outage distorted, so that T-ALL-04 does not evaluate it. */
    int responseTimesNotEvaluated() {
        return responseTimesNotEvaluated;
    }

    /** How long the gate waits for the response to a request. */
    Duration responseWait() {
        return Duration.ofMillis(Math.round(context.responseTimeout().toMillis() * RESPONSE_WAIT_FACTOR));
    }

    /** Findings on the messages received from now on are attributed to this data set. */
    void attributeTo(String datasetKey) {
        this.datasetKey = datasetKey;
    }

    /** Messages of this data set belong to the data set key, also if they arrive during the run of another data set. */
    void bind(String datasetId, String datasetKey) {
        datasetKeys.put(datasetId.toLowerCase(Locale.ROOT), datasetKey);
    }

    private String datasetKeyOf(ParsedMessage message) {
        return message.datasetId().or(() -> history.requestAnsweredBy(message).flatMap(ParsedMessage::datasetId))
                .map(id -> datasetKeys.get(id.toLowerCase(Locale.ROOT)))
                .orElse(datasetKey);
    }

    /**
     * Discards messages left over from earlier runs, so that they are not mistaken for unsolicited messages; also
     * verifies that the transport works.
     */
    void discardPending() {
        List<MessageRecord> leftovers = transport.fetchNew();
        leftovers.forEach(record -> transport.markDownloaded(record.as4MessageId()));
        if (!leftovers.isEmpty()) {
            log.accept("Discarded " + leftovers.size() + " message(s) received before this run");
        }
    }

    private static String summary(ParsedMessage message) {
        return message.record().testCaseId().map(id -> "[" + id + "] ").orElse("") + message.kind() + message.requestId().map(id -> " request ID " + id).orElse("")
                + message.datasetId().map(id -> " data set " + id).orElse("")
                + message.status().map(status -> " status " + status).orElse("");
    }

    /**
     * Tolerates a transport error unless the transport has been failing for longer than the outage limit. Only the
     * first failure of an outage is logged, so that the retries do not push the operator's instruction off the screen.
     */
    private void tolerateOutage(String action, DomibusException e) {
        Instant now = Instant.now();
        lastTransportFailure = now;
        if (failingSince == null) {
            failingSince = now;
            log.accept(action + " failed, retrying for up to " + outageLimit.toSeconds() + " s: " + e.getMessage());
        }
        if (Duration.between(failingSince, now).compareTo(outageLimit) > 0) {
            throw new IllegalStateException(action + " failed for more than " + outageLimit.toSeconds() + " s: "
                    + e.getMessage(), e);
        }
    }

    /** Ends the outage, if any, after a transport call that worked. */
    private void transportWorked() {
        if (failingSince != null) {
            log.accept("The gate's access point works again after "
                    + Duration.between(failingSince, Instant.now()).toSeconds() + " s");
            failingSince = null;
        }
    }

    private void sleep() {
        try {
            Thread.sleep(pollInterval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    private ParsedMessage send(String template, Map<String, String> values, String conversationId, String refToMessageId) {
        String payload = format.render(template, values);
        while (true) {
            try {
                ParsedMessage message = ConformanceChecks.checkAndAppend(
                        transport.submit(conversationId, refToMessageId, payload), history, context).message();
                transportWorked();
                log.accept("-> " + summary(message));
                return message;
            } catch (DomibusException e) {
                // A resent request would be a second request to the platform (and a false T-ALL-03 finding).
                if (e.mayHaveBeenDelivered()) {
                    lastTransportFailure = Instant.now();
                    throw new IllegalStateException("Sending to the platform failed, and the message may have been "
                            + "sent anyway, so it is not sent again: " + e.getMessage(), e);
                }
                tolerateOutage("Sending to the platform", e);
                sleep();
            }
        }
    }

    /** Renders a template and sends it to the platform in a new conversation tagged with the test case. */
    ParsedMessage request(String testCaseId, String template, Map<String, String> values) {
        return send(template, values, MessageRecord.conversationIdFor(testCaseId), null);
    }

    /** Answers the upload in its conversation, referring to it by its AS4 message ID as the platform's responses must. */
    private void answerUpload(ParsedMessage upload) {
        List<String> errors = StepChecks.gateValidation(format, upload.record().payload());
        send(errors.isEmpty() ? "upload-status-success" : "upload-status-error",
                Map.of("requestId", upload.requestId().orElse(""), "status", format.invalidRequestStatus(),
                        "description", String.join("; ", errors)),
                upload.record().conversationId(), upload.record().as4MessageId());
    }

    /**
     * Whether the transport failed after the request was sent. No messages are fetched while the transport fails or a
     * failed action is retried, so the gate may have retrieved the response late or not at all.
     */
    private boolean pendingDuringOutage(MessageRecord request) {
        return request.timestamp().isBefore(lastTransportFailure);
    }

    /** Responses are timestamped when retrieved, so an outage while the request was pending distorts the time. */
    private List<Finding> withoutDistortedResponseTime(CheckedMessage checked) {
        boolean distorted = history.requestAnsweredBy(checked.message())
                .filter(request -> pendingDuringOutage(request.record())).isPresent();
        if (!distorted) {
            return checked.findings();
        }
        responseTimesNotEvaluated++;
        return checked.findings().stream().filter(finding -> !finding.testCaseId().equals(TestCaseIds.ALL_04)).toList();
    }

    private void receive(MessageRecord record) {
        CheckedMessage checked = ConformanceChecks.checkAndAppend(record, history, context);
        List<Finding> findings = withoutDistortedResponseTime(checked);
        received.add(new ReceivedMessage(datasetKeyOf(checked.message()), checked.message(), findings));
        log.accept("<- " + summary(checked.message()) + (findings.isEmpty() ? "" : "  findings: "
                + findings.stream().map(Finding::testCaseId).collect(Collectors.joining(", "))));
        if (checked.message().kind() == MessageKind.IDENTIFIER_UPLOAD) {
            answerUpload(checked.message());
        }
    }

    private void markDownloaded(String as4MessageId) {
        try {
            transport.markDownloaded(as4MessageId);
        } catch (DomibusException e) {
            log.accept("Marking AS4 message " + as4MessageId + " as downloaded failed, it will come again: " + e.getMessage());
        }
    }

    /** Fetches and processes the platform's new messages. */
    void poll() {
        List<MessageRecord> records;
        try {
            records = transport.fetchNew();
            transportWorked();
        } catch (DomibusException e) {
            tolerateOutage("Receiving from the platform", e);
            return;
        }
        // Marked only once processed, so that no message is lost; one that comes again is processed once.
        records.forEach(record -> {
            if (receivedIds.add(record.as4MessageId())) {
                receive(record);
            }
            markDownloaded(record.as4MessageId());
        });
    }

    /**
     * Polls until the condition yields a value or the timeout elapses. The timeout only counts while the transport
     * works, so that an outage ends the run via the outage limit instead of looking like a missing response.
     */
    <T> Optional<T> await(Supplier<Optional<T>> condition, Duration timeout) {
        Instant previousPoll = Instant.now();
        Instant deadline = previousPoll.plus(timeout);
        while (true) {
            poll();
            Instant now = Instant.now();
            if (failingSince != null) {
                deadline = deadline.plus(Duration.between(previousPoll, now));
            }
            previousPoll = now;
            Optional<T> result = condition.get();
            if (result.isPresent() || !now.isBefore(deadline)) {
                return result;
            }
            sleep();
        }
    }

    Optional<ParsedMessage> awaitResponse(ParsedMessage request) {
        return await(() -> history.responseTo(request), responseWait());
    }

    /**
     * The requests overdue in a run that stops now. Those pending during an outage are left out, as the platform may
     * have answered them in time.
     */
    List<Finding> overdueAtStop() {
        Set<String> pendingDuringOutage = history.messages().stream().map(ParsedMessage::record)
                .filter(this::pendingDuringOutage).map(MessageRecord::as4MessageId).collect(Collectors.toSet());
        return ConformanceChecks.checkOverdue(history, Instant.now(), context).stream()
                .filter(finding -> !pendingDuringOutage.contains(finding.as4MessageId()))
                .toList();
    }

    /** Polls and returns the number of messages exchanged so far, to tell the messages after this point. */
    int mark() {
        poll();
        return history.messages().size();
    }

    /**
     * The AS4 status of every message sent to the platform, by AS4 message ID. Messages still in transit are queried
     * again until the grace period elapses.
     *
     * @throws IllegalStateException if the status of a message cannot be queried: this is a gate-side error, so it does
     *                               not fail the platform, but the run must not pass without knowing the receipt
     */
    Map<String, String> receiptStatuses(Duration grace) {
        Map<String, String> statuses = new LinkedHashMap<>();
        Predicate<String> pending = id -> !statuses.containsKey(id) || IN_TRANSIT.contains(statuses.get(id));
        List<String> sent = history.messages().stream()
                .filter(message -> message.record().direction() == MessageRecord.Direction.TO_PLATFORM)
                .map(message -> message.record().as4MessageId())
                .toList();
        await(() -> {
            sent.stream().filter(pending).forEach(id -> {
                try {
                    statuses.put(id, transport.status(id));
                    transportWorked();
                } catch (DomibusException e) {
                    tolerateOutage("Querying the AS4 status of message " + id, e);
                }
            });
            return sent.stream().noneMatch(pending) ? Optional.of(true) : Optional.empty();
        }, grace);
        long unknown = sent.stream().filter(id -> !statuses.containsKey(id)).count();
        if (unknown > 0) {
            throw new IllegalStateException("The AS4 status of " + unknown + " of " + sent.size()
                    + " messages sent to the platform could not be queried, so their receipt is unknown");
        }
        return statuses;
    }
}
