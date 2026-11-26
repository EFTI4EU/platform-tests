package eu.efti.tst.domibus;

import eu.efti.tst.core.MessageRecord;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** Pure functions building WS plugin SOAP 1.2 requests and parsing their responses. */
final class SoapMessages {

    static final String SOAP_NS = "http://www.w3.org/2003/05/soap-envelope";
    static final String EBMS_NS = "http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/";
    static final String WS_PLUGIN_NS = "http://eu.domibus.wsplugin/";

    // Same values as the eFTI reference gate (edelivery-ap-connector ApConstant).
    private static final String PAYLOAD_ID = "cid:message";
    private static final String PAYLOAD_MIME_TYPE = "text/xml";
    private static final String ORIGINAL_SENDER = "urn:oasis:names:tc:ebcore:partyid-type:unregistered:C1";
    private static final String FINAL_RECIPIENT = "urn:oasis:names:tc:ebcore:partyid-type:unregistered:C4";
    private static final String INITIATOR_ROLE = "http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/initiator";
    private static final String RESPONDER_ROLE = "http://docs.oasis-open.org/ebxml-msg/ebms/v3.0/ns/core/200704/responder";

    private SoapMessages() {
    }

    /** @param refToMessageId AS4 message ID of the message this one answers, null to omit eb:RefToMessageId */
    static String submitRequest(DomibusConfig config, String toParty, String conversationId, String refToMessageId,
                                String payloadXml) {
        String messageInfo = refToMessageId == null ? ""
                : "<eb:MessageInfo><eb:RefToMessageId>%s</eb:RefToMessageId></eb:MessageInfo>".formatted(escape(refToMessageId));
        String header = """
                <eb:Messaging xmlns:eb="%s">
                  <eb:UserMessage>%s
                    <eb:PartyInfo>
                      <eb:From><eb:PartyId type="%s">%s</eb:PartyId><eb:Role>%s</eb:Role></eb:From>
                      <eb:To><eb:PartyId type="%s">%s</eb:PartyId><eb:Role>%s</eb:Role></eb:To>
                    </eb:PartyInfo>
                    <eb:CollaborationInfo>
                      <eb:Service type="%s">%s</eb:Service>
                      <eb:Action>%s</eb:Action>
                      <eb:ConversationId>%s</eb:ConversationId>
                    </eb:CollaborationInfo>
                    <eb:MessageProperties>
                      <eb:Property name="originalSender">%s</eb:Property>
                      <eb:Property name="finalRecipient">%s</eb:Property>
                    </eb:MessageProperties>
                    <eb:PayloadInfo>
                      <eb:PartInfo href="%s">
                        <eb:PartProperties><eb:Property name="MimeType">%s</eb:Property></eb:PartProperties>
                      </eb:PartInfo>
                    </eb:PayloadInfo>
                  </eb:UserMessage>
                </eb:Messaging>""".formatted(
                EBMS_NS,
                messageInfo,
                escape(config.partyIdType()), escape(config.ownPartyId()), INITIATOR_ROLE,
                escape(config.partyIdType()), escape(toParty), RESPONDER_ROLE,
                escape(config.serviceType()), escape(config.service()),
                escape(config.action()),
                escape(conversationId),
                ORIGINAL_SENDER, FINAL_RECIPIENT,
                PAYLOAD_ID, PAYLOAD_MIME_TYPE);
        String body = """
                <ws:submitRequest xmlns:ws="%s">
                  <payload payloadId="%s" contentType="%s"><value>%s</value></payload>
                </ws:submitRequest>""".formatted(WS_PLUGIN_NS, PAYLOAD_ID, PAYLOAD_MIME_TYPE,
                Base64.getEncoder().encodeToString(payloadXml.getBytes(StandardCharsets.UTF_8)));
        return envelope(header, body);
    }

    static String listPendingMessagesRequest() {
        return envelope("", "<ws:listPendingMessagesRequest xmlns:ws=\"%s\"/>".formatted(WS_PLUGIN_NS));
    }

    /** Does not mark the message as downloaded, so it stays pending until {@link #markAsDownloadedRequest}. */
    static String retrieveMessageRequest(String messageId) {
        return envelope("", """
                <ws:retrieveMessageRequest xmlns:ws="%s" markAsDownloaded="false">\
                <messageID>%s</messageID></ws:retrieveMessageRequest>""".formatted(WS_PLUGIN_NS, escape(messageId)));
    }

    /** Status of a message this domain sent (the same ID may also exist as a received message). */
    static String statusRequest(String messageId) {
        return envelope("", """
                <ws:statusRequestWithAccessPointRole xmlns:ws="%s">\
                <messageID>%s</messageID><accessPointRole>SENDING</accessPointRole>\
                </ws:statusRequestWithAccessPointRole>""".formatted(WS_PLUGIN_NS, escape(messageId)));
    }

    static String markAsDownloadedRequest(String messageId) {
        return envelope("", """
                <ws:markMessageAsDownloadedRequest xmlns:ws="%s">\
                <messageID>%s</messageID></ws:markMessageAsDownloadedRequest>""".formatted(WS_PLUGIN_NS, escape(messageId)));
    }

    /** Returns the AS4 message ID assigned by Domibus. */
    static String parseSubmitResponse(String responseXml) {
        List<String> ids = childTexts(parseResponse(responseXml), WS_PLUGIN_NS, "submitResponse", "messageID");
        if (ids.isEmpty()) {
            throw new DomibusException("submitResponse contains no messageID");
        }
        return ids.getFirst();
    }

