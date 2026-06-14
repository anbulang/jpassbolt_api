package com.jpassbolt.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.MetadataSettingsDto;
import com.jpassbolt.api.model.AuthenticationToken;
import com.jpassbolt.api.model.GpgKey;
import com.jpassbolt.api.model.MetadataKey;
import com.jpassbolt.api.model.MetadataPrivateKey;
import com.jpassbolt.api.model.ResourceType;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.MetadataKeyRepository;
import com.jpassbolt.api.repository.MetadataPrivateKeyRepository;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.ResourceTypeRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.GpgService;
import com.jpassbolt.api.service.MetadataTypesSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seed test data into H2 when running with the "local" profile.
 *
 * <p>
 * Two ACTIVE users with DISTINCT real keypairs so cross-user sharing can be
 * exercised end-to-end in a real browser:
 * <ul>
 *   <li><b>ada@passbolt.com</b> (admin) — holds the committed dev SERVER key
 *       (login with {@code src/main/resources/gpg/server_private.asc} +
 *       passphrase {@code password});</li>
 *   <li><b>betty@passbolt.com</b> (user) — holds the canonical Passbolt test
 *       key (login with the {@code betty_private.key} fixture + passphrase
 *       {@code betty@passbolt.com}); her public key + true fingerprint are
 *       seeded so the client's {@code verifyArmoredKeyFingerprint} passes when
 *       ada shares a secret with her.</li>
 * </ul>
 *
 * <p>
 * It also seeds a v5 cross-user metadata demo: an active shared metadata key
 * (the server keypair is reused as the shared metadata key for the demo) with a
 * per-user encrypted private-key copy for BOTH ada and betty, and flips the
 * organization metadata-types settings to enable v5 resource creation. With this
 * in place the (already shipped) frontend transparent layer creates v5 resources
 * automatically and both users can decrypt the encrypted metadata. The server
 * stays ZERO-KNOWLEDGE: it only stores the armored blobs; all encryption of the
 * per-user copies happens via {@link GpgService#encrypt} (Bouncy Castle).
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
    private final MetadataKeyRepository metadataKeyRepository;
    private final MetadataPrivateKeyRepository metadataPrivateKeyRepository;
    private final MetadataTypesSettingsService metadataTypesSettingsService;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    /** Canonical Passbolt test key for betty@passbolt.com (40-hex fingerprint). */
    private static final String BETTY_FINGERPRINT = "A754860C3ADE5AB04599025ED3F1FE4BE61D7009";

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
        // remains as a keyless directory entry.

        seedResourceTypes();

        // Second ACTIVE user with a DISTINCT real keypair (canonical Passbolt
        // betty test key). Required for cross-user sharing: when ada shares a
        // secret with betty the client re-encrypts it to betty's public key AND
        // verifies betty's armored key against this fingerprint, so the seeded
        // gpgkey MUST be betty's real key with her true fingerprint.
        User betty = new User();
        betty.setUsername("betty@passbolt.com");
        betty.setRoleId(userRole.getId());
        betty.setActive(true);
        betty.setDeleted(false);
        userRepository.save(betty);

        com.jpassbolt.api.model.Profile bettyProfile = new com.jpassbolt.api.model.Profile();
        bettyProfile.setUserId(betty.getId());
        bettyProfile.setFirstName("Betty");
        bettyProfile.setLastName("Holberton");
        profileRepository.save(bettyProfile);

        String bettyPublicKey = readClasspath("classpath:gpg/betty_public.asc");
        GpgKey bettyKey = new GpgKey();
        bettyKey.setUserId(betty.getId());
        bettyKey.setArmoredKey(bettyPublicKey);
        bettyKey.setFingerprint(BETTY_FINGERPRINT);
        bettyKey.setKeyId(BETTY_FINGERPRINT.substring(BETTY_FINGERPRINT.length() - 16));
        bettyKey.setUid("Betty Holberton <betty@passbolt.com>");
        bettyKey.setType("RSA");
        bettyKey.setBits(2048);
        bettyKey.setDeleted(false);
        gpgKeyRepository.save(bettyKey);

        // Pending (not yet activated) user + register token so the /setup flow can
        // still be exercised end-to-end locally (carol is the setup placeholder
        // now that betty is a real active user).
        User pending = new User();
        pending.setUsername("carol@passbolt.com");
        pending.setRoleId(userRole.getId());
        pending.setActive(false);
        pending.setDeleted(false);
        userRepository.save(pending);

        com.jpassbolt.api.model.Profile carolProfile = new com.jpassbolt.api.model.Profile();
        carolProfile.setUserId(pending.getId());
        carolProfile.setFirstName("Carol");
        carolProfile.setLastName("Shaw");
        profileRepository.save(carolProfile);

        AuthenticationToken regToken = new AuthenticationToken();
        regToken.setUserId(pending.getId());
        regToken.setToken("d4c0c497-be4f-47c5-8f50-cb618a4a1d32");
        regToken.setType("register");
        regToken.setActive(true);
        authenticationTokenRepository.save(regToken);

        // v5 cross-user metadata demo (shared metadata key + per-user private
        // copies for ada & betty, settings flipped to enable v5).
        seedV5CrossUserDemo(testUser.getId(), betty.getId(), serverPublicKey,
                fingerprint, bettyPublicKey);

        log.info("=== LOCAL TEST DATA SEEDED ===");
        log.info("ada@passbolt.com (admin) — login with server_private.asc, passphrase: password");
        log.info("betty@passbolt.com (user) — login with betty_private.key, passphrase: betty@passbolt.com");
        log.info("admin@passbolt.com — keyless directory entry");
        log.info("Setup URL: /setup/start/{}/d4c0c497-be4f-47c5-8f50-cb618a4a1d32.json", pending.getId());
        log.info("v5 metadata: enabled (default_resource_types=v5); shared key seeded for ada + betty");
        log.info("==============================");
    }

    /**
     * Seed the v5 cross-user metadata demo.
     *
     * <p>
     * For demo simplicity the SERVER keypair is reused as the shared metadata
     * key: its public half becomes {@code metadata_keys.armored_key}; the
     * cleartext {@code PASSBOLT_METADATA_PRIVATE_KEY} JSON wraps the server
     * PRIVATE key (passphrase {@code password}). That JSON is encrypted ONCE PER
     * USER to their own public key (ada == server key, betty == betty's key) via
     * {@link GpgService#encrypt} and stored in {@code metadata_private_keys.data}.
     * Both users can therefore recover the shared private key in-browser (two-hop
     * decrypt) and read/write v5 metadata. The server never decrypts any of it.
     * </p>
     */
    private void seedV5CrossUserDemo(String adaId, String bettyId, String serverPublicKey,
            String serverFingerprint, String bettyPublicKey) {
        // 1. Active shared metadata key (reuse the server public key for the demo).
        MetadataKey metadataKey = new MetadataKey();
        metadataKey.setFingerprint(serverFingerprint);
        metadataKey.setArmoredKey(serverPublicKey);
        metadataKey.setCreatedBy(adaId);
        metadataKey.setModifiedBy(adaId);
        metadataKeyRepository.save(metadataKey);

        // 2. Cleartext PASSBOLT_METADATA_PRIVATE_KEY blob (the inner armored_key is
        //    the SHARED metadata PRIVATE key = the server private key here).
        String serverPrivateKey = readClasspath("classpath:gpg/server_private.asc");
        Map<String, Object> cleartext = new LinkedHashMap<>();
        cleartext.put("object_type", "PASSBOLT_METADATA_PRIVATE_KEY");
        cleartext.put("domain", "http://localhost:8080");
        cleartext.put("fingerprint", serverFingerprint);
        cleartext.put("armored_key", serverPrivateKey);
        cleartext.put("passphrase", "password");
        String cleartextJson;
        try {
            cleartextJson = objectMapper.writeValueAsString(cleartext);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize the metadata private key blob", e);
        }

        // 3. Per-user encrypted copies (zero-knowledge: encrypt to each user's
        //    public key via Bouncy Castle; the server never keeps the cleartext).
        saveMetadataPrivateKey(metadataKey.getId(), adaId,
                gpgService.encrypt(cleartextJson, serverPublicKey), adaId);
        saveMetadataPrivateKey(metadataKey.getId(), bettyId,
                gpgService.encrypt(cleartextJson, bettyPublicKey), adaId);

        // 4. Flip the organization metadata-types settings to enable v5 (an active
        //    metadata key now exists, satisfying the service's v5 precondition).
        MetadataSettingsDto.TypesSettings v5Settings = MetadataSettingsDto.TypesSettings.builder()
                .defaultResourceTypes(MetadataTypesSettingsService.V5)
                .defaultFolderType(MetadataTypesSettingsService.V4)
                .defaultTagType(MetadataTypesSettingsService.V4)
                .defaultCommentType(MetadataTypesSettingsService.V4)
                .allowCreationOfV5Resources(true)
                .allowCreationOfV5Folders(true)
                .allowCreationOfV5Tags(false)
                .allowCreationOfV5Comments(false)
                .allowCreationOfV4Resources(true)
                .allowCreationOfV4Folders(true)
                .allowCreationOfV4Tags(true)
                .allowCreationOfV4Comments(true)
                .allowV5V4Downgrade(false)
                .allowV4V5Upgrade(true)
                .build();
        metadataTypesSettingsService.setTypesSettings(v5Settings, adaId);
    }

    private void saveMetadataPrivateKey(String metadataKeyId, String userId, String data, String createdBy) {
        MetadataPrivateKey copy = new MetadataPrivateKey();
        copy.setMetadataKeyId(metadataKeyId);
        copy.setUserId(userId);
        copy.setData(data);
        copy.setCreatedBy(createdBy);
        copy.setModifiedBy(createdBy);
        metadataPrivateKeyRepository.save(copy);
    }

    /** Read a classpath resource (e.g. {@code classpath:gpg/betty_public.asc}) as UTF-8. */
    private String readClasspath(String location) {
        try (InputStream in = resourceLoader.getResource(location).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read classpath resource: " + location, e);
        }
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
