package eu.efti.tst.core;

import java.util.Optional;

/**
 * Format-neutral kind of an eFTI message exchanged between gate and platform. The message names of the
 * test report (FTI004 etc.) are given for reference.
 */
public enum MessageKind {
    /** FTI004, platform to gate. */
    IDENTIFIER_UPLOAD(null),
    /** FTI009, gate to platform. */
    DATA_REQUEST(null),
    /** FTI025, gate to platform. */
    FOLLOW_UP(null),
    /** FTI029, gate to platform, answers an {@link #IDENTIFIER_UPLOAD}. */
    UPLOAD_STATUS(IDENTIFIER_UPLOAD),
    /** FTI010, platform to gate, answers a {@link #DATA_REQUEST}. */
    DATA_RESPONSE(DATA_REQUEST),
    /** FTI030, platform to gate, answers a {@link #FOLLOW_UP}. */
    FOLLOW_UP_ACK(FOLLOW_UP),
    /** Not well-formed or not a message of the format. */
    UNKNOWN(null);

    private final MessageKind answeredKind;

    MessageKind(MessageKind answeredKind) {
        this.answeredKind = answeredKind;
    }

    /** The kind of request this kind of message answers, empty if it is not a response. */
    public Optional<MessageKind> answers() {
        return Optional.ofNullable(answeredKind);
    }
}
