package eu.efti.tst.domibus;

import eu.efti.tst.core.MessageRecord;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sends and fetches AS4 messages through the WS plugin of one Domibus domain (pull mode, no inbound endpoint). */
public class DomibusClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Result of {@link #fetchNew}.
     *
     * @param messages   the retrieved messages, in the order Domibus lists them
     * @param unreadable failures of messages that could not be retrieved, by AS4 message ID; they stay pending
     */
    public record Fetched(List<MessageRecord> messages, Map<String, DomibusException> unreadable) {

        public Fetched {
            messages = List.copyOf(messages);
            unreadable = Map.copyOf(unreadable);
        }
    }

    private final DomibusConfig config;
    private final HttpClient httpClient;

    public DomibusClient(DomibusConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /**
     * Submits an XML payload to {@code toParty} and returns the AS4 message ID assigned by Domibus.
     *
     * @param refToMessageId AS4 message ID of the message this one answers (eb:RefToMessageId), null if none
     *
     * @throws DomibusException on failure; see {@link DomibusException#mayHaveBeenDelivered()} before retrying
     */
    public String submit(String toParty, String conversationId, String refToMessageId, String payloadXml) {
        return SoapMessages.parseSubmitResponse(
                call(SoapMessages.submitRequest(config, toParty, conversationId, refToMessageId, payloadXml)));
    }

    /**
     * Returns all received messages that are not yet marked as downloaded. Nothing is marked here: the caller marks
     * each message with {@link #markDownloaded} once it has handled it, so a message is returned again until then
     * (at-least-once). The timestamp of a record is the time it was retrieved. A message without payload is returned
     * with an empty payload, one without eb:RefToMessageId with a null {@code refToMessageId}. A message whose
     * retrieval Domibus answers with a fault or an unexpected response is reported in {@link Fetched#unreadable()}
     * instead, so it does not block the others.
     *
     * @param direction direction to record, i.e. {@code FROM_PLATFORM} when this domain is the gate side
     * @throws DomibusException if Domibus cannot be reached or answers with an HTTP error
     */
    public Fetched fetchNew(MessageRecord.Direction direction) {
        List<MessageRecord> messages = new ArrayList<>();
        Map<String, DomibusException> unreadable = new LinkedHashMap<>();
        for (String messageId : SoapMessages.parsePendingMessageIds(call(SoapMessages.listPendingMessagesRequest()))) {
            String response = call(SoapMessages.retrieveMessageRequest(messageId));
            try {
                messages.add(SoapMessages.parseRetrieveResponse(response, direction, Instant.now()));
            } catch (DomibusException e) {
                unreadable.put(messageId, e);
            }
        }
        return new Fetched(messages, unreadable);
    }

    /** Marks a message returned by {@link #fetchNew} as downloaded, so it is not returned again. */
    public void markDownloaded(String as4MessageId) {
        SoapMessages.parseMarkAsDownloadedResponse(call(SoapMessages.markAsDownloadedRequest(as4MessageId)));
    }

    /**
     * Status of a message this domain sent, as named by the WS plugin, e.g. {@code ACKNOWLEDGED} (receipt received),
     * {@code WAITING_FOR_RETRY}, {@code SEND_FAILURE} or {@code NOT_FOUND}.
     */
    public String getStatus(String as4MessageId) {
        return SoapMessages.parseStatusResponse(call(SoapMessages.statusRequest(as4MessageId)));
    }

    /** Posts a SOAP envelope and returns the response body; SOAP faults are left to the parsers. */
    private String call(String envelope) {
        HttpRequest request = HttpRequest.newBuilder(config.wsPluginUrl())
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/soap+xml; charset=UTF-8")
                .header("Authorization", basicAuth())
                .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw new DomibusException("Connecting to Domibus WS plugin at " + config.wsPluginUrl() + " failed", e, false);
        } catch (IOException e) {
            throw new DomibusException("Calling Domibus WS plugin at " + config.wsPluginUrl() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomibusException("Interrupted while calling Domibus WS plugin", e);
        }
        if (response.statusCode() == 200 || isSoapFault(response)) {
            return response.body();
        }
        throw new DomibusException("Domibus WS plugin returned HTTP " + response.statusCode() + ": "
                + SoapMessages.abbreviate(response.body()), null, response.statusCode() >= 500);
    }

    // SOAP 1.2 faults come with HTTP 4xx/5xx; they carry a more useful error than the status code.
    private static boolean isSoapFault(HttpResponse<String> response) {
        return response.statusCode() >= 400 && response.body().contains(SoapMessages.SOAP_NS)
                && response.body().contains("Fault");
    }

    private String basicAuth() {
        String credentials = config.user() + ":" + config.password();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
