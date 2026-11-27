package eu.efti.tst.platform;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSetTest {

    private static final DataSet DRAFT = new DataSet("id", "<a/>", DataSet.Status.DRAFT);

    @Test
    void followsTheLifecycle() {
        DataSet active = DRAFT.updated("<b/>").signedByCarrier().updated("<c/>");
        DataSet inactive = active.signedByConsignee("<d/>", true);

        assertThat(active).isEqualTo(new DataSet("id", "<c/>", DataSet.Status.ACTIVE));
        assertThat(inactive).isEqualTo(new DataSet("id", "<d/>", DataSet.Status.INACTIVE));
    }

    @Test
    void consigneeSignatureOfANonRoadDataSetMakesItReadyToArchive() {
        assertThat(DRAFT.signedByCarrier().signedByConsignee("<d/>", false))
                .isEqualTo(new DataSet("id", "<d/>", DataSet.Status.READY_TO_ARCHIVE));
    }

    @Test
    void onlyActiveAndInactiveDataSetsAreAvailableOnline() {
        assertThat(Arrays.stream(DataSet.Status.values()).filter(status -> new DataSet("id", "<a/>", status).availableOnline()))
                .containsExactly(DataSet.Status.ACTIVE, DataSet.Status.INACTIVE);
    }

    @Test
    void rejectsActionsOutOfOrder() {
        DataSet active = DRAFT.signedByCarrier();
        DataSet inactive = active.signedByConsignee("<d/>", true);
        DataSet readyToArchive = active.signedByConsignee("<d/>", false);

        assertThatThrownBy(() -> DRAFT.signedByConsignee("<d/>", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(active::signedByCarrier).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> inactive.updated("<e/>")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> inactive.signedByConsignee("<e/>", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> readyToArchive.updated("<e/>")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> readyToArchive.signedByConsignee("<e/>", false)).isInstanceOf(IllegalStateException.class);
    }
}
