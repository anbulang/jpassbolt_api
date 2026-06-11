package com.jpassbolt.api.config;

import com.jpassbolt.api.model.GpgKey;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.GpgService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seed test data into H2 when running with the "local" profile.
 * Creates a test user whose GPG key is the server's own key,
 * allowing login with the server private key and passphrase "password".
 */
@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final GpgKeyRepository gpgKeyRepository;
    private final GpgService gpgService;

    @Override
    public void run(String... args) {
        // Create roles
        Role userRole = new Role();
        userRole.setName("user");
        userRole.setDescription("Logged in user");
        roleRepository.save(userRole);

        Role adminRole = new Role();
        adminRole.setName("admin");
        adminRole.setDescription("Organization administrator");
        roleRepository.save(adminRole);

        // Create test user
        User testUser = new User();
        testUser.setUsername("ada@passbolt.com");
        testUser.setRoleId(userRole.getId());
        testUser.setActive(true);
        testUser.setDeleted(false);
        userRepository.save(testUser);

        // Use the server's own public key as the user's GPG key
        // This way, the server can decrypt the auth nonce during GPG login.
        // To log in via the browser, paste the server's PRIVATE key file
        // and use passphrase: "password"
        String serverPublicKey = gpgService.getServerPublicKey();
        String fingerprint = gpgService.getServerKeyFingerprint();
        String keyId = fingerprint.substring(fingerprint.length() - 16);

        GpgKey gpgKey = new GpgKey();
        gpgKey.setUserId(testUser.getId());
        gpgKey.setArmoredKey(serverPublicKey);
        gpgKey.setFingerprint(fingerprint);
        gpgKey.setKeyId(keyId);
        gpgKey.setUid("Ada Lovelace <ada@passbolt.com>");
        gpgKey.setType("RSA");
        gpgKey.setBits(4096);
        gpgKey.setDeleted(false);
        gpgKeyRepository.save(gpgKey);

        log.info("=== LOCAL TEST DATA SEEDED ===");
        log.info("Test user: ada@passbolt.com");
        log.info("GPG fingerprint: {}", fingerprint);
        log.info("Login with the SERVER's private key (src/main/resources/gpg/server_private.asc)");
        log.info("Passphrase: password");
        log.info("==============================");
    }
}
