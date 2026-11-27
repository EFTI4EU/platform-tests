package eu.efti.tst.core;

/**
 * Format-neutral identifiers registered in the gate's registry of identifiers (Art. 11(3) of Impl. Reg. (EU)
 * 2024/1942). How a value is read from a message is defined by the format; values that belong together (e.g. an
 * ID and its registration country) form one value.
 */
public enum IdentifierType {
    /** eFTI618 with registration country eFTI620. */
    TRANSPORT_MEANS,
    /** eFTI374 with registration country eFTI578, category eFTI378 and sequence number eFTI987. */
    TRANSPORT_EQUIPMENT,
    /** eFTI448 with sequence number eFTI1000. */
    CARRIED_EQUIPMENT,
    /** eFTI39. */
    CARRIER_ACCEPTANCE_DATE,
    /** eFTI188. */
    ACTUAL_DELIVERY_DATE,
    /** eFTI581. */
    MODE_OF_TRANSPORT,
    /** eFTI1451. */
    DANGEROUS_GOODS
}
