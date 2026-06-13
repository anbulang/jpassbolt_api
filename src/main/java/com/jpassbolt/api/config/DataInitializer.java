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

        // Create test user. Made an ADMIN so the local browser session can
        // exercise the admin-only UI (Users/Groups management). This is the sole
        // user holding the server key, so the GPGAuth login-by-keyid lookup is
        // unambiguous (see the note on admin@passbolt.com below).
        User testUser = new User();
        testUser.setUsername("ada@passbolt.com");
        testUser.setRoleId(adminRole.getId());
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

        // NOTE: admin@passbolt.com intentionally has NO gpgkey. The gpgkeys table
        // is not uniquely constrained on fingerprint, and GPGAuth stage-1 looks up
        // the user BY fingerprint — giving two users the same server key made that
        // lookup return 2 rows ("Query did not return a unique result"), which
        // silently broke browser login. ada@passbolt.com is the single server-key
        // holder (and is an admin), so login is unambiguous. admin@passbolt.com
        // remains as a keyless directory entry. To exercise multi-user sharing
        // locally, seed a second user with a DISTINCT real keypair + its true
        // fingerprint (the client verifies the recipient key against it).

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
        seedV5ResourceTypes();
    }

    /**
     * Seed the 6 v5 resource types (Passbolt v5 metadata system). The v4 index
     * endpoint filters {@link ResourceType#V5_RESOURCE_TYPE_SLUGS} out, so these
     * rows are invisible to v4 clients and safe for v4 contract tests.
     *
     * <p>UUIDs are the canonical UuidFactory::uuid('resource-types.id.&lt;slug&gt;')
     * values (UUIDv5 over PASSBOLT_SEED d5447ca1-950f-459d-8b20-86ddfdd0f922),
     * matching the official seed dump. The v5 JSON-Schema definitions are stored
     * as {@code "[]"} verbatim from the official resource_types dump
     * (docs/ref_files/V1__Initial_Schema_Data_H2.sql) — the real schema bodies
     * live in the client/plugin; the server only stores the definition string.</p>
     */
    private void seedV5ResourceTypes() {
        createResourceType("761e5863-e17e-5ded-b3c2-76ffd5d0a2dc",
                "v5-password-string", "Simple Password (Deprecated)",
                "The original passbolt resource type, kept for backward compatibility reasons.",
                "[]");
        createResourceType("dd1f723d-0d1e-513f-8218-4055dc0530d0",
                "v5-default", "Default resource type",
                "The new default resource type introduced with v5.",
                "[]");
        createResourceType("bb2280b5-c4d9-569c-9337-62b307f1139c",
                "v5-totp-standalone", "Standalone TOTP",
                "The new standalone TOTP resource type introduced with v5.",
                "[]");
        createResourceType("7438294d-f71c-5164-ba95-d9e60e295564",
                "v5-default-with-totp", "Default resource type with TOTP",
                "The new default resource type with a TOTP introduced with v5.",
                "[]");
        createResourceType("0551544e-2ccd-5ce8-95cf-86f0aab0f827",
                "v5-custom-fields", "Standalone custom fields",
                "A resource with standalone custom fields.",
                "[]");
        createResourceType("0a72c76b-b8e6-53f0-8bef-0a8ca6b5c764",
                "v5-note", "Standalone note",
                "A resource with standalone notes.",
                "[]");
        log.info("Seeded 6 v5 resource types");
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
