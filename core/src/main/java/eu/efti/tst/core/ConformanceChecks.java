package eu.efti.tst.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Conformance checks on the messages a platform sends. They need nothing but the exchanged messages and the
 * {@link ConformanceContext}, so a gate can apply them to production traffic for continuous monitoring (the
 * checks of {@link ConformanceContext.Mode#MONITORING}). The checks never throw: messages that are not well-formed
 * or cannot be evaluated yield findings.
 *
 * <p>Covered test cases and the limits of their data-independent evaluation:
 * <ul>
 *   <li>T-DAT-01: well-formed, without bytes that are invalid in its encoding, a message of the format, valid
 *       against the XSD, plus the format's Schematron rules attributed to T-DAT-01 (e.g. the data set ID is a
 *       UUID v4, a date is real and matches its format code).</li>
 *   <li>T-DAT-02: the format's Schematron status rules; the gate and platform identifiers and the data set ID are
 *       compared with the context or the request if the format exposes them in the platform's message. The message
 *       timestamp tolerance is not checked (the format has no message timestamp).</li>
 *   <li>T-DAT-03: the format's Schematron rules (mode of transport and dangerous goods indicator present). Whether
 *       every identifier the data set holds is uploaded needs the data set and is checked via T-DAT-05.</li>
 *   <li>T-DAT-04: no element outside the requested subsets; no sibling elements with identical content; mandatory
 *       elements of a requested subset present and non-empty if their parent is present; non-repeatable aggregates
 *       that hold such mandatory elements present if their parent is present. Conditional elements and business
 *       rules are not checked.</li>
 *   <li>T-DAT-05: the identifier values in a returned data set equal those of the identifier upload for the data set
 *       that the gate side accepted last (uploads it rejected do not count), including values the platform sets
 *       during the lifecycle (e.g. the carrier acceptance date). A response may lack an identifier that is outside
 *       the requested subsets or optional in all of them, and fields of composite identifier values; any other
 *       registered identifier must be present. Without an accepted upload (e.g. if the upload statuses are not appended to the history) the check is
 *       skipped.</li>
 *   <li>T-ALL-03: each data request and follow-up is answered at most once (see {@link MessageHistory} for the
 *       pairing), a response refers to the request it answers by its AS4 RefToMessageId (eFTI technical guidance,
 *       Annex 1, ch. 3) and echoes its request ID (a response to a request without request ID may carry any or none),
 *       the platform sends no unsolicited responses and no message kinds it is not supposed to send, and its AS4
 *       message IDs and the request IDs of its identifier uploads (the closest equivalent of the message instance ID
 *       eFTI1 in this format; AS4 message IDs are deduplicated by the access point) are unique. A follow-up that is
 *       not acknowledged within the timeout is reported by {@link #checkOverdue} (TGD 2, 6.2 pairs every follow-up
 *       with an acknowledgement).</li>
 *   <li>T-ALL-04: responses arrive within the timeout (TGD 1, 4.2 sets it for data requests, the test applies it to
 *       follow-up acknowledgements as well), and {@link #checkOverdue} reports data requests that are not answered
 *       within it (Impl. Reg. (EU) 2024/1942, Art. 3(3)(d)). The time is measured from the time the gate side sent the request, not from the AS4 receipt
 *       the regulation names, since the receipt time is not part of a {@link MessageRecord}; as the test report
 *       states, this counts the AS4 transit time (including retries) against the platform.</li>
 * </ul>
 *
 * <p>Thread safety: {@link #checkAndAppend} checks and appends a message atomically, so it may be called from
 * several threads with the same history.
 */
public final class ConformanceChecks {

    private static final String SYNTAX = TestCaseIds.DAT_01;
    private static final String KNOWN_VALUES = TestCaseIds.DAT_02;
    private static final String DATASET_COMPOSITION = TestCaseIds.DAT_04;
    private static final String IDENTIFIER_CONSISTENCY = TestCaseIds.DAT_05;
    private static final String ROUND_TRIP = TestCaseIds.DAT_06;
    private static final String CORRELATION = TestCaseIds.ALL_03;
    private static final String RESPONSE_TIME = TestCaseIds.ALL_04;
    private static final Set<String> TEST_RUN_ONLY = Set.of(TestCaseIds.DAT_03, DATASET_COMPOSITION, IDENTIFIER_CONSISTENCY);
    /** What decoding leaves in place of bytes that are invalid in the encoding of a message. */
    private static final String REPLACEMENT_CHARACTER = "\uFFFD";

    /**
     * Outcome of {@link #checkRoundTrip}.
     *
     * @param findings        the violations of T-DAT-06
     * @param omittedOptional paths of the optional elements the expected data set contains and the response lacks;
     *                        informative, as a platform may omit them
     * @param addedAttributes paths of the attributes the response adds to values of the expected data set (e.g. the
     *                        language of a text); informative, as they do not change the values
     */
    public record RoundTripResult(List<Finding> findings, List<String> omittedOptional, List<String> addedAttributes) {
    }

    private ConformanceChecks() {
    }

    private static Finding finding(String testCaseId, MessageRecord record, String detail) {
        return new Finding(testCaseId, record.as4MessageId(), detail);
    }

    private static String seconds(Duration duration) {
        return duration.toMillis() / 1000.0 + " s";
    }

    private static List<Finding> guarded(String testCaseId, MessageRecord record, Supplier<List<Finding>> check) {
        try {
            return check.get();
        } catch (RuntimeException e) {
            return List.of(finding(testCaseId, record, "The check cannot be evaluated: "
                    + Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName())));
        }
    }

    private static List<Finding> syntax(ParsedMessage message, Format format) {
        if (message.malformation().isPresent()) {
            return List.of(finding(SYNTAX, message.record(), message.malformation().get()));
        }
        if (message.kind() == MessageKind.UNKNOWN) {
            return List.of(finding(SYNTAX, message.record(), "Not a message of format " + format.version() + "."));
        }
        String payload = message.record().payload();
        List<Finding> findings = new ArrayList<>(format.validateSchema(payload).stream()
                .map(error -> finding(SYNTAX, message.record(), "Schema violation at " + error))
                .toList());
        // An XML parser accepts the replacement character, although undecodable bytes make a message not well-formed
        if (payload.contains(REPLACEMENT_CHARACTER)) {
            findings.add(finding(SYNTAX, message.record(), "The message contains the replacement character U+FFFD: "
                    + "some of its bytes are not valid in its encoding, or its text was damaged before it was sent."));
        }
        findings.addAll(guarded(SYNTAX, message.record(), () -> format.validateRules(payload).stream()
                .map(violation -> finding(violation.testCaseId().isBlank() ? SYNTAX : violation.testCaseId(),
                        message.record(), violation.message() + " (at " + violation.location() + ")"))
                .toList()));
        return findings;
    }

    private static String describe(ParsedMessage request) {
        return request.kind() + request.requestId().map(id -> " with request ID " + id).orElse(" without request ID");
    }

    private static List<Finding> uniqueness(ParsedMessage message, MessageHistory history) {
        List<Finding> findings = new ArrayList<>();
        Optional.ofNullable(message.record().as4MessageId()).filter(history::isPlatformAs4MessageIdUsed).ifPresent(id ->
                findings.add(finding(CORRELATION, message.record(), "The AS4 message ID " + id + " was already used.")));
        if (message.kind() == MessageKind.IDENTIFIER_UPLOAD) {
            message.requestId().filter(history::isUploadRequestIdUsed).ifPresent(id -> findings.add(finding(CORRELATION,
                    message.record(), "The request ID " + id + " was already used by an earlier identifier upload.")));
        }
        return findings;
    }

    private static List<Finding> echo(ParsedMessage response, ParsedMessage request, Format format) {
        List<String> accepted = format.acceptedEchoes(request);
        if (accepted.isEmpty()) {
            // nothing to echo, so a request ID the platform generated for the response is as acceptable as none
            return List.of();
        }
        Optional<String> echoed = response.requestId();
        String pairedBy = response.record().refToMessageId() != null ? "per its AS4 RefToMessageId" : "same AS4 conversation";
        if (echoed.isEmpty()) {
            return List.of(finding(CORRELATION, response.record(), "The response does not echo the request ID of the "
                    + describe(request) + " it answers (" + pairedBy + ")."));
        }
        return accepted.contains(echoed.get())
                ? List.of()
                : List.of(finding(CORRELATION, response.record(), "The response echoes request ID " + echoed.get()
                + " but answers the " + describe(request) + " (" + pairedBy + ")."));
    }

    private static List<Finding> responseTime(ParsedMessage response, ParsedMessage request, ConformanceContext context) {
        Duration elapsed = Duration.between(request.record().timestamp(), response.record().timestamp());
        return elapsed.compareTo(context.responseTimeout()) > 0
                ? List.of(finding(RESPONSE_TIME, response.record(), "Answered after " + seconds(elapsed)
                + ", the limit is " + seconds(context.responseTimeout()) + "."))
                : List.of();
    }

    private static List<Finding> knownValues(ParsedMessage message, ConformanceContext context, Optional<ParsedMessage> request) {
        List<Finding> findings = new ArrayList<>();
        message.gateId().filter(id -> !id.equals(context.gateId())).ifPresent(id -> findings.add(finding(KNOWN_VALUES,
                message.record(), "Gate identifier " + id + " instead of " + context.gateId() + ".")));
        message.platformId().filter(id -> !id.equals(context.platformId())).ifPresent(id -> findings.add(finding(KNOWN_VALUES,
                message.record(), "Platform identifier " + id + " instead of " + context.platformId() + ".")));
        request.flatMap(ParsedMessage::datasetId).ifPresent(requested -> message.datasetId()
                .filter(id -> !id.equalsIgnoreCase(requested))
                .ifPresent(id -> findings.add(finding(KNOWN_VALUES, message.record(),
                        "Data set ID " + id + " instead of the requested " + requested + "."))));
        return findings;
    }

    private static Optional<String> identifierDeviation(List<String> registered, List<String> returned, boolean mustBeReturned) {
        if (returned.isEmpty()) {
            return mustBeReturned ? Optional.of("registered " + registered + " but the data set does not contain it.") : Optional.empty();
        }
        return IdentifierSet.agreeOnReturnedFields(registered, returned) ? Optional.empty()
                : Optional.of("registered " + registered + " but the data set contains " + returned + ".");
    }

    private static List<Finding> identifierConsistency(
            MessageRecord record, String dataset, ParsedMessage request, MessageHistory history) {
        Optional<String> registeredIdentifiers = request.datasetId()
                .flatMap(history::latestAcceptedUpload)
                .flatMap(ParsedMessage::identifiers);
        if (registeredIdentifiers.isEmpty()) {
            return List.of();
        }
        IdentifierSet registered = history.format().identifiers(registeredIdentifiers.get());
        IdentifierSet returned = history.format().identifiers(dataset);
        IdentifierSet notOmissible = history.format().identifiersNotOmissible(registeredIdentifiers.get(), Set.copyOf(request.subsetIds()));
        return Arrays.stream(IdentifierType.values())
                .flatMap(type -> identifierDeviation(registered.get(type), returned.get(type), notOmissible.holdsValue(type))
                        .map(deviation -> finding(IDENTIFIER_CONSISTENCY, record, type + ": " + deviation)).stream())
                .toList();
    }

    private static List<Finding> response(ParsedMessage message, MessageHistory history, ConformanceContext context) {
        MessageKind requestKind = message.kind().answers().orElseThrow();
        String refToMessageId = message.record().refToMessageId();
        List<Finding> findings = new ArrayList<>();
        if (refToMessageId == null) {
            findings.add(finding(CORRELATION, message.record(),
                    "The response carries no AS4 RefToMessageId referring to the " + requestKind + " it answers."));
        }
        MessageHistory.Pairing pairing = history.pairing(message);
        if (pairing.request().isEmpty()) {
            String detail = !pairing.candidates().isEmpty()
                    ? "The " + describe(pairing.candidates().getFirst()) + " was already answered."
                    : refToMessageId != null
                    ? "The AS4 RefToMessageId " + refToMessageId + " refers to no " + requestKind + " sent to the platform."
                    : message.requestId().map(id -> "No " + requestKind + " with request ID " + id
                            + " was sent to the platform (unsolicited response or request ID not echoed).")
                    .orElse("The response does not echo a request ID and was not sent in the AS4 conversation of a "
                            + requestKind + ".");
            findings.add(finding(CORRELATION, message.record(), detail));
            return findings;
        }
        ParsedMessage request = pairing.request().get();
        findings.addAll(echo(message, request, history.format()));
        findings.addAll(guarded(RESPONSE_TIME, message.record(), () -> responseTime(message, request, context)));
        findings.addAll(guarded(KNOWN_VALUES, message.record(), () -> knownValues(message, context, Optional.of(request))));
        if (message.dataset().isPresent()) {
            String dataset = message.dataset().get();
            findings.addAll(guarded(DATASET_COMPOSITION, message.record(), () -> history.format()
                    .compositionViolations(dataset, Set.copyOf(request.subsetIds())).stream()
                    .map(violation -> finding(DATASET_COMPOSITION, message.record(), violation))
                    .toList()));
            findings.addAll(guarded(IDENTIFIER_CONSISTENCY, message.record(),
                    () -> identifierConsistency(message.record(), dataset, request, history)));
        }
        return findings;
    }

    private static List<Finding> findings(ParsedMessage message, MessageHistory history, ConformanceContext context) {
        if (message.record().direction() != MessageRecord.Direction.FROM_PLATFORM) {
            return List.of();
        }
        MessageRecord record = message.record();
        List<Finding> findings = new ArrayList<>(guarded(SYNTAX, record, () -> syntax(message, history.format())));
        findings.addAll(guarded(CORRELATION, record, () -> uniqueness(message, history)));
        findings.addAll(switch (message.kind()) {
            case IDENTIFIER_UPLOAD -> guarded(KNOWN_VALUES, record, () -> knownValues(message, context, Optional.empty()));
            case DATA_RESPONSE, FOLLOW_UP_ACK -> guarded(CORRELATION, record, () -> response(message, history, context));
            case UNKNOWN -> List.of();
            case DATA_REQUEST, FOLLOW_UP, UPLOAD_STATUS -> List.of(finding(CORRELATION, record,
                    "The platform sent a message of kind " + message.kind() + ", which only the gate sends."));
        });
        return context.mode() == ConformanceContext.Mode.TEST_RUN ? findings
                : findings.stream().filter(finding -> !TEST_RUN_ONLY.contains(finding.testCaseId())).toList();
    }

    /**
     * Checks a message against the history and appends it, atomically. Messages sent to the platform yield no
     * findings but must be appended as well (also the upload statuses), as the checks of the responses refer to them.
     *
     * @param record the message to check; it must not be part of the history yet
     */
    public static CheckedMessage checkAndAppend(MessageRecord record, MessageHistory history, ConformanceContext context) {
        ParsedMessage message = history.format().parse(record);
        return history.atomically(() -> {
            List<Finding> findings = findings(message, history, context);
            return new CheckedMessage(history.append(message), findings);
        });
    }

    /**
     * Checks a message. Messages sent to the platform yield no findings but must be appended to the history, as
     * the checks of the responses refer to them.
     *
     * @param record  the message to check
     * @param history the messages exchanged before; the record itself must not be part of it yet
     * @deprecated use {@link #checkAndAppend}, which also appends the message; checking and appending separately is
     * only correct if the caller makes sure that no other message is appended in between
     */
    @Deprecated
    public static List<Finding> check(MessageRecord record, MessageHistory history, ConformanceContext context) {
        ParsedMessage message = history.format().parse(record);
        return history.atomically(() -> findings(message, history, context));
    }

    /**
     * Requests sent to the platform that have not been answered within the timeout: data requests as T-ALL-04,
     * follow-ups as T-ALL-03 (not acknowledged). Stateless: every call reports all requests overdue at that time.
     * Requests without a request ID are skipped, since the platform cannot build a correlatable response for them and
     * may leave them unanswered (T-NEG-02, T-NEG-06).
     */
    public static List<Finding> checkOverdue(MessageHistory history, Instant now, ConformanceContext context) {
        String timeout = seconds(context.responseTimeout());
        try {
            return history.unansweredRequests().stream()
                    .filter(request -> request.record().direction() == MessageRecord.Direction.TO_PLATFORM)
                    .filter(request -> request.requestId().isPresent())
                    .filter(request -> request.record().timestamp().plus(context.responseTimeout()).isBefore(now))
                    .map(request -> request.kind() == MessageKind.DATA_REQUEST
                            ? finding(RESPONSE_TIME, request.record(), "No response to the " + describe(request)
                            + " within " + timeout + ".")
                            : finding(CORRELATION, request.record(), "The " + describe(request)
                            + " was not acknowledged within " + timeout + "."))
                    .toList();
        } catch (RuntimeException e) {
            return List.of(new Finding(RESPONSE_TIME, null, "Overdue responses cannot be evaluated: " + e.getMessage()));
        }
    }

    /**
     * T-DAT-06 (test only): compares the data set of a data response with the expected data set restricted to the
     * subsets of the request it answers. Values the platform sets during the lifecycle (as declared by the format)
     * are not compared, and missing elements are no finding but only listed if they are optional (status O) in all
     * requested subsets, as are attributes that the response adds to values of the expected data set.
     * Responses without a data set yield no findings; their status is checked by T-DAT-02.
     *
     * @param response the data response; it may or may not be part of the history already
     */
    public static RoundTripResult checkRoundTrip(MessageRecord response, MessageHistory history, String expectedDatasetXml) {
        List<String> omittedOptional = new ArrayList<>();
        List<String> addedAttributes = new ArrayList<>();
        List<Finding> findings = guarded(ROUND_TRIP, response, () -> {
            ParsedMessage message = history.format().parse(response);
            if (message.kind() != MessageKind.DATA_RESPONSE || message.dataset().isEmpty()) {
                return List.of();
            }
            Optional<ParsedMessage> request = history.requestAnsweredBy(message);
            if (request.isEmpty()) {
                return List.of(finding(ROUND_TRIP, response,
                        "The response cannot be correlated with a data request, so the requested subsets are unknown."));
            }
            RoundTrip.Comparison comparison = history.format()
                    .roundTrip(expectedDatasetXml, message.dataset().get(), Set.copyOf(request.get().subsetIds()));
            omittedOptional.addAll(comparison.omittedOptional());
            addedAttributes.addAll(comparison.addedAttributes());
            return comparison.differences().stream()
                    .map(difference -> finding(ROUND_TRIP, response, difference))
                    .toList();
        });
        return new RoundTripResult(findings, omittedOptional, addedAttributes);
    }
}
