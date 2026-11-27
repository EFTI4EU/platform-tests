package eu.efti.tst.domibus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Local HTTP server answering like the WS plugin of one Domibus domain, for tests of code using {@link DomibusClient}
 * (also used by other modules via this module's test-jar). It accepts submissions, keeps pending messages until they
 * are marked as downloaded, reports submitted messages as {@code ACKNOWLEDGED}, and can be told to answer otherwise.
 */
public final class DomibusStub implements AutoCloseable {

    public static final String PASSWORD = "test123";

    public record Reply(int status, String body) {
    }

    /** Closes the connection without answering, as if it broke after the request was sent. */
    public static final Reply NO_RESPONSE = new Reply(0, "");

    /** A request to the stub: the operation is the local name of the SOAP body element, e.g. {@code submitRequest}. */
    public record Request(String operation, Optional<String> messageId) {
    }

    /** @param refToMessageId eb:RefToMessageId of the submission, null if it has none */
    public record Submitted(String as4MessageId, String toParty, String conversationId, String refToMessageId,
                            String payload) {
    }

    private final HttpServer server;
    private final Map<String, String> pending = new LinkedHashMap<>();
    private final List<Submitted> submitted = new CopyOnWriteArrayList<>();
    private final List<String> marked = new CopyOnWriteArrayList<>();
    private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    private volatile Function<Request, Reply> override = request -> null;

    private DomibusStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/domibus/services/wsplugin", this::handle);
        server.start();
    }

    public static DomibusStub start() {
        try {
            return new DomibusStub();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/domibus/services/wsplugin");
    }

    public DomibusClient client(String user, String ownPartyId) {
        return new DomibusClient(DomibusConfig.efti(url(), user, PASSWORD, ownPartyId));
    }

    /** Makes a message pending; Domibus answers retrieveMessage for it with the given body. */
    public synchronized void addPending(String messageId, String retrieveResponseBody) {
        pending.put(messageId, retrieveResponseBody);
    }

    /** Replaces the normal answer to every request for which the function returns a reply (not null). */
    public void override(Function<Request, Reply> override) {
        this.override = override;
    }

    public List<Submitted> submitted() {
        return List.copyOf(submitted);
    }

    public List<String> marked() {
        return List.copyOf(marked);
    }

    public synchronized List<String> pending() {
        return List.copyOf(pending.keySet());
    }

    public List<String> authorizationHeaders() {
        return List.copyOf(authorizationHeaders);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- SOAP builders, also for tests of the parsers ----

    public static String soap(String header, String body) {
        return """
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
                  <soap:Header>%s</soap:Header>
                  <soap:Body>%s</soap:Body>
                </soap:Envelope>""".formatted(header, body);
    }

    public static String fault(String reason, String code, String message) {
        return soap("", """
                <soap:Fault>
                  <soap:Code><soap:Value>soap:Receiver</soap:Value></soap:Code>
                  <soap:Reason><soap:Text xml:lang="en">%s</soap:Text></soap:Reason>
                  <soap:Detail>
                    <ns2:FaultDetail xmlns:ns2="http://eu.domibus.wsplugin/"><code>%s</code><message>%s</message></ns2:FaultDetail>
                  </soap:Detail>
                </soap:Fault>""".formatted(reason, code, message));
    }

    public static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A retrieveMessage response for a message without eb:RefToMessageId. */
    public static String retrieveResponse(String messageId, String fromParty, String toParty, String conversationId,
                                          String base64Payload) {
        return retrieveResponse(messageId, fromParty, toParty, conversationId, null, base64Payload);
    }

    /**
     * @param refToMessageId the eb:RefToMessageId, null for a message without one
     * @param base64Payload  the payload part's value, null for a message without payload
     */
    public static String retrieveResponse(String messageId, String fromParty, String toParty, String conversationId,
                                          String refToMessageId, String base64Payload) {
        return soap("""
                <ns5:Messaging xmlns:ns5="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/">
                  <ns5:UserMessage mpc="http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/defaultMPC">
                    <ns5:MessageInfo>
                      <ns5:Timestamp>2026-10-07T12:30:15.123Z</ns5:Timestamp>
                      <ns5:MessageId>%s</ns5:MessageId>%s
                    </ns5:MessageInfo>
                    <ns5:PartyInfo>
                      <ns5:From>
                        <ns5:PartyId type="urn:oasis:names:tc:ebcore:partyid-type:unregistered">%s</ns5:PartyId>
                        <ns5:Role>http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/initiator</ns5:Role>
                      </ns5:From>
                      <ns5:To>
                        <ns5:PartyId type="urn:oasis:names:tc:ebcore:partyid-type:unregistered">%s</ns5:PartyId>
                        <ns5:Role>http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/responder</ns5:Role>
                      </ns5:To>
                    </ns5:PartyInfo>
                    <ns5:CollaborationInfo>
                      <ns5:Service type="tc1">bdx:noprocess</ns5:Service>
                      <ns5:Action>eftiGateAction</ns5:Action>
                      <ns5:ConversationId>%s</ns5:ConversationId>
                    </ns5:CollaborationInfo>
                  </ns5:UserMessage>
                </ns5:Messaging>""".formatted(messageId,
                refToMessageId == null ? "" : "<ns5:RefToMessageId>" + refToMessageId + "</ns5:RefToMessageId>",
                fromParty, toParty, conversationId), """
                <ns2:retrieveMessageResponse xmlns:ns2="http://eu.domibus.wsplugin/">%s</ns2:retrieveMessageResponse>"""
                .formatted(base64Payload == null ? ""
                        : "<payload payloadId=\"cid:message\" contentType=\"text/xml\"><value>" + base64Payload + "</value></payload>"));
    }

    private static String wsResponse(String element, String content) {
        return soap("", "<ns2:%s xmlns:ns2=\"http://eu.domibus.wsplugin/\">%s</ns2:%s>".formatted(element, content, element));
    }

    private static String text(Document document, String localName) {
        Node node = document.getElementsByTagNameNS("*", localName).item(0);
        return node == null ? "" : node.getTextContent().strip();
    }

    // ---- request handling ----

    private void handle(HttpExchange exchange) throws IOException {
        authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        Reply reply;
        try {
            Document request = SoapMessages.parseResponse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Element body = (Element) request.getElementsByTagNameNS(SoapMessages.SOAP_NS, "Body").item(0);
            String operation = body.getElementsByTagNameNS("*", "*").item(0).getLocalName();
            Optional<String> messageId = Optional.of(text(request, "messageID")).filter(id -> !id.isEmpty());
            reply = Optional.ofNullable(override.apply(new Request(operation, messageId)))
                    .orElseGet(() -> answer(operation, messageId.orElse(""), request));
        } catch (RuntimeException e) {
            reply = new Reply(500, "stub failure: " + e);
        }
        if (reply == NO_RESPONSE) {
            exchange.close();
            return;
        }
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/soap+xml; charset=UTF-8");
        exchange.sendResponseHeaders(reply.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private synchronized Reply answer(String operation, String messageId, Document request) {
        return switch (operation) {
            case "submitRequest" -> {
                String id = "sent-" + (submitted.size() + 1) + "@stub";
                Element to = (Element) request.getElementsByTagNameNS(SoapMessages.EBMS_NS, "To").item(0);
                submitted.add(new Submitted(id, to.getElementsByTagNameNS(SoapMessages.EBMS_NS, "PartyId").item(0).getTextContent(),
                        text(request, "ConversationId"),
                        Optional.of(text(request, "RefToMessageId")).filter(ref -> !ref.isEmpty()).orElse(null),
                        new String(Base64.getDecoder().decode(text(request, "value")), StandardCharsets.UTF_8)));
                yield new Reply(200, wsResponse("submitResponse", "<messageID>" + id + "</messageID>"));
            }
            case "listPendingMessagesRequest" -> new Reply(200, wsResponse("listPendingMessagesResponse",
                    String.join("", pending.keySet().stream().map(id -> "<messageID>" + id + "</messageID>").toList())));
            case "retrieveMessageRequest" -> pending.containsKey(messageId)
                    ? new Reply(200, pending.get(messageId))
                    : new Reply(500, fault("Error retrieving message", "WSPLUGIN:0004", "Message not found"));
            case "markMessageAsDownloadedRequest" -> {
                pending.remove(messageId);
                marked.add(messageId);
                yield new Reply(200, wsResponse("markMessageAsDownloadedResponse", "<messageID>" + messageId + "</messageID>"));
            }
            case "statusRequestWithAccessPointRole" -> new Reply(200, wsResponse("getStatusResponse",
                    submitted.stream().anyMatch(message -> message.as4MessageId().equals(messageId)) ? "ACKNOWLEDGED" : "NOT_FOUND"));
            default -> new Reply(500, fault("Unknown operation", "STUB", operation));
        };
    }
}
