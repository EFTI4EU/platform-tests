package eu.efti.tst.core;

/**
 * A violation of a test case observed on one message.
 *
 * @param testCaseId   ID of the violated test case, e.g. {@code T-DAT-02}
 * @param as4MessageId AS4 message ID of the message the finding refers to
 * @param detail       human-readable description of the violation
 */
public record Finding(String testCaseId, String as4MessageId, String detail) {
}
