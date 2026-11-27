package eu.efti.tst.platform;

import eu.efti.tst.domibus.DomibusClient;
import eu.efti.tst.domibus.DomibusConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;

/** A minimal eFTI platform with a web UI, to try out the test runner end to end. */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(PlatformProperties.class)
public class PseudoPlatformApplication {

    @Bean
    PlatformBehaviour platformBehaviour(PlatformProperties properties) {
        return PlatformBehaviour.load(Path.of(properties.formatDir()));
    }

    @Bean
    DomibusClient domibusClient(PlatformProperties properties) {
        return new DomibusClient(DomibusConfig.efti(properties.wsPluginUrl(), properties.wsPluginUser(),
                properties.wsPluginPassword(), properties.ownPartyId()));
    }

    public static void main(String[] args) {
        SpringApplication.run(PseudoPlatformApplication.class, args);
    }
}
