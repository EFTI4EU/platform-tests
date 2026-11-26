package eu.efti.tst.domibus;

/** Any failure talking to the Domibus WS plugin: transport errors, HTTP errors, SOAP faults, unexpected responses. */
public class DomibusException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean mayHaveBeenDelivered;

    public DomibusException(String message) {
        this(message, null, true);
    }

    public DomibusException(String message, Throwable cause) {
        this(message, cause, true);
    }

    public DomibusException(String message, Throwable cause, boolean mayHaveBeenDelivered) {
        super(message, cause);
        this.mayHaveBeenDelivered = mayHaveBeenDelivered;
    }

    /**
     * Whether Domibus may have processed the request despite the failure, e.g. when the connection broke after the
     * request was sent. False only if the request certainly had no effect (no connection, or rejected with a SOAP
     * fault or an HTTP 4xx status), so that retrying a failed submit cannot send the message twice.
     */
    public boolean mayHaveBeenDelivered() {
        return mayHaveBeenDelivered;
    }
}