    static List<String> parsePendingMessageIds(String responseXml) {
        return childTexts(parseResponse(responseXml), WS_PLUGIN_NS, "listPendingMessagesResponse", "messageID");
    }

    /** Fails on SOAP faults; the response content itself is not needed. */
    static void parseMarkAsDownloadedResponse(String responseXml) {
        requiredElement(parseResponse(responseXml), WS_PLUGIN_NS, "markMessageAsDownloadedResponse");
    }

    static String parseStatusResponse(String responseXml) {
        String status = requiredElement(parseResponse(responseXml), WS_PLUGIN_NS, "getStatusResponse").getTextContent().strip();
        if (status.isEmpty()) {
            throw new DomibusException("getStatusResponse contains no status");
        }
        return status;
    }

    /**
     * Maps a retrieveMessage response to a record; a message without payload gets an empty payload, one without
     * (or with an empty) eb:RefToMessageId a null {@code refToMessageId}.
     *
     * @param receivedAt timestamp of the record; the eb:Timestamp of the message is the sender's clock
     */
    static MessageRecord parseRetrieveResponse(String responseXml, MessageRecord.Direction direction, Instant receivedAt) {
        Document document = parseResponse(responseXml);
        Element userMessage = requiredElement(document, EBMS_NS, "UserMessage");
        String payload;
        try {
            payload = decodeXml(Base64.getMimeDecoder().decode(
                    firstText(requiredElement(document, WS_PLUGIN_NS, "retrieveMessageResponse"), "", "value")));
        } catch (IllegalArgumentException e) {
            throw new DomibusException("Payload of retrieved message is not valid base64", e);
        }
        return new MessageRecord(
                requiredText(userMessage, "MessageId"),
                receivedAt,
                direction,
                requiredText(requiredElement(userMessage, EBMS_NS, "From"), "PartyId"),
                requiredText(requiredElement(userMessage, EBMS_NS, "To"), "PartyId"),
                requiredText(userMessage, "ConversationId"),
                Optional.of(firstText(userMessage, EBMS_NS, "RefToMessageId")).filter(id -> !id.isEmpty()).orElse(null),
                payload);
    }

    /**
     * Decodes an XML document in the encoding given by its byte order mark or XML declaration (UTF-8 if it has
     * neither, is no XML or names an unknown encoding), without the byte order mark. Bytes that are invalid in that
     * encoding become the replacement character U+FFFD, which the checks report (see {@link MessageRecord}).
     */
    static String decodeXml(byte[] xml) {
        Charset charset = StandardCharsets.UTF_8;
        try {
            XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            // Creating the reader reads only the XML declaration.
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            charset = Charset.forName(reader.getEncoding());
            reader.close();
        } catch (XMLStreamException | IllegalArgumentException e) {
            // Unknown or missing encoding name: keep UTF-8, the checks report the payload as not well-formed.
        }
        String text = new String(xml, charset);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /**
     * Parses a SOAP response and throws a {@link DomibusException} if it is a SOAP fault. A fault means Domibus
     * rejected the request, so the request had no effect.
     */
    static Document parseResponse(String responseXml) {
        Document document = parse(responseXml);
        NodeList faults = document.getElementsByTagNameNS(SOAP_NS, "Fault");
        if (faults.getLength() > 0) {
            throw new DomibusException("SOAP fault: " + describeFault((Element) faults.item(0)), null, false);
        }
        return document;
    }

    private static String envelope(String header, String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="%s">
                <soap:Header>%s</soap:Header>
                <soap:Body>%s</soap:Body>
                </soap:Envelope>""".formatted(SOAP_NS, header, body);
    }

    private static String describeFault(Element fault) {
        String reason = firstText(fault, SOAP_NS, "Text");
        String code = firstText(fault, "", "code");
        String detail = firstText(fault, "", "message");
        return (reason.isEmpty() ? "(no reason)" : reason)
                + (code.isEmpty() ? "" : " [" + code + "]")
                + (detail.isEmpty() || detail.equals(reason) ? "" : ": " + detail);
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new DomibusException("Response is not well-formed XML: " + abbreviate(xml), e);
        }
    }

    private static List<String> childTexts(Document document, String parentNs, String parentName, String childName) {
        Element parent = requiredElement(document, parentNs, parentName);
        NodeList children = parent.getChildNodes();
        return IntStream.range(0, children.getLength())
                .mapToObj(children::item)
                .filter(node -> node.getNodeType() == Node.ELEMENT_NODE && childName.equals(node.getLocalName()))
                .map(node -> node.getTextContent().strip())
                .filter(text -> !text.isEmpty())
                .toList();
    }

    private static Element requiredElement(Node scope, String ns, String localName) {
        NodeList nodes = scope instanceof Document document
                ? document.getElementsByTagNameNS(ns.isEmpty() ? "*" : ns, localName)
                : ((Element) scope).getElementsByTagNameNS(ns.isEmpty() ? "*" : ns, localName);
        if (nodes.getLength() == 0) {
            throw new DomibusException("Response lacks element " + localName);
        }
        return (Element) nodes.item(0);
    }

    private static String requiredText(Element scope, String ebmsLocalName) {
        String text = requiredElement(scope, EBMS_NS, ebmsLocalName).getTextContent().strip();
        if (text.isEmpty()) {
            throw new DomibusException("Response has empty element " + ebmsLocalName);
        }
        return text;
    }

    private static String firstText(Element scope, String ns, String localName) {
        NodeList nodes = scope.getElementsByTagNameNS(ns.isEmpty() ? "*" : ns, localName);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String abbreviate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }
}
