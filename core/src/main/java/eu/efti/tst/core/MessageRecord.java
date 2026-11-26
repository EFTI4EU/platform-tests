package eu.efti.tst.core;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One AS4 message exchanged between the gate side (test runner or real gate) and the platform, as observed
 * on the wire. This is the only input the conformance checks need, so any gate implementation can feed them.
 *
 * @param as4MessageId   eDelivery message ID assigned by the access point
 * @param timestamp      time the message was sent (outgoing) or received (incoming) by the gate side
 * @param direction      whether the gate sent the message to the platform or received it from the platform
 * @param fromParty      AS4 party ID of the sender
 * @param toParty        AS4 party ID of the recipient
 * @param conversationId AS4 conversation ID
 * @param refToMessageId AS4 RefToMessageId: the AS4 message ID of the message this one answers, null if it carries
 *                       none (e.g. a request). The eFTI technical guidance (Annex 1, ch. 3, Table 14) requires a
 *                       response to refer to the message ID of the request it answers, so responses are paired with
 *                       requests by it (see {@link MessageHistory}).
 * @param payload        business payload (XML) as transmitted, decoded in its own encoding. Bytes that are invalid in
 *                       that encoding must be decoded as the replacement character U+FFFD (the default of
 *                       {@code new String(bytes, charset)}), which T-DAT-01 reports.
 */
public record MessageRecord(
        String as4MessageId,
        Instant timestamp,
        Direction direction,
        String fromParty,
        String toParty,
        String conversationId,
        String refToMessageId,
        String payload) {

    public enum Direction { TO_PLATFORM, FROM_PLATFORM }

    private static final Pattern TAGGED_CONVERSATION_ID = Pattern.compile("(T-[A-Z]+-\\d+)-.+");

    /**
     * A new conversation ID that starts with the test case ID, so that the messages of a test run can be traced to
     * their test case on both sides (the platform's responses stay in the conversation of the request).
     */
    public static String conversationIdFor(String testCaseId) {
        return testCaseId + "-" + UUID.randomUUID();
    }

    /** The test case this message belongs to, if its conversation ID was created by {@link #conversationIdFor}. */
    public Optional<String> testCaseId() {
        return Optional.ofNullable(conversationId).map(TAGGED_CONVERSATION_ID::matcher).filter(Matcher::matches)
                .map(matcher -> matcher.group(1));
    }
}
