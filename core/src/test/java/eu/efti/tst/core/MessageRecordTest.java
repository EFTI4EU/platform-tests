package eu.efti.tst.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageRecordTest {

    private static MessageRecord inConversation(String conversationId) {
        return new MessageRecord("msg-1@gate", Fixtures.T0, MessageRecord.Direction.TO_PLATFORM, "gate", "platform",
                conversationId, null, "<x/>");
    }

    @Test
    void testCaseIdIsReadFromATaggedConversationId() {
        assertThat(inConversation(MessageRecord.conversationIdFor("T-NEG-02")).testCaseId()).contains("T-NEG-02");
        assertThat(inConversation(MessageRecord.conversationIdFor("T-RUN-04")).testCaseId()).contains("T-RUN-04");
    }

    @Test
    void conversationIdsOfTheSameTestCaseAreUnique() {
        assertThat(MessageRecord.conversationIdFor("T-RUN-04")).isNotEqualTo(MessageRecord.conversationIdFor("T-RUN-04"));
    }

    @Test
    void untaggedConversationIdsHaveNoTestCase() {
        assertThat(inConversation(null).testCaseId()).isEmpty();
        assertThat(inConversation("").testCaseId()).isEmpty();
        assertThat(inConversation("3f2b9c1e-8d4a-4b6f-9a0e-1c2d3e4f5a6b").testCaseId()).isEmpty();
        assertThat(inConversation("T-RUN-04").testCaseId()).isEmpty();
        assertThat(inConversation("T-RUN-04-").testCaseId()).isEmpty();
        assertThat(inConversation("upload-T-RUN-04-1").testCaseId()).isEmpty();
    }
}
