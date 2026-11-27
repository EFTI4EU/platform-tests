package eu.efti.tst.domibus;

import eu.efti.tst.core.MessageRecord;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static eu.efti.tst.domibus.DomibusStub.base64;
import static eu.efti.tst.domibus.DomibusStub.fault;
import static eu.efti.tst.domibus.DomibusStub.soap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoapMessagesTest {

    private static final DomibusConfig CONFIG = DomibusConfig.efti(
            URI.create("http://localhost:18080/domibus/services/wsplugin"), "tstgate_ws", "test123", "tstgate");

    private static final String PAYLOAD = "<identifierQuery xmlns=\"http://efti.eu/v1/edelivery\">ä &amp; ü</identifierQuery>";

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-07T13:00:00Z");

    private static String retrieveResponse(String messageId, String base64Payload) {
        return DomibusStub.retrieveResponse(messageId, "pseudoplatform", "tstgate", "conv-1", base64Payload);
    }

    private static String retrievedPayload(byte[] payload) {
        return SoapMessages.parseRetrieveResponse(retrieveResponse("msg-1", Base64.getEncoder().encodeToString(payload)),
                MessageRecord.Direction.FROM_PLATFORM, RECEIVED_AT).payload();
    }

    private static String ebmsText(Element scope, String localName) {
        return scope.getElementsByTagNameNS(SoapMessages.EBMS_NS, localName).item(0).getTextContent();
    }

    @Test
    void submitRequestContainsHeaderAndBase64Payload() {
        Document request = SoapMessages.parseResponse(
                SoapMessages.submitRequest(CONFIG, "pseudoplatform", "conv-<1>&", null, PAYLOAD));

        Element userMessage = (Element) request.getElementsByTagNameNS(SoapMessages.EBMS_NS, "UserMessage").item(0);
        Element from = (Element) userMessage.getElementsByTagNameNS(SoapMessages.EBMS_NS, "From").item(0);
        Element to = (Element) userMessage.getElementsByTagNameNS(SoapMessages.EBMS_NS, "To").item(0);
        Element service = (Element) userMessage.getElementsByTagNameNS(SoapMessages.EBMS_NS, "Service").item(0);
        assertThat(ebmsText(from, "PartyId")).isEqualTo("tstgate");
        assertThat(((Element) from.getElementsByTagNameNS(SoapMessages.EBMS_NS, "PartyId").item(0)).getAttribute("type"))
                .isEqualTo(DomibusConfig.EFTI_PARTY_ID_TYPE);
        assertThat(ebmsText(to, "PartyId")).isEqualTo("pseudoplatform");
        assertThat(service.getTextContent()).isEqualTo("bdx:noprocess");
        assertThat(service.getAttribute("type")).isEqualTo("tc1");
        assertThat(ebmsText(userMessage, "Action")).isEqualTo("eftiGateAction");
        assertThat(ebmsText(userMessage, "ConversationId")).isEqualTo("conv-<1>&");
        assertThat(userMessage.getElementsByTagNameNS(SoapMessages.EBMS_NS, "Property").getLength()).isEqualTo(3);
        assertThat(userMessage.getElementsByTagNameNS(SoapMessages.EBMS_NS, "MessageInfo").getLength()).isZero();

        Element submit = (Element) request.getElementsByTagNameNS(SoapMessages.WS_PLUGIN_NS, "submitRequest").item(0);
        Element payload = (Element) submit.getElementsByTagName("payload").item(0);
        assertThat(payload.getAttribute("payloadId")).isEqualTo("cid:message");
        String value = payload.getElementsByTagName("value").item(0).getTextContent();
        assertThat(new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)).isEqualTo(PAYLOAD);
    }

    @Test
    void submitRequestOfAResponseRefersToTheAnsweredMessage() {
        Document request = SoapMessages.parseResponse(
                SoapMessages.submitRequest(CONFIG, "pseudoplatform", "conv-1", "req&1@domibus", PAYLOAD));

        Element messageInfo = (Element) request.getElementsByTagNameNS(SoapMessages.EBMS_NS, "MessageInfo").item(0);
        assertThat(messageInfo.getParentNode().getLocalName()).isEqualTo("UserMessage");
        assertThat(ebmsText(messageInfo, "RefToMessageId")).isEqualTo("req&1@domibus");
    }

    @Test
    void retrieveRequestKeepsMessagePending() {
        Document request = SoapMessages.parseResponse(SoapMessages.retrieveMessageRequest("id&1@domibus"));

        Element retrieve = (Element) request.getElementsByTagNameNS(SoapMessages.WS_PLUGIN_NS, "retrieveMessageRequest").item(0);
        assertThat(retrieve.getAttribute("markAsDownloaded")).isEqualTo("false");
        assertThat(retrieve.getElementsByTagName("messageID").item(0).getTextContent()).isEqualTo("id&1@domibus");
    }

    @Test
    void markAsDownloadedRequestContainsMessageId() {
        Document request = SoapMessages.parseResponse(SoapMessages.markAsDownloadedRequest("id-1@domibus"));

        assertThat(request.getElementsByTagNameNS(SoapMessages.WS_PLUGIN_NS, "markMessageAsDownloadedRequest").getLength()).isEqualTo(1);
        assertThat(request.getElementsByTagName("messageID").item(0).getTextContent()).isEqualTo("id-1@domibus");
    }

    @Test
    void listPendingRequestIsEmptyRequestElement() {
        Document request = SoapMessages.parseResponse(SoapMessages.listPendingMessagesRequest());

        assertThat(request.getElementsByTagNameNS(SoapMessages.WS_PLUGIN_NS, "listPendingMessagesRequest").getLength()).isEqualTo(1);
    }

    @Test
    void parsesSubmitResponse() {
        String response = soap("", """
                <ns2:submitResponse xmlns:ns2="http://eu.domibus.wsplugin/">
                  <messageID>abc-123@domibus.eu</messageID>
                  <messageEntityID>42</messageEntityID>
                </ns2:submitResponse>""");

        assertThat(SoapMessages.parseSubmitResponse(response)).isEqualTo("abc-123@domibus.eu");
    }

    @Test
    void submitResponseWithoutMessageIdFails() {
        String response = soap("", "<ns2:submitResponse xmlns:ns2=\"http://eu.domibus.wsplugin/\"/>");

        assertThatThrownBy(() -> SoapMessages.parseSubmitResponse(response))
                .isInstanceOf(DomibusException.class)
                .hasMessageContaining("no messageID")
                .satisfies(e -> assertThat(((DomibusException) e).mayHaveBeenDelivered()).isTrue());
    }

    @Test
    void submitFaultIsReportedWithCodeAndMessage() {
        String response = fault("Message submission failed", "EBMS:0003",
                "No matching leg found for party [unknown]");

        assertThatThrownBy(() -> SoapMessages.parseSubmitResponse(response))
                .isInstanceOf(DomibusException.class)
                .hasMessage("SOAP fault: Message submission failed [EBMS:0003]: No matching leg found for party [unknown]")
                .satisfies(e -> assertThat(((DomibusException) e).mayHaveBeenDelivered()).isFalse());
    }

    @Test
    void faultWithoutDetailIsReportedWithReason() {
        String response = soap("", """
                <soap:Fault>
                  <soap:Code><soap:Value>soap:Sender</soap:Value></soap:Code>
                  <soap:Reason><soap:Text xml:lang="en">Unauthorized</soap:Text></soap:Reason>
                </soap:Fault>""");

        assertThatThrownBy(() -> SoapMessages.parsePendingMessageIds(response))
                .isInstanceOf(DomibusException.class)
                .hasMessage("SOAP fault: Unauthorized");
    }

    @Test
    void parsesPendingMessageIds() {
        String response = soap("", """
                <ns2:listPendingMessagesResponse xmlns:ns2="http://eu.domibus.wsplugin/">
                  <messageID>id-1</messageID>
                  <messageID>id-2</messageID>
                </ns2:listPendingMessagesResponse>""");

        assertThat(SoapMessages.parsePendingMessageIds(response)).containsExactly("id-1", "id-2");
    }

    @Test
    void parsesEmptyPendingMessageList() {
        String response = soap("", "<ns2:listPendingMessagesResponse xmlns:ns2=\"http://eu.domibus.wsplugin/\"/>");

        assertThat(SoapMessages.parsePendingMessageIds(response)).isEmpty();
    }

    @Test
    void responseOfWrongOperationFails() {
        String response = soap("", "<ns2:submitResponse xmlns:ns2=\"http://eu.domibus.wsplugin/\"/>");

        assertThatThrownBy(() -> SoapMessages.parsePendingMessageIds(response))
                .isInstanceOf(DomibusException.class)
                .hasMessageContaining("listPendingMessagesResponse");
    }

    @Test
    void parsesRetrievedMessageWithTheTimeOfRetrieval() {
        String response = retrieveResponse("msg-1@domibus.eu", base64(PAYLOAD));

        MessageRecord message = SoapMessages.parseRetrieveResponse(response, MessageRecord.Direction.FROM_PLATFORM, RECEIVED_AT);

        assertThat(message).isEqualTo(new MessageRecord("msg-1@domibus.eu", RECEIVED_AT,
                MessageRecord.Direction.FROM_PLATFORM, "pseudoplatform", "tstgate", "conv-1", null, PAYLOAD));
    }

    @Test
    void retrievedResponseRefersToTheAnsweredMessage() {
        String response = DomibusStub.retrieveResponse("msg-1", "pseudoplatform", "tstgate", "conv-1", "req-1@stub", base64(PAYLOAD));

        assertThat(SoapMessages.parseRetrieveResponse(response, MessageRecord.Direction.FROM_PLATFORM, RECEIVED_AT).refToMessageId())
                .isEqualTo("req-1@stub");
    }

    @Test
    void retrievedMessageWithEmptyRefToMessageIdRefersToNothing() {
        String response = DomibusStub.retrieveResponse("msg-1", "pseudoplatform", "tstgate", "conv-1", " ", base64(PAYLOAD));

        assertThat(SoapMessages.parseRetrieveResponse(response, MessageRecord.Direction.FROM_PLATFORM, RECEIVED_AT).refToMessageId())
                .isNull();
    }

    @Test
    void retrievedPayloadMayContainLineBreaks() {
        String wrapped = Base64.getMimeEncoder(8, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(PAYLOAD.getBytes(StandardCharsets.UTF_8));
        String response = retrieveResponse("msg-1", wrapped);

        assertThat(SoapMessages.parseRetrieveResponse(response, MessageRecord.Direction.TO_PLATFORM, RECEIVED_AT).payload())
                .isEqualTo(PAYLOAD);
    }

    @Test
    void retrievedMessageWithoutPayloadHasAnEmptyPayload() {
        MessageRecord message = SoapMessages.parseRetrieveResponse(retrieveResponse("msg-1", null),
                MessageRecord.Direction.FROM_PLATFORM, RECEIVED_AT);

        assertThat(message.as4MessageId()).isEqualTo("msg-1");
        assertThat(message.payload()).isEmpty();
    }

    @Test
    void retrievedPayloadIsDecodedInTheEncodingOfItsXmlDeclaration() {
        String latin1 = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>ä ö ü ß</a>";
        String utf16 = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><a>ä</a>";

        assertThat(retrievedPayload(latin1.getBytes(StandardCharsets.ISO_8859_1))).isEqualTo(latin1);
        assertThat(retrievedPayload(utf16.getBytes(StandardCharsets.UTF_16))).isEqualTo(utf16);
    }

    @Test
    void retrievedPayloadHoldsTheReplacementCharacterForBytesInvalidInItsEncoding() {
        String declaredUtf8 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><a>ä</a>";

        assertThat(retrievedPayload(declaredUtf8.getBytes(StandardCharsets.ISO_8859_1)))
                .isEqualTo(declaredUtf8.replace('ä', '\uFFFD'));
    }

    @Test
    void retrievedPayloadLosesItsUtf8ByteOrderMark() {
        assertThat(retrievedPayload(("\uFEFF" + PAYLOAD).getBytes(StandardCharsets.UTF_8))).isEqualTo(PAYLOAD);
    }

    @Test
    void retrievedPayloadThatIsNoXmlOrNamesAnUnknownEncodingIsReadAsUtf8() {
        String unknownEncoding = "<?xml version=\"1.0\" encoding=\"NO-SUCH-CHARSET\"?><a>ä</a>";

        assertThat(retrievedPayload("not xml: ä".getBytes(StandardCharsets.UTF_8))).isEqualTo("not xml: ä");
        assertThat(retrievedPayload(unknownEncoding.getBytes(StandardCharsets.UTF_8))).isEqualTo(unknownEncoding);
    }

    @Test
    void retrievedMessageWithInvalidBase64Fails() {
        String response = retrieveResponse("msg-1", "not base64!");

        assertThatThrownBy(() -> SoapMessages.parseRetrieveResponse(response, MessageRecord.Direction.FROM_PLATFORM, RECEIVED_AT))
                .isInstanceOf(DomibusException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void retrievedMessageWithoutConversationIdFails() {
        String response = retrieveResponse("msg-1", base64(PAYLOAD))
                .replace("<ns5:ConversationId>conv-1</ns5:ConversationId>", "");

        assertThatThrownBy(() -> SoapMessages.parseRetrieveResponse(response, MessageRecord.Direction.FROM_PLATFORM, RECEIVED_AT))
                .isInstanceOf(DomibusException.class)
                .hasMessageContaining("ConversationId");
    }

    @Test
    void statusRequestAsksForAMessageThisDomainSent() {
        Document request = SoapMessages.parseResponse(SoapMessages.statusRequest("id&1@domibus"));

        Element status = (Element) request.getElementsByTagNameNS(SoapMessages.WS_PLUGIN_NS, "statusRequestWithAccessPointRole").item(0);
        assertThat(status.getElementsByTagName("messageID").item(0).getTextContent()).isEqualTo("id&1@domibus");
        assertThat(status.getElementsByTagName("accessPointRole").item(0).getTextContent()).isEqualTo("SENDING");
    }

    @Test
    void parsesStatusResponse() {
        // as returned by Domibus 5.2.1
        String response = """
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"><soap:Body>\
                <ns2:getStatusResponse xmlns:ns2="http://eu.domibus.wsplugin/">ACKNOWLEDGED</ns2:getStatusResponse>\
                </soap:Body></soap:Envelope>""";

        assertThat(SoapMessages.parseStatusResponse(response)).isEqualTo("ACKNOWLEDGED");
    }

    @Test
    void statusResponseWithoutStatusFails() {
        String response = soap("", "<ns2:getStatusResponse xmlns:ns2=\"http://eu.domibus.wsplugin/\"/>");

        assertThatThrownBy(() -> SoapMessages.parseStatusResponse(response))
                .isInstanceOf(DomibusException.class)
                .hasMessageContaining("no status");
    }

    @Test
    void nonXmlResponseFails() {
        assertThatThrownBy(() -> SoapMessages.parseSubmitResponse("<html>Bad gateway"))
                .isInstanceOf(DomibusException.class)
                .hasMessageContaining("not well-formed");
    }

    @Test
    void responseWithDoctypeIsRejected() {
        String response = "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" + soap("", "<x>&e;</x>");

        assertThatThrownBy(() -> SoapMessages.parseResponse(response)).isInstanceOf(DomibusException.class);
    }
}
