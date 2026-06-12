package com.jpassbolt.api.config;

import com.jpassbolt.api.model.AuthenticationToken;
import com.jpassbolt.api.model.GpgKey;
import com.jpassbolt.api.model.ResourceType;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.ResourceTypeRepository;
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
    private final ResourceTypeRepository resourceTypeRepository;
    private final ProfileRepository profileRepository;
    private final AuthenticationTokenRepository authenticationTokenRepository;
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

        Role guestRole = new Role();
        guestRole.setName("guest");
        guestRole.setDescription("Non logged in user");
        roleRepository.save(guestRole);

        // Create test user
        User testUser = new User();
        testUser.setUsername("ada@passbolt.com");
        testUser.setRoleId(userRole.getId());
        testUser.setActive(true);
        testUser.setDeleted(false);
        userRepository.save(testUser);

        // Profile rows so /users.json renders profile (the plugin UI shows
        // blank entries otherwise). NOTE: the model class is referenced fully
        // qualified because org.springframework.context.annotation.Profile is
        // already imported for @Profile("local").
        com.jpassbolt.api.model.Profile adaProfile = new com.jpassbolt.api.model.Profile();
        adaProfile.setUserId(testUser.getId());
        adaProfile.setFirstName("Ada");
        adaProfile.setLastName("Lovelace");
        profileRepository.save(adaProfile);

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

        // Create admin test user so GET /healthcheck.json can be exercised locally
        User adminUser = new User();
        adminUser.setUsername("admin@passbolt.com");
        adminUser.setRoleId(adminRole.getId());
        adminUser.setActive(true);
        adminUser.setDeleted(false);
        userRepository.save(adminUser);

        com.jpassbolt.api.model.Profile adminProfile = new com.jpassbolt.api.model.Profile();
        adminProfile.setUserId(adminUser.getId());
        adminProfile.setFirstName("Admin");
        adminProfile.setLastName("User");
        profileRepository.save(adminProfile);

        // Reuse the server's own public key so the admin can log in with the
        // server private key (same pattern as ada@passbolt.com above)
        GpgKey adminGpgKey = new GpgKey();
        adminGpgKey.setUserId(adminUser.getId());
        adminGpgKey.setArmoredKey(serverPublicKey);
        adminGpgKey.setFingerprint(fingerprint);
        adminGpgKey.setKeyId(keyId);
        adminGpgKey.setUid("Admin User <admin@passbolt.com>");
        adminGpgKey.setType("RSA");
        adminGpgKey.setBits(4096);
        adminGpgKey.setDeleted(false);
        gpgKeyRepository.save(adminGpgKey);

        seedResourceTypes();

        log.info("=== LOCAL TEST DATA SEEDED ===");
        log.info("Test user: ada@passbolt.com");
        log.info("Admin user: admin@passbolt.com");
        log.info("GPG fingerprint: {}", fingerprint);
        log.info("Login with the SERVER's private key (src/main/resources/gpg/server_private.asc)");
        log.info("Passphrase: password");
        log.info("==============================");

        // Pending (not yet activated) user + register token so the /setup
        // flow can be exercised end-to-end locally.
        User pending = new User();
        pending.setUsername("betty@passbolt.com");
        pending.setRoleId(userRole.getId());
        pending.setActive(false);
        pending.setDeleted(false);
        userRepository.save(pending);

        com.jpassbolt.api.model.Profile bettyProfile = new com.jpassbolt.api.model.Profile();
        bettyProfile.setUserId(pending.getId());
        bettyProfile.setFirstName("Betty");
        bettyProfile.setLastName("Holberton");
        profileRepository.save(bettyProfile);

        AuthenticationToken regToken = new AuthenticationToken();
        regToken.setUserId(pending.getId());
        regToken.setToken("d4c0c497-be4f-47c5-8f50-cb618a4a1d32");
        regToken.setType("register");
        regToken.setActive(true);
        authenticationTokenRepository.save(regToken);

        log.info("Setup URL: /setup/start/{}/d4c0c497-be4f-47c5-8f50-cb618a4a1d32.json", pending.getId());
    }

    /**
     * Seed the 4 standard v4 resource types. Definition JSON strings are
     * verbatim from docs/ref_files/V1__Initial_Schema_Data_H2.sql (L766).
     * Note: BaseEntity's @GeneratedValue(UUID) may override the manually set
     * fixed UUIDs under Hibernate 6 (save() falls back to merge for non-null
     * ids); random ids are acceptable — the official plugin resolves resource
     * types by slug from the index endpoint at startup.
     */
    private void seedResourceTypes() {
        createResourceType("669f8c64-242a-59fb-92fc-81f660975fd3",
                ResourceType.SLUG_PASSWORD_STRING, "Simple password",
                "The original passbolt resource type, where the secret is a non empty string.",
                """
                {"resource":{"type":"object","required":["name"],"properties":{"name":{"type":"string","maxLength":255},"username":{"anyOf":[{"type":"string","maxLength":255},{"type":"null"}]},"uri":{"anyOf":[{"type":"string","maxLength":1024},{"type":"null"}]},"description":{"anyOf":[{"type":"string","maxLength":10000},{"type":"null"}]}}},"secret":{"type":"string","maxLength":4096}}""");
        createResourceType("a28a04cd-6f53-518a-967c-9963bf9cec51",
                ResourceType.SLUG_PASSWORD_AND_DESCRIPTION, "Password with description",
                "A resource with the password and the description encrypted.",
                """
                {"resource":{"type":"object","required":["name"],"properties":{"name":{"type":"string","maxLength":255},"username":{"anyOf":[{"type":"string","maxLength":255},{"type":"null"}]},"uri":{"anyOf":[{"type":"string","maxLength":1024},{"type":"null"}]}}},"secret":{"type":"object","required":["password"],"properties":{"password":{"type":"string","maxLength":4096},"description":{"anyOf":[{"type":"string","maxLength":10000},{"type":"null"}]}}}}""");
        createResourceType("05ba5c75-504d-5ad6-819a-83af68867d86",
                ResourceType.SLUG_STANDALONE_TOTP, "Standalone TOTP",
                "A resource with standalone TOTP fields.",
                """
                {"resource":{"type":"object","required":["name"],"properties":{"name":{"type":"string","maxLength":255},"uri":{"anyOf":[{"type":"string","maxLength":1024},{"type":"null"}]}}},"secret":{"type":"object","required":["totp"],"properties":{"totp":{"type":"object","required":["secret_key","digits","algorithm"],"properties":{"algorithm":{"type":"string","minLength":4,"maxLength":6},"secret_key":{"type":"string","maxLength":1024},"digits":{"type":"number","minimum":6,"exclusiveMaximum":9},"period":{"type":"number"}}}}}}""");
        createResourceType("8cca88d9-a3f6-56df-b860-3ef08de5c5c4",
                ResourceType.SLUG_PASSWORD_DESCRIPTION_TOTP, "Password, Description and TOTP",
                "A resource with encrypted password, description and TOTP fields.",
                """
                {"resource":{"type":"object","required":["name"],"properties":{"name":{"type":"string","maxLength":255},"username":{"anyOf":[{"type":"string","maxLength":255},{"type":"null"}]},"uri":{"anyOf":[{"type":"string","maxLength":1024},{"type":"null"}]}}},"secret":{"type":"object","required":["password","totp"],"properties":{"password":{"type":"string","maxLength":4096},"description":{"anyOf":[{"type":"string","maxLength":10000},{"type":"null"}]},"totp":{"type":"object","required":["secret_key","digits","algorithm"],"properties":{"algorithm":{"type":"string","minLength":4,"maxLength":6},"secret_key":{"type":"string","maxLength":1024},"digits":{"type":"number","minimum":6,"exclusiveMaximum":9},"period":{"type":"number"}}}}}}""");
        log.info("Seeded 4 v4 resource types");
    }

    private void createResourceType(String id, String slug, String name, String description, String definition) {
        ResourceType resourceType = new ResourceType();
        resourceType.setId(id);
        resourceType.setSlug(slug);
        resourceType.setName(name);
        resourceType.setDescription(description);
        resourceType.setDefinition(definition);
        resourceTypeRepository.save(resourceType);
    }
}
