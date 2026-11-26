package eu.efti.tst.platform;

import java.util.List;

/**
 * An eFTI data set managed by the pseudo platform. Lifecycle: DRAFT -(carrier signature)-> ACTIVE
 * -(consignee signature)-> INACTIVE for road transport, READY_TO_ARCHIVE otherwise; the XML can be edited until the
 * consignee signature. The change of road data sets to READY_TO_ARCHIVE after the cabotage period is not simulated.
 *
 * @param id     data set ID (UUID v4), part of the UIL
 * @param xml    the data set as entered by the operator, plus the changes of the signatures
 * @param status lifecycle status
 */
public record DataSet(String id, String xml, Status status) {

    public enum Status { DRAFT, ACTIVE, INACTIVE, READY_TO_ARCHIVE }

    private void requireStatus(String action, List<Status> allowed) {
        if (!allowed.contains(status)) {
            throw new IllegalStateException(action + " is not possible for a data set in status " + status);
        }
    }

    /** Whether competent authorities can retrieve the data set online. */
    public boolean availableOnline() {
        return status == Status.ACTIVE || status == Status.INACTIVE;
    }

    public DataSet signedByCarrier() {
        requireStatus("Carrier signature", List.of(Status.DRAFT));
        return new DataSet(id, xml, Status.ACTIVE);
    }

    public DataSet updated(String newXml) {
        requireStatus("Update", List.of(Status.DRAFT, Status.ACTIVE));
        return new DataSet(id, newXml, status);
    }

    public DataSet signedByConsignee(String signedXml, boolean roadTransport) {
        requireStatus("Consignee signature", List.of(Status.ACTIVE));
        return new DataSet(id, signedXml, roadTransport ? Status.INACTIVE : Status.READY_TO_ARCHIVE);
    }
}
