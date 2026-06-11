package com.jpassbolt.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for GPG/PGP encryption.
 * Maps to the jpassbolt.gpg section in application.yml
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "jpassbolt.gpg")
public class GpgProperties {

    private ServerKey serverKey = new ServerKey();

    @Data
    public static class ServerKey {
        /**
         * Path to the server's private key file (ASCII armored format)
         * Example: classpath:gpg/server_private.asc
         */
        private String privateLocation;

        /**
         * Path to the server's public key file (ASCII armored format)
         * Example: classpath:gpg/server_public.asc
         */
        private String publicLocation;

        /**
         * Passphrase to unlock the private key
         */
        private String passphrase;
    }
}
