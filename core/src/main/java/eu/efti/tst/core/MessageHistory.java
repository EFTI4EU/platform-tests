package eu.efti.tst.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Record of the messages exchanged with one platform, parsed with the format, in the order they were appended,
 * together with the pairing of responses and requests.
 *
 * <p>Thread-safe: every method is atomic. Checking a message and appending it are two calls, though, so to do both
 * atomically (e.g. when several threads receive messages) use {@link ConformanceChecks#checkAndAppend}.
 *
 * <p>Pairing: a response answers a request of the kind given by {@link MessageKind#answers()}, sent in the opposite
 * direction. It is paired
 * <ol>
 *   <li>with the request whose AS4 message ID is its RefToMessageId, if it carries one (the eFTI technical guidance
 *       requires it of every response, see {@link MessageRecord#refToMessageId()}); if that is no such request, the
 *       response answers none;</li>
 *   <li>without RefToMessageId, with the request sent in its AS4 conversation, if exactly one request was sent in that
 *       conversation (the gate side uses a new conversation per request and expects the answer in it);</li>
 *   <li>otherwise with the first unanswered request whose request ID it echoes (see
 *       {@link Format#acceptedEchoes}).</li>
 * </ol>
 * A response is paired when it is appended, and the pairing does not change afterwards. Whether a response paired
 * by RefToMessageId or conversation echoes the right request ID is checked by {@link ConformanceChecks}.
 */
public final class MessageHistory {

    /**
     * The pairing of a response.
     *
     * @param candidates the requests the response may answer, in order: the request its RefToMessageId refers to,
     *                   or without RefToMessageId the request of its conversation or the requests whose request ID it
     *                   echoes
     * @param request    the first candidate that is not answered yet, which the response answers
     */
    record Pairing(List<ParsedMessage> candidates, Optional<ParsedMessage> request) {
    }

    private record EchoKey(MessageKind requestKind, String echo) {
    }

    private static final Set<MessageKind> REQUEST_KINDS = Arrays.stream(MessageKind.values())
            .flatMap(kind -> kind.answers().stream())
            .collect(Collectors.toUnmodifiableSet());

    private final Format format;
    private final Object lock = new Object();
    private final List<ParsedMessage> messages = new ArrayList<>();
    private final Map<String, List<ParsedMessage>> requestsByAs4MessageId = new HashMap<>();
    private final Map<String, List<ParsedMessage>> requestsByConversation = new HashMap<>();
    private final Map<EchoKey, List<ParsedMessage>> requestsByEcho = new HashMap<>();
    private final Map<ParsedMessage, ParsedMessage> responseByRequest = new HashMap<>();
    private final Map<ParsedMessage, Optional<ParsedMessage>> requestByResponse = new HashMap<>();
    private final Map<ParsedMessage, List<ParsedMessage>> allResponsesByRequest = new HashMap<>();
    private final Map<String, ParsedMessage> acceptedUploadByDataset = new HashMap<>();
    private final Set<String> platformAs4MessageIds = new HashSet<>();

    public MessageHistory(Format format) {
        this.format = format;
    }

    private static boolean isFromPlatform(ParsedMessage message) {
        return message.record().direction() == MessageRecord.Direction.FROM_PLATFORM;
    }

    private static boolean canAnswer(ParsedMessage response, ParsedMessage request) {
        return response.kind().answers().equals(Optional.of(request.kind())) && isFromPlatform(response) != isFromPlatform(request);
    }

    /** Data set IDs are UUIDs, which are case-insensitive (RFC 9562). */
    private static String datasetKey(String datasetId) {
        return datasetId.toLowerCase(Locale.ROOT);
    }

    private static <K> void index(Map<K, List<ParsedMessage>> index, K key, ParsedMessage message) {
        index.computeIfAbsent(key, k -> new ArrayList<>()).add(message);
    }

    private static <K> void removeFrom(Map<K, List<ParsedMessage>> index, Predicate<ParsedMessage> removed) {
        index.values().forEach(list -> list.removeIf(removed));
        index.values().removeIf(List::isEmpty);
    }

    public Format format() {
        return format;
    }

    /** Runs the action while no other thread can access the history. */
    <T> T atomically(Supplier<T> action) {
        synchronized (lock) {
            return action.get();
        }
    }

    /** Requests the response may answer by the pairing rules, not yet restricted to those it can answer. */
    private List<ParsedMessage> pairingCandidates(ParsedMessage response, MessageKind requestKind) {
        String refToMessageId = response.record().refToMessageId();
        if (refToMessageId != null) {
            return requestsByAs4MessageId.getOrDefault(refToMessageId, List.of());
        }
        List<ParsedMessage> inConversation = requestsByConversation.getOrDefault(response.record().conversationId(), List.of());
        if (inConversation.size() == 1 && canAnswer(response, inConversation.getFirst())) {
            return inConversation;
        }
        return response.requestId().stream()
                .flatMap(echo -> requestsByEcho.getOrDefault(new EchoKey(requestKind, echo), List.of()).stream())
                .toList();
    }

    /** The pairing a response that is not part of the history yet would get if it were appended now. */
    Pairing pairing(ParsedMessage response) {
        synchronized (lock) {
            Optional<MessageKind> requestKind = response.kind().answers();
            if (requestKind.isEmpty()) {
                return new Pairing(List.of(), Optional.empty());
            }
            List<ParsedMessage> candidates = pairingCandidates(response, requestKind.get()).stream()
                    .filter(request -> canAnswer(response, request))
                    .toList();
            return new Pairing(candidates, candidates.stream().filter(request -> !responseByRequest.containsKey(request)).findFirst());
        }
    }

    /** Parses, pairs and appends the message. */
    public ParsedMessage append(MessageRecord record) {
        return append(format.parse(record));
    }

    ParsedMessage append(ParsedMessage message) {
        synchronized (lock) {
            if (message.kind().answers().isPresent()) {
                Pairing pairing = pairing(message);
                Optional<ParsedMessage> request = pairing.request();
                requestByResponse.putIfAbsent(message, request);
                request.or(() -> pairing.candidates().stream().findFirst())
                        .ifPresent(target -> index(allResponsesByRequest, target, message));
                request.ifPresent(answered -> {
                    responseByRequest.put(answered, message);
                    if (message.kind() == MessageKind.UPLOAD_STATUS && message.success()) {
                        answered.datasetId().ifPresent(datasetId -> acceptedUploadByDataset.put(datasetKey(datasetId), answered));
                    }
                });
            }
            if (REQUEST_KINDS.contains(message.kind())) {
                Optional.ofNullable(message.record().as4MessageId()).ifPresent(id -> index(requestsByAs4MessageId, id, message));
                Optional.ofNullable(message.record().conversationId()).ifPresent(id -> index(requestsByConversation, id, message));
                format.acceptedEchoes(message).forEach(echo -> index(requestsByEcho, new EchoKey(message.kind(), echo), message));
            }
            if (isFromPlatform(message) && message.record().as4MessageId() != null) {
                platformAs4MessageIds.add(message.record().as4MessageId());
            }
            messages.add(message);
            return message;
        }
    }

    /** Snapshot of all messages. */
    public List<ParsedMessage> messages() {
        synchronized (lock) {
            return List.copyOf(messages);
        }
    }

    public List<MessageRecord> records() {
        return messages().stream().map(ParsedMessage::record).toList();
    }

    /**
     * The requests the response may answer, in order (see the pairing rules above), including answered ones; empty
     * if the response is not correlatable (unsolicited, or neither referring to a request by RefToMessageId nor in a
     * request's conversation nor echoing the request ID of a request).
     *
     * @deprecated use {@link #requestAnsweredBy} for the request a response answers; a response is uncorrelatable if
     * that is empty and the response is no duplicate answer
     */
    @Deprecated
    public List<ParsedMessage> requestsAnswerableBy(ParsedMessage response) {
        return pairing(response).candidates();
    }

    /**
     * The request a response answers, empty if it answers no (further) request. The response need not be part of
     * the history yet; if it is, its pairing at the time it was appended is returned.
     */
    public Optional<ParsedMessage> requestAnsweredBy(ParsedMessage response) {
        synchronized (lock) {
            Optional<ParsedMessage> paired = requestByResponse.get(response);
            return paired != null ? paired : pairing(response).request();
        }
    }

    /** The response answering a request, empty if it has not been answered (yet). */
    public Optional<ParsedMessage> responseTo(ParsedMessage request) {
        synchronized (lock) {
            return Optional.ofNullable(responseByRequest.get(request));
        }
    }

    /**
     * All responses to a request, in order: the one answering it (see {@link #responseTo}) and every further response
     * referring to it although it was already answered (by the pairing rules above, the first of its candidates), which
     * the checks report as duplicate answers.
     */
    public List<ParsedMessage> allResponsesTo(ParsedMessage request) {
        synchronized (lock) {
            return List.copyOf(allResponsesByRequest.getOrDefault(request, List.of()));
        }
    }

    /**
     * The identifier upload of the platform for the data set that the gate side accepted last, i.e. answered with an
     * upload status with a success status. Uploads the gate side rejected do not count. Data set IDs are compared
     * case-insensitively.
     */
    public Optional<ParsedMessage> latestAcceptedUpload(String datasetId) {
        synchronized (lock) {
            return Optional.ofNullable(acceptedUploadByDataset.get(datasetKey(datasetId)));
        }
    }

    /** Requests (of either direction) that have not been answered (yet), in order. */
    List<ParsedMessage> unansweredRequests() {
        synchronized (lock) {
            return messages.stream()
                    .filter(message -> REQUEST_KINDS.contains(message.kind()) && !responseByRequest.containsKey(message))
                    .toList();
        }
    }

    /** Whether the platform already sent an identifier upload with this request ID. */
    boolean isUploadRequestIdUsed(String requestId) {
        synchronized (lock) {
            return requestsByEcho.getOrDefault(new EchoKey(MessageKind.IDENTIFIER_UPLOAD, requestId), List.of()).stream()
                    .anyMatch(MessageHistory::isFromPlatform);
        }
    }

    /** Whether the platform already sent a message with this AS4 message ID. */
    boolean isPlatformAs4MessageIdUsed(String as4MessageId) {
        synchronized (lock) {
            return platformAs4MessageIds.contains(as4MessageId);
        }
    }

    /**
     * Removes the messages exchanged before the given time, so that a gate can keep a history of production traffic
     * for an unlimited time. The latest accepted identifier upload of every data set is retained, as it is the
     * baseline for checking the data responses (T-DAT-05); these grow with the number of data sets.
     *
     * <p>Run {@link ConformanceChecks#checkOverdue} before pruning and choose a time well before now minus the
     * response timeout: unanswered requests are pruned as well (and no longer reported as overdue), a response to a
     * pruned request is reported as unsolicited, and the uniqueness checks only see the retained messages.
     */
    public void prune(Instant olderThan) {
        synchronized (lock) {
            Set<ParsedMessage> baselines = Set.copyOf(acceptedUploadByDataset.values());
            Predicate<ParsedMessage> pruned = message -> message.record().timestamp().isBefore(olderThan)
                    && !baselines.contains(message);
            messages.removeIf(pruned);
            removeFrom(requestsByAs4MessageId, pruned);
            removeFrom(requestsByConversation, pruned);
            removeFrom(requestsByEcho, pruned);
            responseByRequest.keySet().removeIf(pruned);
            requestByResponse.keySet().removeIf(pruned);
            allResponsesByRequest.keySet().removeIf(pruned);
            removeFrom(allResponsesByRequest, pruned);
            platformAs4MessageIds.clear();
            messages.stream()
                    .filter(MessageHistory::isFromPlatform)
                    .map(message -> message.record().as4MessageId())
                    .filter(Objects::nonNull)
                    .forEach(platformAs4MessageIds::add);
        }
    }
}
