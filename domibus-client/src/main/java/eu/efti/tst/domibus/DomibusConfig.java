package eu.efti.tst.domibus;

import java.net.URI;
import java.util.Objects;

/**
 * Connection to one Domibus domain via its WS plugin, plus the AS4 collaboration info used for every
 * submitted message.
 *
 * @param wsPluginUrl WS plugin endpoint, e.g. {@code http://localhost:18080/domibus/services/wsplugin}
 * @param user        WS plugin user; in multi-tenancy it also selects the Domibus domain
 * @param password    password of the WS plugin user
 * @param ownPartyId  AS4 party ID of this domain, used as sender of submitted messages
 * @param partyIdType AS4 party ID type of both sender and recipient
 * @param service     AS4 service value
 * @param serviceType AS4 service type
 * @param action      AS4 action
 */
public record DomibusConfig(
        URI wsPluginUrl,
        String user,
        String password,
        String ownPartyId,
        String partyIdType,
        String service,
        String serviceType,
        String action) {

    // Must match the PMode of the local stack (deploy/setup/pmode-template.xml).
    public static final String EFTI_PARTY_ID_TYPE = "urn:oasis:names:tc:ebcore:partyid-type:unregistered";
    public static final String EFTI_SERVICE = "bdx:noprocess";
    public static final String EFTI_SERVICE_TYPE = "tc1";
    public static final String EFTI_ACTION = "eftiGateAction";

    public DomibusConfig {
        Objects.requireNonNull(wsPluginUrl, "wsPluginUrl");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(ownPartyId, "ownPartyId");
        Objects.requireNonNull(partyIdType, "partyIdType");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(serviceType, "serviceType");
        Objects.requireNonNull(action, "action");
    }

    /** Config with the collaboration info of the eFTI reference implementation PModes. */
    public static DomibusConfig efti(URI wsPluginUrl, String user, String password, String ownPartyId) {
        return new DomibusConfig(wsPluginUrl, user, password, ownPartyId,
                EFTI_PARTY_ID_TYPE, EFTI_SERVICE, EFTI_SERVICE_TYPE, EFTI_ACTION);
    }
}
