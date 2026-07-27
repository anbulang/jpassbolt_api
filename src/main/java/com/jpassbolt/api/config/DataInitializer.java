package com.jpassbolt.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.MetadataSettingsDto;
import com.jpassbolt.api.model.AccountSetting;
import com.jpassbolt.api.model.AuthenticationToken;
import com.jpassbolt.api.model.Comment;
import com.jpassbolt.api.model.Favorite;
import com.jpassbolt.api.model.Folder;
import com.jpassbolt.api.model.FoldersRelation;
import com.jpassbolt.api.model.GpgKey;
import com.jpassbolt.api.model.Group;
import com.jpassbolt.api.model.GroupUser;
import com.jpassbolt.api.model.MetadataKey;
import com.jpassbolt.api.model.MetadataPrivateKey;
import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.ResourceType;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AccountSettingRepository;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.CommentRepository;
import com.jpassbolt.api.repository.FavoriteRepository;
import com.jpassbolt.api.repository.FolderRepository;
import com.jpassbolt.api.repository.FoldersRelationRepository;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.GroupRepository;
import com.jpassbolt.api.repository.GroupUserRepository;
import com.jpassbolt.api.repository.MetadataKeyRepository;
import com.jpassbolt.api.repository.MetadataPrivateKeyRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.ResourceTypeRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.FolderService;
import com.jpassbolt.api.service.GpgKeyParserService;
import com.jpassbolt.api.service.GpgService;
import com.jpassbolt.api.service.MetadataTypesSettingsService;
import com.jpassbolt.api.service.MfaService;
import com.jpassbolt.api.service.SelfRegistrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Seed test data into H2 when running with the "local" profile.
 *
 * <p>
 * Two ACTIVE users with DISTINCT real keypairs so cross-user sharing can be
 * exercised end-to-end in a real browser:
 * <ul>
 *   <li><b>ada@passbolt.com</b> (admin) — holds her OWN dev keypair, distinct
 *       from the server identity key (login with
 *       {@code src/main/resources/gpg/ada_private.asc} + passphrase
 *       {@code password});</li>
 *   <li><b>betty@passbolt.com</b> (user) — holds the canonical Passbolt test
 *       key (login with the {@code betty_private.key} fixture + passphrase
 *       {@code betty@passbolt.com}); her public key + true fingerprint are
 *       seeded so the client's {@code verifyArmoredKeyFingerprint} passes when
 *       ada shares a secret with her.</li>
 * </ul>
 *
 * <p>
 * It also seeds a v5 cross-user metadata demo: an active shared metadata key
 * (a DEDICATED demo metadata keypair, distinct from the server and user keys)
 * with a per-user encrypted private-key copy for BOTH ada and betty, and flips the
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
    private final GpgKeyParserService gpgKeyParserService;
    private final GroupRepository groupRepository;
    private final GroupUserRepository groupUserRepository;
    private final FolderRepository folderRepository;
    private final FoldersRelationRepository foldersRelationRepository;
    private final ResourceRepository resourceRepository;
    private final PermissionRepository permissionRepository;
    private final SecretRepository secretRepository;
    private final CommentRepository commentRepository;
    private final FavoriteRepository favoriteRepository;
    private final OrganizationSettingRepository organizationSettingRepository;
    private final AccountSettingRepository accountSettingRepository;
    private final SettingsProperties settingsProperties;
    private final javax.sql.DataSource dataSource;

    /** Canonical Passbolt test key for betty@passbolt.com (40-hex fingerprint). */
    private static final String BETTY_FINGERPRINT = "A754860C3ADE5AB04599025ED3F1FE4BE61D7009";

    /**
     * Flip to true to also seed an MFA (TOTP) demo for dame@passbolt.com.
     * OFF by default on purpose: with it on, every dame login would require a
     * TOTP code, which gets in the way of day-to-day local debugging.
     */
    private static final boolean SEED_MFA_DEMO = false;

    /**
     * Fixed TOTP shared secret for the (opt-in) MFA demo — base32 of
     * "Hello!\xDE\xAD\xBE\xEF", the classic otplib/GoogleAuth test vector.
     */
    private static final String MFA_DEMO_TOTP_SECRET = "JBSWY3DPEHPK3PXP";

    /** Fixed register token for the ruth@passbolt.com inactive-user demo. */
    private static final String RUTH_REGISTER_TOKEN = "8b6c9678-2222-4d5b-89c6-4d3f0a6d90aa";

    @Override
    public void run(String... args) {
        // SAFETY GUARD — seed ONLY against an embedded H2 database.
        //
        // @Profile("local") activates this initializer whenever `local` is in the
        // active set, but the effective datasource is NOT implied by that: with
        // SPRING_PROFILES_ACTIVE=local,mysql the later `mysql` profile selects a
        // real (possibly remote) database while `local` still switches this bean
        // on. Seeding then writes the ada@passbolt.com ADMIN account — whose
        // private key + passphrase ("password") are committed to this public
        // repo — into that real database, handing an admin login to anyone with
        // the repository.
        //
        // So the safety property ("committed dev keys cannot reach a real
        // environment") is enforced here by construction, not merely by the
        // profile name: if the live connection is not embedded H2, seeding is
        // skipped loudly. See src/main/resources/gpg/README.md.
        String dbUrl;
        String dbProduct;
        try (java.sql.Connection c = dataSource.getConnection()) {
            dbUrl = c.getMetaData().getURL();
            dbProduct = c.getMetaData().getDatabaseProductName();
        } catch (java.sql.SQLException e) {
            log.error("[seed] could not inspect the datasource; refusing to seed dev fixtures", e);
            return;
        }
        // Allowlist genuine EMBEDDED H2 only. A bare "jdbc:h2:" prefix is NOT
        // enough: H2 also speaks a networked server protocol — jdbc:h2:tcp: and
        // jdbc:h2:ssl: point at a REMOTE H2 that is every bit as real as MySQL,
        // so a prefix-only check would let the guard pass for a shared database
        // and re-open exactly the hole it exists to close. Default-deny: require
        // the driver to actually be H2 AND the URL to be an in-memory or local
        // file database (case-insensitive), which rejects tcp:/ssl: and any
        // future non-embedded URL variant.
        if (!isEmbeddedH2(dbUrl, dbProduct)) {
            log.warn("[seed] datasource is not embedded H2 (url={}, product={}) — SKIPPING dev fixture "
                    + "seeding. The committed dev keys (ada@passbolt.com admin, passphrase 'password') must "
                    + "never reach a real database; this guard fires under SPRING_PROFILES_ACTIVE=local,mysql "
                    + "and for networked H2 (jdbc:h2:tcp:/ssl:).",
                    dbUrl, dbProduct);
            return;
        }

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

        // ada 使用一对【独立的】开发用户钥(ada_public.asc / ada_private.asc),
        // 与服务器身份钥彻底分离。服务器钥只负责 GpgAuth 服务端身份(stage0 用它
        // 自己解 nonce),不再充当任何用户/元数据钥——这样在本机以 ada 登录后导出
        // 备份得到的是 ada 自己的私钥,而不是服务器私钥。
        // 浏览器/E2E 登录 ada:导入 src/main/resources/gpg/ada_private.asc,passphrase:"password"。
        String adaPublicKey = readClasspath("classpath:gpg/ada_public.asc");
        GpgKeyParserService.GpgKeyMetadata adaMeta = gpgKeyParserService.parse(adaPublicKey);

        GpgKey gpgKey = new GpgKey();
        gpgKey.setUserId(testUser.getId());
        gpgKey.setArmoredKey(adaPublicKey);
        gpgKey.setFingerprint(adaMeta.getFingerprint());
        gpgKey.setKeyId(adaMeta.getKeyId());
        gpgKey.setUid(adaMeta.getUid());
        gpgKey.setType(adaMeta.getType());
        gpgKey.setBits(adaMeta.getBits());
        gpgKey.setKeyCreated(adaMeta.getKeyCreated());
        gpgKey.setExpires(adaMeta.getExpires());
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
        // the user BY fingerprint — giving two users the same key made that lookup
        // return 2 rows ("Query did not return a unique result"), which silently
        // broke browser login. Every seeded user now holds a DISTINCT keypair
        // (ada = ada_*.asc, betty = betty key, …), so no key is shared. admin@passbolt.com
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

        // v5 cross-user metadata demo (独立的演示元数据钥 + per-user private
        // copies for ada & betty, settings flipped to enable v5).
        seedV5CrossUserDemo(testUser.getId(), betty.getId(), adaPublicKey, bettyPublicKey);

        log.info("=== LOCAL TEST DATA SEEDED ===");
        log.info("ada@passbolt.com (admin) — login with ada_private.asc, passphrase: password");
        log.info("betty@passbolt.com (user) — login with betty_private.key, passphrase: betty@passbolt.com");
        log.info("admin@passbolt.com — keyless directory entry");
        log.info("Setup URL: /setup/start/{}/d4c0c497-be4f-47c5-8f50-cb618a4a1d32.json", pending.getId());
        log.info("v5 metadata: enabled (default_resource_types=v5); shared key seeded for ada + betty");
        log.info("==============================");

        seedFeatureCoverageDemo();
    }

    /**
     * Seed extra demo data covering the features the base seed leaves empty:
     * user lifecycle states (active/inactive/soft-deleted/disabled), a group,
     * folders, v4 resources shared directly and through a group, a comment, a
     * favorite, and the self-registration organization setting.
     *
     * <p>
     * Third loginable account: <b>dame@passbolt.com</b> holds the official
     * Passbolt fixture keypair. Private keys are NOT committed — fetch them
     * from {@code passbolt_api_ref/plugins/PassboltDev/TestData/config/gpg/}
     * ({@code dame_private.key}, {@code edith_private.key}); the fixture
     * passphrase convention is the user's email address (e.g.
     * {@code dame@passbolt.com}). Only the public halves are bundled under
     * {@code src/main/resources/gpg/fixtures/} (as {@code .asc} — the
     * repository .gitignore blocks {@code *.key} on purpose, and only public
     * keys may be committed). The server key is NOT reused
     * for any of these users (a duplicated fingerprint once broke the GPGAuth
     * stage-1 lookup — see the admin@passbolt.com note above).
     * </p>
     *
     * <p>
     * Idempotent: each block checks for its own marker row (username, group
     * name, resource name, setting property) and skips when present, so the
     * method is safe against a database that already carries the demo data.
     * All secrets are encrypted at startup via {@link GpgService#encrypt}
     * (Bouncy Castle) — the server only ever stores ciphertext.
     * </p>
     */
    /**
     * True only for a genuine EMBEDDED H2 database (in-memory or local file).
     *
     * <p>
     * Package-private + static so it can be unit-tested directly. The threat it
     * guards against is seeding committed dev credentials into a real database;
     * H2's networked modes ({@code jdbc:h2:tcp:} / {@code jdbc:h2:ssl:}) point at
     * a remote server that is exactly such a database, so a bare
     * {@code jdbc:h2:} prefix is insufficient. Default-deny: the JDBC product
     * must be H2 AND the URL must name an in-memory or file database.
     * </p>
     */
    static boolean isEmbeddedH2(String url, String product) {
        if (!"H2".equalsIgnoreCase(product)) {
            return false;
        }
        String u = url == null ? "" : url.trim().toLowerCase(java.util.Locale.ROOT);
        return u.startsWith("jdbc:h2:mem:") || u.startsWith("jdbc:h2:file:");
    }

    private void seedFeatureCoverageDemo() {
        Role userRole = roleRepository.findByName("user").orElseThrow();
        User ada = userRepository.findByUsername("ada@passbolt.com").orElseThrow();
        User betty = userRepository.findByUsername("betty@passbolt.com").orElseThrow();
        String adaId = ada.getId();

        // ① dame — second loginable regular user with a DISTINCT real keypair
        // (fingerprint parsed from the bundled fixture public key).
        User dame = userRepository.findByUsername("dame@passbolt.com").orElse(null);
        if (dame == null) {
            dame = createUserWithProfile("dame@passbolt.com", userRole.getId(), true,
                    "Dame Steve", "Shirley");
            seedGpgKeyFromClasspath(dame.getId(), "classpath:gpg/fixtures/dame_public.asc");
            log.info("[demo] dame@passbolt.com seeded (user, loginable) — verify: cross-user share to a non-admin third account");
        }

        // ①.5 anbulang1@gmail.com — real-email account-recovery E2E test user.
        // Active + loginable, holds the DISTINCT frances test keypair (fingerprint
        // parsed from the bundled fixture public key, so it never collides with
        // ada/betty/dame). To drive the full four-state flow: import
        // frances_private.key (passbolt_api_ref/.../TestData/config/gpg/) with
        // passphrase "frances@passbolt.com" into the extension, then request a
        // recovery for this email — with SMTP configured, the link is delivered
        // as real mail.
        if (userRepository.findByUsername("anbulang1@gmail.com").isEmpty()) {
            // The frances fixture key may already belong to another account
            // (e.g. someone completed setup with it). Seeding it anyway would
            // create the one-key-two-users ambiguity, so the whole demo user
            // is skipped — an account without a key would be unusable anyway.
            String francesFingerprint = gpgKeyParserService
                    .parse(readClasspath("classpath:gpg/fixtures/frances_public.asc"))
                    .getFingerprint();
            if (!gpgKeyRepository.findAllByFingerprint(francesFingerprint).isEmpty()) {
                log.warn("[demo] skip anbulang1@gmail.com: frances fixture fingerprint {} "
                        + "is already registered to an existing account", francesFingerprint);
            } else {
                User anbulang = createUserWithProfile("anbulang1@gmail.com", userRole.getId(), true,
                        "Anbulang", "Tester");
                seedGpgKeyFromClasspath(anbulang.getId(), "classpath:gpg/fixtures/frances_public.asc");
                log.info("[demo] anbulang1@gmail.com seeded (active, loginable) — recovery E2E: import frances_private.key, passphrase frances@passbolt.com");
            }
        }

        // ② ruth — inactive (setup not finished) + register token: verify the
        // inactive-login rejection and the "finish setup" guidance.
        if (userRepository.findByUsername("ruth@passbolt.com").isEmpty()) {
            User ruth = createUserWithProfile("ruth@passbolt.com", userRole.getId(), false,
                    "Ruth", "Teitelbaum");
            AuthenticationToken ruthToken = new AuthenticationToken();
            ruthToken.setUserId(ruth.getId());
            ruthToken.setToken(RUTH_REGISTER_TOKEN);
            ruthToken.setType("register");
            ruthToken.setActive(true);
            authenticationTokenRepository.save(ruthToken);
            log.info("[demo] ruth@passbolt.com seeded (inactive + register token) — verify: inactive login rejection; setup URL: /setup/start/{}/{}.json",
                    ruth.getId(), RUTH_REGISTER_TOKEN);
        }

        // ③ sofia — soft-deleted: verify deleted users are filtered everywhere.
        if (userRepository.findByUsername("sofia@passbolt.com").isEmpty()) {
            User sofia = createUserWithProfile("sofia@passbolt.com", userRole.getId(), true,
                    "Sofia", "Kovalevskaya");
            sofia.setDeleted(true);
            userRepository.save(sofia);
            log.info("[demo] sofia@passbolt.com seeded (soft-deleted) — verify: deleted-user filtering in /users.json and shares");
        }

        // ④ edith — active but disabled (timestamped): verify the Users page
        // disabled chip and the re-enable action (disabled:null round-trip).
        if (userRepository.findByUsername("edith@passbolt.com").isEmpty()) {
            User edith = createUserWithProfile("edith@passbolt.com", userRole.getId(), true,
                    "Edith", "Clarke");
            edith.setDisabled(LocalDateTime.now(ZoneOffset.UTC));
            userRepository.save(edith);
            seedGpgKeyFromClasspath(edith.getId(), "classpath:gpg/fixtures/edith_public.asc");
            log.info("[demo] edith@passbolt.com seeded (disabled) — verify: disabled chip + re-enable in the Users page");
        }

        // ⑤ group "Board": ada is group manager, betty + dame are members.
        Group board = groupRepository.findByDeletedFalse().stream()
                .filter(g -> "Board".equals(g.getName()))
                .findFirst()
                .orElse(null);
        if (board == null) {
            board = new Group();
            board.setName("Board");
            board.setDeleted(false);
            board.setCreatedBy(adaId);
            board.setModifiedBy(adaId);
            groupRepository.save(board);
            saveGroupUser(board.getId(), adaId, true);
            saveGroupUser(board.getId(), betty.getId(), false);
            saveGroupUser(board.getId(), dame.getId(), false);
            log.info("[demo] group 'Board' seeded (manager: ada; members: betty, dame) — verify: group CRUD + membership rendering");
        }

        // ⑥ two-level folder tree for ada: "Ops" > "Servers" (folder rows +
        // OWNER permissions + ada's folders_relations, same shape as
        // FolderService.createFolder).
        Folder ops = folderRepository.findAll().stream()
                .filter(f -> "Ops".equals(f.getName()))
                .findFirst()
                .orElse(null);
        Folder servers;
        if (ops == null) {
            ops = saveFolder("Ops", adaId, null);
            servers = saveFolder("Servers", adaId, ops.getId());
            log.info("[demo] folders 'Ops' > 'Servers' seeded for ada — verify: folder tree + move endpoints");
        } else {
            servers = folderRepository.findAll().stream()
                    .filter(f -> "Servers".equals(f.getName()))
                    .findFirst()
                    .orElse(ops);
        }

        // ⑦ v4 resources with per-user secrets (encrypted at startup; RSA-4096
        // encryption can take seconds, hence the timing log).
        if (resourceRepository.findByDeletedFalse().stream()
                .noneMatch(r -> "Demo Shared Login".equals(r.getName()))) {
            long start = System.currentTimeMillis();
            String typeId = resourceTypeRepository.findBySlug(ResourceType.SLUG_PASSWORD_AND_DESCRIPTION)
                    .orElseThrow()
                    .getId();
            // ada 现在持有独立开发钥:密文必须加密到 ada 自己的公钥,否则她用
            // ada_private.asc 登录后无法解出这些演示资源的密码。
            String adaPublicKey = readClasspath("classpath:gpg/ada_public.asc");
            String bettyPublicKey = readClasspath("classpath:gpg/betty_public.asc");
            String damePublicKey = readClasspath("classpath:gpg/fixtures/dame_public.asc");

            // resource 1: ada OWNER, shared READ with betty directly.
            Resource shared = saveResource("Demo Shared Login", "demo",
                    "https://shared.demo.jpassbolt.local", typeId, adaId);
            savePermission(Permission.RESOURCE_ACO, shared.getId(), Permission.USER_ARO, adaId, Permission.OWNER);
            savePermission(Permission.RESOURCE_ACO, shared.getId(), Permission.USER_ARO, betty.getId(),
                    Permission.READ);
            String sharedCleartext = secretCleartext("demo-shared-password", "Shared ada -> betty (READ)");
            saveSecret(shared.getId(), adaId, gpgService.encrypt(sharedCleartext, adaPublicKey));
            saveSecret(shared.getId(), betty.getId(), gpgService.encrypt(sharedCleartext, bettyPublicKey));
            // Every user who can SEE a resource must have a folders_relations row in
            // their own tree (root = null parent) — the same invariant the real API
            // upholds (ResourceService.createResource for the creator, PermissionService
            // for share recipients). Without it the move endpoint 404s
            // ("The object to move does not exist"). Seed both viewers here.
            saveFoldersRelation(FoldersRelation.FOREIGN_MODEL_RESOURCE, shared.getId(), adaId, null);
            saveFoldersRelation(FoldersRelation.FOREIGN_MODEL_RESOURCE, shared.getId(), betty.getId(), null);

            // resource 2: ada OWNER, shared UPDATE with the Board group — every
            // member (ada, betty, dame) gets their own encrypted secret copy.
            Resource wiki = saveResource("Board Wiki", "board",
                    "https://wiki.board.jpassbolt.local", typeId, adaId);
            savePermission(Permission.RESOURCE_ACO, wiki.getId(), Permission.USER_ARO, adaId, Permission.OWNER);
            savePermission(Permission.RESOURCE_ACO, wiki.getId(), Permission.GROUP_ARO, board.getId(),
                    Permission.UPDATE);
            String wikiCleartext = secretCleartext("board-wiki-password", "Shared through group Board");
            saveSecret(wiki.getId(), adaId, gpgService.encrypt(wikiCleartext, adaPublicKey));
            saveSecret(wiki.getId(), betty.getId(), gpgService.encrypt(wikiCleartext, bettyPublicKey));
            saveSecret(wiki.getId(), dame.getId(), gpgService.encrypt(wikiCleartext, damePublicKey));
            // Root folders_relations row for all three group viewers (see note above).
            saveFoldersRelation(FoldersRelation.FOREIGN_MODEL_RESOURCE, wiki.getId(), adaId, null);
            saveFoldersRelation(FoldersRelation.FOREIGN_MODEL_RESOURCE, wiki.getId(), betty.getId(), null);
            saveFoldersRelation(FoldersRelation.FOREIGN_MODEL_RESOURCE, wiki.getId(), dame.getId(), null);

            // resource 3: ada-only, filed under Ops > Servers in ada's tree.
            Resource rootPwd = saveResource("Server Root", "root",
                    "ssh://root.demo.jpassbolt.local", typeId, adaId);
            savePermission(Permission.RESOURCE_ACO, rootPwd.getId(), Permission.USER_ARO, adaId, Permission.OWNER);
            saveSecret(rootPwd.getId(), adaId,
                    gpgService.encrypt(secretCleartext("server-root-password", "Personal, inside a folder"), adaPublicKey));
            saveFoldersRelation(FoldersRelation.FOREIGN_MODEL_RESOURCE, rootPwd.getId(), adaId, servers.getId());

            // ⑧ a comment and a favorite on resource 1, both by ada.
            Comment comment = new Comment();
            comment.setForeignKey(shared.getId());
            comment.setForeignModel(Comment.RESOURCE_FOREIGN_MODEL);
            comment.setContent("Demo comment: rotated after the last audit.");
            comment.setUserId(adaId);
            comment.setCreatedBy(adaId);
            comment.setModifiedBy(adaId);
            commentRepository.save(comment);
            favoriteRepository.save(new Favorite(adaId, shared.getId(), Favorite.FOREIGN_MODEL_RESOURCE));

            log.info("[demo] 3 v4 resources + {} secrets seeded in {} ms — verify: direct share, group share, foldered resource, comment, favorite",
                    6, System.currentTimeMillis() - start);
        }

        // ⑨ self-registration policy (organization_settings row, same stored
        // shape as SelfRegistrationService.save: the service reads by property).
        if (organizationSettingRepository.findByProperty(SelfRegistrationService.ORG_SETTING_PROPERTY).isEmpty()) {
            Map<String, Object> selfRegValue = new LinkedHashMap<>();
            selfRegValue.put("provider", SelfRegistrationService.PROVIDER_EMAIL_DOMAINS);
            selfRegValue.put("data", Map.of("allowed_domains", List.of("passbolt.com")));
            saveOrganizationSetting(SelfRegistrationService.ORG_SETTING_PROPERTY,
                    "organization.setting.selfRegistration", writeJson(selfRegValue), adaId);
            log.info("[demo] selfRegistration setting seeded (allowed domain: passbolt.com) — verify: /self-registration/* + guest register gate");
        }

        // ⑩ opt-in MFA (TOTP) demo for dame — see SEED_MFA_DEMO.
        if (SEED_MFA_DEMO && accountSettingRepository
                .findByUserIdInAndProperty(List.of(dame.getId()), MfaService.MFA_PROPERTY).isEmpty()) {
            Map<String, Object> orgMfa = Map.of("providers", List.of(MfaService.PROVIDER_TOTP));
            saveOrganizationSetting(MfaService.MFA_PROPERTY, "organization.setting.mfa",
                    writeJson(orgMfa), adaId);

            // Issuer derives from the configured public origin (scheme stripped,
            // ':' dropped — it is the otpauth label separator), e.g. localhost8090.
            String totpIssuer = settingsProperties.getFullBaseUrl()
                    .replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "")
                    .replaceAll("[^A-Za-z0-9.\\-]", "");
            String provisioningUri = "otpauth://totp/" + totpIssuer + ":dame%40passbolt.com"
                    + "?issuer=" + totpIssuer + "&secret=" + MFA_DEMO_TOTP_SECRET;
            Map<String, Object> accountMfa = new LinkedHashMap<>();
            accountMfa.put("providers", List.of(MfaService.PROVIDER_TOTP));
            accountMfa.put("totp", Map.of(
                    "otpProvisioningUri", provisioningUri,
                    "verified", LocalDateTime.now(ZoneOffset.UTC).toString()));
            AccountSetting mfaSetting = new AccountSetting();
            mfaSetting.setUserId(dame.getId());
            mfaSetting.setProperty(MfaService.MFA_PROPERTY);
            mfaSetting.setPropertyId(deterministicUuid("account.setting.mfa"));
            mfaSetting.setValue(writeJson(accountMfa));
            accountSettingRepository.save(mfaSetting);
            log.info("[demo] MFA TOTP seeded for dame (secret: {}) — verify: MFA challenge on dame login", MFA_DEMO_TOTP_SECRET);
        }

        log.info("[demo] feature-coverage demo data ready (dame login: dame_private.key from passbolt_api_ref TestData, passphrase: dame@passbolt.com)");
    }

    private User createUserWithProfile(String username, String roleId, boolean active,
            String firstName, String lastName) {
        User user = new User();
        user.setUsername(username);
        user.setRoleId(roleId);
        user.setActive(active);
        user.setDeleted(false);
        userRepository.save(user);

        com.jpassbolt.api.model.Profile profile = new com.jpassbolt.api.model.Profile();
        profile.setUserId(user.getId());
        profile.setFirstName(firstName);
        profile.setLastName(lastName);
        profileRepository.save(profile);
        return user;
    }

    /**
     * Seed a gpgkeys row from a bundled fixture public key; fingerprint/uid/
     * bits/type are parsed from the armored key itself (never hardcoded, and
     * never the server key — see the fingerprint-ambiguity note above).
     */
    private void seedGpgKeyFromClasspath(String userId, String location) {
        String armoredKey = readClasspath(location);
        GpgKeyParserService.GpgKeyMetadata metadata = gpgKeyParserService.parse(armoredKey);
        // Fingerprint uniqueness is an application-level invariant (PHP
        // GpgkeysTable isUnique(['fingerprint']); the official schema has no
        // DB unique index, so EVERY write path must guard it). SetupService
        // guards the API path; this guards the seeding path. Without it, a
        // fixture key already claimed by some account would be seeded onto a
        // second one and a single private key could then log in as two users.
        List<GpgKey> existing = gpgKeyRepository.findAllByFingerprint(metadata.getFingerprint());
        if (!existing.isEmpty()) {
            boolean ownedBySomeoneElse = existing.stream()
                    .anyMatch(k -> !userId.equals(k.getUserId()));
            if (ownedBySomeoneElse) {
                log.error("[seed] REFUSING to seed {} for user {}: fingerprint {} already "
                        + "belongs to another account — one key must never map to two users",
                        location, userId, metadata.getFingerprint());
            }
            return; // same-user rows only: idempotent re-run, nothing to do
        }
        GpgKey key = new GpgKey();
        key.setUserId(userId);
        key.setArmoredKey(armoredKey);
        key.setFingerprint(metadata.getFingerprint());
        key.setKeyId(metadata.getKeyId());
        key.setUid(metadata.getUid());
        key.setType(metadata.getType());
        key.setBits(metadata.getBits());
        key.setKeyCreated(metadata.getKeyCreated());
        key.setExpires(metadata.getExpires());
        key.setDeleted(false);
        gpgKeyRepository.save(key);
    }

    private void saveGroupUser(String groupId, String userId, boolean isAdmin) {
        GroupUser membership = new GroupUser();
        membership.setGroupId(groupId);
        membership.setUserId(userId);
        membership.setIsAdmin(isAdmin);
        groupUserRepository.save(membership);
    }

    /** Folder row + OWNER permission + the owner's relation (FolderService shape). */
    private Folder saveFolder(String name, String ownerId, String parentFolderId) {
        Folder folder = new Folder();
        folder.setName(name);
        folder.setCreatedBy(ownerId);
        folder.setModifiedBy(ownerId);
        folderRepository.save(folder);
        savePermission(FolderService.FOLDER_ACO, folder.getId(), Permission.USER_ARO, ownerId, Permission.OWNER);
        saveFoldersRelation(FoldersRelation.FOREIGN_MODEL_FOLDER, folder.getId(), ownerId, parentFolderId);
        return folder;
    }

    private void saveFoldersRelation(String foreignModel, String foreignId, String userId, String parentFolderId) {
        FoldersRelation relation = new FoldersRelation();
        relation.setForeignModel(foreignModel);
        relation.setForeignId(foreignId);
        relation.setUserId(userId);
        relation.setFolderParentId(parentFolderId);
        foldersRelationRepository.save(relation);
    }

    private Resource saveResource(String name, String username, String uri, String resourceTypeId, String creatorId) {
        Resource resource = new Resource();
        resource.setName(name);
        resource.setUsername(username);
        resource.setUri(uri);
        resource.setResourceTypeId(resourceTypeId);
        resource.setDeleted(false);
        resource.setCreatedBy(creatorId);
        resource.setModifiedBy(creatorId);
        return resourceRepository.save(resource);
    }

    private void savePermission(String aco, String acoForeignKey, String aro, String aroForeignKey, int type) {
        Permission permission = new Permission();
        permission.setAco(aco);
        permission.setAcoForeignKey(acoForeignKey);
        permission.setAro(aro);
        permission.setAroForeignKey(aroForeignKey);
        permission.setType(type);
        permissionRepository.save(permission);
    }

    private void saveSecret(String resourceId, String userId, String encryptedData) {
        Secret secret = new Secret();
        secret.setResourceId(resourceId);
        secret.setUserId(userId);
        secret.setData(encryptedData);
        secretRepository.save(secret);
    }

    /** Cleartext for the password-and-description resource type (encrypted before storage). */
    private String secretCleartext(String password, String description) {
        Map<String, Object> cleartext = new LinkedHashMap<>();
        cleartext.put("password", password);
        cleartext.put("description", description);
        return writeJson(cleartext);
    }

    private void saveOrganizationSetting(String property, String propertyIdSeed, String value, String userId) {
        OrganizationSetting setting = new OrganizationSetting();
        setting.setProperty(property);
        setting.setPropertyId(deterministicUuid(propertyIdSeed));
        setting.setValue(value);
        setting.setCreatedBy(userId);
        setting.setModifiedBy(userId);
        organizationSettingRepository.save(setting);
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize demo seed JSON", e);
        }
    }

    /** Same derivation the settings services use (they query by property, never by property_id). */
    private static String deterministicUuid(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Seed the v5 cross-user metadata demo.
     *
     * <p>
     * A DEDICATED demo metadata keypair (demo_metadata_public.asc /
     * demo_metadata_private.asc) is used as the shared metadata key — it is
     * NEITHER the server identity key NOR any user key, so decrypting a shared
     * metadata private-key copy never leaks the server private key. The demo
     * key's public half becomes {@code metadata_keys.armored_key}; the cleartext
     * {@code PASSBOLT_METADATA_PRIVATE_KEY} JSON wraps its PRIVATE half
     * (passphrase {@code password}). That JSON is encrypted ONCE PER USER to
     * their own public key (ada -> ada key, betty -> betty key) via
     * {@link GpgService#encrypt} and stored in {@code metadata_private_keys.data}.
     * Both users can therefore recover the shared metadata private key in-browser
     * (two-hop decrypt) and read/write v5 metadata. The server never decrypts any
     * of it.
     * </p>
     */
    private void seedV5CrossUserDemo(String adaId, String bettyId, String adaPublicKey,
            String bettyPublicKey) {
        // 1. Active shared metadata key = 独立的演示元数据钥(非服务器钥、非用户钥)。
        String metadataPublicKey = readClasspath("classpath:gpg/demo_metadata_public.asc");
        String metadataFingerprint = gpgKeyParserService.parse(metadataPublicKey).getFingerprint();

        MetadataKey metadataKey = new MetadataKey();
        metadataKey.setFingerprint(metadataFingerprint);
        metadataKey.setArmoredKey(metadataPublicKey);
        metadataKey.setCreatedBy(adaId);
        metadataKey.setModifiedBy(adaId);
        metadataKeyRepository.save(metadataKey);

        // 2. Cleartext PASSBOLT_METADATA_PRIVATE_KEY blob (the inner armored_key is
        //    the SHARED metadata PRIVATE key = the demo metadata private key).
        String metadataPrivateKey = readClasspath("classpath:gpg/demo_metadata_private.asc");
        Map<String, Object> cleartext = new LinkedHashMap<>();
        cleartext.put("object_type", "PASSBOLT_METADATA_PRIVATE_KEY");
        cleartext.put("domain", settingsProperties.getFullBaseUrl());
        cleartext.put("fingerprint", metadataFingerprint);
        cleartext.put("armored_key", metadataPrivateKey);
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
                gpgService.encrypt(cleartextJson, adaPublicKey), adaId);
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
