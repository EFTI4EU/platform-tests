package eu.efti.tst.platform;

import eu.efti.tst.core.MessageRecord;
import eu.efti.tst.core.ParsedMessage;
import eu.efti.tst.core.TestCaseIds;
import eu.efti.tst.domibus.DomibusClient;
import eu.efti.tst.domibus.DomibusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory state of the pseudo platform: the operator's actions on data sets and the automatic answers to gate
 * messages. State changes only take effect once the resulting message was submitted to Domibus, so a failed
 * submission leaves the data set unchanged. Changes are serialized, including their calls to Domibus; the page reads
 * a snapshot published after each change, so it never waits for Domibus.
 * <p>
 * A received message is marked as downloaded only after it was answered. If the answer could not be submitted, the
 * message stays pending and is answered on the next poll; it is never answered twice.
 */
@Service
public class PseudoPlatform {

    private static final Logger LOG = LoggerFactory.getLogger(PseudoPlatform.class);

    private final PlatformBehaviour behaviour;
    private final DomibusClient domibus;
    private final PlatformProperties properties;
    private final Map<String, DataSet> dataSets = new LinkedHashMap<>();
    private final List<ParsedMessage> messages = new ArrayList<>();
    private final Set<String> receivedIds = new HashSet<>();
    private final Set<String> answeredIds = new HashSet<>();
    /** Latest failure per AS4 message ID, oldest first. */
    private final Map<String, String> problems = new LinkedHashMap<>();
    private FaultToggles toggles = FaultToggles.NONE;
    private String connectionError;
    private volatile PlatformView view;

    public PseudoPlatform(PlatformBehaviour behaviour, DomibusClient domibus, PlatformProperties properties) {
        this.behaviour = behaviour;
        this.domibus = domibus;
        this.properties = properties;
        publish();
    }

    private void publish() {
        view = new PlatformView(List.copyOf(dataSets.values()).reversed(), messages.reversed(), toggles,
                Optional.ofNullable(connectionError), List.copyOf(problems.values()).reversed());
    }

    private DataSet existing(String datasetId) {
        return Optional.ofNullable(dataSets.get(datasetId))
                .orElseThrow(() -> new IllegalArgumentException("Unknown data set " + datasetId));
    }

    private void requireValid(String datasetXml) {
        List<String> errors = behaviour.validateDataset(datasetXml);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("The data set is invalid:\n" + String.join("\n", errors));
        }
    }

    /** @param refToMessageId AS4 message ID of the message this one answers, null if it answers none */
    private void send(String toParty, String conversationId, String refToMessageId, String payload) {
        String as4MessageId = domibus.submit(toParty, conversationId, refToMessageId, payload);
        messages.add(behaviour.parse(new MessageRecord(as4MessageId, Instant.now(), MessageRecord.Direction.FROM_PLATFORM,
                properties.ownPartyId(), toParty, conversationId, refToMessageId, payload)));
    }

    /**
     * @param testCaseId the test case of the runner's scenario whose step makes the platform send this upload, so that
     *                   the message logs can show it (a real platform has no such knowledge)
     */
    private void upload(DataSet dataSet, String testCaseId) {
        String requestId = UUID.randomUUID().toString();
        send(properties.gatePartyId(), MessageRecord.conversationIdFor(testCaseId), null,
                behaviour.identifierUpload(requestId, dataSet));
    }

    private void apply(DataSet dataSet) {
        dataSets.put(dataSet.id(), dataSet);
        publish();
    }

    /** @return the ID of the new draft */
    public synchronized String create(String datasetXml) {
        requireValid(datasetXml);
        String id = UUID.randomUUID().toString();
        apply(new DataSet(id, datasetXml, DataSet.Status.DRAFT));
        return id;
    }

    public synchronized void signAsCarrier(String datasetId) {
        DataSet signed = existing(datasetId).signedByCarrier();
        upload(signed, TestCaseIds.RUN_02);
        apply(signed);
    }

    /** @return whether an identifier upload was sent */
    public synchronized boolean update(String datasetId, String datasetXml) {
        DataSet updated = existing(datasetId).updated(datasetXml);
        requireValid(datasetXml);
        boolean sendUpload = updated.status() == DataSet.Status.ACTIVE && !toggles.skipUploadOnUpdate();
        if (sendUpload) {
            upload(updated, TestCaseIds.RUN_03);
        }
        apply(updated);
        return sendUpload;
    }

    public synchronized void signAsConsignee(String datasetId) {
        DataSet current = existing(datasetId);
        String signedXml = behaviour.signAsConsignee(current.xml(), OffsetDateTime.now());
        DataSet signed = current.signedByConsignee(signedXml, behaviour.isRoadTransport(signedXml));
        requireValid(signed.xml());
        upload(signed, TestCaseIds.RUN_06);
        apply(signed);
    }

    /**
     * Forgets the data sets, the message log and the failures, so that the next test run starts on a clean page. The
     * fault toggles stay, and so do the IDs of received and answered messages: a message still pending in Domibus is
     * neither logged nor answered again.
     */
    public synchronized void clear() {
        dataSets.clear();
        messages.clear();
        problems.clear();
        publish();
    }

    public synchronized void setToggles(FaultToggles toggles) {
        this.toggles = toggles;
        publish();
    }

    public PlatformView view() {
        return view;
    }

    private void recordProblem(String as4MessageId, String description, RuntimeException e) {
        if (problems.remove(as4MessageId) == null) {
            LOG.warn("Message {}: {} (later failures of this message are only shown on the page)", as4MessageId, description, e);
        }
        problems.put(as4MessageId, Instant.now() + ": message " + as4MessageId + ": " + description + ": " + e.getMessage());
    }

    private void handle(MessageRecord received) {
        String id = received.as4MessageId();
        ParsedMessage message = behaviour.parse(received);
        if (receivedIds.add(id)) {
            messages.add(message);
        }
        if (!answeredIds.contains(id)) {
            try {
                behaviour.answer(message, dataSets, messages, toggles)
                        .ifPresent(answer -> send(received.fromParty(), received.conversationId(), id, answer));
            } catch (RuntimeException e) {
                boolean retry = e instanceof DomibusException domibusError && !domibusError.mayHaveBeenDelivered();
                recordProblem(id, retry ? "answering failed, trying again" : "answering failed, not trying again", e);
                if (retry) {
                    return;
                }
            }
            answeredIds.add(id);
        }
        try {
            domibus.markDownloaded(id);
        } catch (DomibusException e) {
            recordProblem(id, "marking as downloaded failed, trying again", e);
        }
    }

    private synchronized void handleAll(DomibusClient.Fetched fetched) {
        connectionError = null;
        fetched.unreadable().forEach((id, e) -> recordProblem(id, "reading failed, trying again", e));
        fetched.messages().forEach(this::handle);
        publish();
    }

    private synchronized void pollFailed(DomibusException e) {
        if (connectionError == null) {
            LOG.warn("Polling Domibus failed", e);
        }
        connectionError = Instant.now() + ": " + e.getMessage()
                + Optional.ofNullable(e.getCause()).map(cause -> " (" + cause + ")").orElse("");
        publish();
    }

    @Scheduled(fixedDelayString = "${platform.poll-interval}")
    public void poll() {
        DomibusClient.Fetched fetched;
        try {
            fetched = domibus.fetchNew(MessageRecord.Direction.TO_PLATFORM);
        } catch (DomibusException e) {
            pollFailed(e);
            return;
        }
        handleAll(fetched);
    }
}
