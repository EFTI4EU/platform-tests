package eu.efti.tst.runner;

import eu.efti.tst.core.MessageRecord;

import java.util.List;

/**
 * The gate's eDelivery connection to the platform under test. Transport errors are thrown as
 * {@link eu.efti.tst.domibus.DomibusException}, whose
 * {@link eu.efti.tst.domibus.DomibusException#mayHaveBeenDelivered()} tells whether a submit may be retried.
 */
interface Transport {

    /**
     * Sends a payload to the platform and returns the record of the sent message.
     *
     * @param refToMessageId AS4 message ID of the platform's message this one answers, null for a request
     */
    MessageRecord submit(String conversationId, String refToMessageId, String payloadXml);

    /** Messages received from the platform that are not marked as downloaded yet; they are returned again until then. */
    List<MessageRecord> fetchNew();

    /** Marks a message returned by {@link #fetchNew} as downloaded, so that it is not returned again. */
    void markDownloaded(String as4MessageId);

    /**
     * AS4 status of a message sent with {@link #submit}, e.g. {@code ACKNOWLEDGED} (or {@code ACKNOWLEDGED_WITH_WARNING})
     * once a receipt was received.
     */
    String status(String as4MessageId);
}
