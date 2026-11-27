package eu.efti.tst.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/**
 * Settings under {@code platform.*} in application.yml, overridable by environment variables
 * (e.g. {@code PLATFORM_WS_PLUGIN_URL}). Paths are strings because Spring converts {@code Path} via its resource
 * loader, which rejects relative paths with {@code ..}.
 *
 * @param wsPluginUrl      WS plugin endpoint of the platform's Domibus domain
 * @param wsPluginUser     WS plugin user (selects the Domibus domain)
 * @param wsPluginPassword password of the WS plugin user
 * @param ownPartyId       AS4 party ID of the platform
 * @param gatePartyId      AS4 party ID of the gate that receives the identifier uploads
 * @param formatDir        format folder ({@code formats/<version>}), its test data sets can be loaded into the form
 */
@ConfigurationProperties("platform")
public record PlatformProperties(
        URI wsPluginUrl,
        String wsPluginUser,
        String wsPluginPassword,
        String ownPartyId,
        String gatePartyId,
        String formatDir) {
}
