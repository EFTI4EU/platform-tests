package eu.efti.tst.runner;

import eu.efti.tst.core.MessageRecord;
import eu.efti.tst.domibus.DomibusClient;
import eu.efti.tst.domibus.DomibusConfig;
import eu.efti.tst.domibus.DomibusException;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** {@link Transport} via the WS plugin of the gate's Domibus domain. */
final class DomibusTransport implements Transport {

    private final DomibusClient client;
    private final String ownPartyId;
    private final String platformPartyId;
    private final Consumer<String> log;
    // Unreadable messages and those of other parties may come again on every poll; they are reported once.
    private final Set<String> reported = new HashSet<>();

    DomibusTransport(DomibusClient client, String ownPartyId, String platformPartyId, Consumer<String> log) {
        this.client = client;
        this.ownPartyId = ownPartyId;
        this.platformPartyId = platformPartyId;
        this.log = log;
    }

    DomibusTransport(RunnerConfig.Domibus domibus, String platformPartyId, Consumer<String> log) {
        this(new DomibusClient(DomibusConfig.efti(domibus.url(), domibus.user(), domibus.password(), domibus.partyId())),
                domibus.partyId(), platformPartyId, log);
    }

    @Override
    public MessageRecord submit(String conversationId, String refToMessageId, String payloadXml) {
        String as4MessageId = client.submit(platformPartyId, conversationId, refToMessageId, payloadXml);
        return new MessageRecord(as4MessageId, Instant.now(), MessageRecord.Direction.TO_PLATFORM, ownPartyId,
                platformPartyId, conversationId, refToMessageId, payloadXml);
    }

    /** Marks a message of another party than the platform under test as downloaded, so that it does not come again. */
    private void drop(MessageRecord record) {
        if (reported.add(record.as4MessageId())) {
            log.accept("Ignoring AS4 message " + record.as4MessageId() + " from party " + record.fromParty()
                    + ", which is not the platform under test");
        }
        try {
            client.markDownloaded(record.as4MessageId());
        } catch (DomibusException e) {
            // It comes again and is dropped then; the messages of the platform must not wait for it.
        }
    }

    /** Messages from other parties than the platform under test are dropped. */
    @Override
    public List<MessageRecord> fetchNew() {
        DomibusClient.Fetched fetched = client.fetchNew(MessageRecord.Direction.FROM_PLATFORM);
        fetched.unreadable().forEach((as4MessageId, error) -> {
            if (reported.add(as4MessageId)) {
                log.accept("Cannot read AS4 message " + as4MessageId + ", trying again later: " + error.getMessage());
            }
        });
        Map<Boolean, List<MessageRecord>> fromPlatform = fetched.messages().stream()
                .collect(Collectors.partitioningBy(record -> platformPartyId.equals(record.fromParty())));
        fromPlatform.get(false).forEach(this::drop);
        return fromPlatform.get(true);
    }

    @Override
    public void markDownloaded(String as4MessageId) {
        client.markDownloaded(as4MessageId);
    }

    @Override
    public String status(String as4MessageId) {
        return client.getStatus(as4MessageId);
    }
}
