package eu.efti.tst.domibus;

import eu.efti.tst.core.MessageRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end exchange over real AS4 between the two domains of the local stack (deploy/).
 * Run with {@code -Dtst.domibus.url=http://localhost:18080/domibus/services/wsplugin}.
 */
@EnabledIfSystemProperty(named = "tst.domibus.url", matches = ".+")
class DomibusSmokeTest {

    private static final URI WS_PLUGIN_URL = URI.create(System.getProperty("tst.domibus.url", "http://unused"));
    private static final Duration DELIVERY_TIMEOUT = Duration.ofMinutes(2);

    private final DomibusClient gate = new DomibusClient(DomibusConfig.efti(WS_PLUGIN_URL, "tstgate_ws", "test123", "tstgate"));
    private final DomibusClient platform = new DomibusClient(
            DomibusConfig.efti(WS_PLUGIN_URL, "pseudoplatform_ws", "test123", "pseudoplatform"));

    @Test
    void gateMessageReachesPlatform() throws InterruptedException {
        assertDelivered(gate, platform, "tstgate", "pseudoplatform", MessageRecord.Direction.TO_PLATFORM, null);
    }

    @Test
    void platformMessageReachesGate() throws InterruptedException {
        assertDelivered(platform, gate, "pseudoplatform", "tstgate", MessageRecord.Direction.FROM_PLATFORM,
                "smoke-request-" + UUID.randomUUID() + "@tst");
    }

    private static void assertDelivered(DomibusClient sender, DomibusClient receiver, String from, String to,
                                        MessageRecord.Direction direction, String refToMessageId) throws InterruptedException {
        String conversationId = UUID.randomUUID().toString();
        String payload = "<smokeTest xmlns=\"urn:tst\">" + conversationId + " äöü</smokeTest>";
        Instant start = Instant.now().minusSeconds(5);

        String messageId = sender.submit(to, conversationId, refToMessageId, payload);

        MessageRecord received = awaitMessage(receiver, direction, messageId);
        System.out.printf("%s -> %s delivered: %s%n", from, to, received);
        assertThat(received.fromParty()).isEqualTo(from);
        assertThat(received.toParty()).isEqualTo(to);
        assertThat(received.conversationId()).isEqualTo(conversationId);
        assertThat(received.refToMessageId()).isEqualTo(refToMessageId);
        assertThat(received.payload()).isEqualTo(payload);
        assertThat(received.direction()).isEqualTo(direction);
        assertThat(received.timestamp()).isAfter(start);
        assertThat(awaitStatus(sender, messageId, "ACKNOWLEDGED")).isEqualTo("ACKNOWLEDGED");
    }

    private static String awaitStatus(DomibusClient sender, String messageId, String expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(DELIVERY_TIMEOUT);
        String status = sender.getStatus(messageId);
        while (!status.equals(expected) && Instant.now().isBefore(deadline)) {
            Thread.sleep(1_000);
            status = sender.getStatus(messageId);
        }
        return status;
    }

    private static MessageRecord awaitMessage(DomibusClient receiver, MessageRecord.Direction direction, String messageId)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(DELIVERY_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            List<MessageRecord> messages = receiver.fetchNew(direction).messages();
            messages.forEach(message -> receiver.markDownloaded(message.as4MessageId()));
            Optional<MessageRecord> match = messages.stream()
                    .filter(message -> message.as4MessageId().equals(messageId))
                    .findFirst();
            if (match.isPresent()) {
                return match.get();
            }
            Thread.sleep(2_000);
        }
        throw new AssertionError("Message " + messageId + " not delivered within " + DELIVERY_TIMEOUT);
    }
}
