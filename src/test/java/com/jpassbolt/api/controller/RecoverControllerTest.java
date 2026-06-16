package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.model.AuthenticationToken;
import com.jpassbolt.api.model.GpgKey;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.GpgService;
import com.jpassbolt.api.util.GpgTestHelper;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the public account-recovery endpoints:
 * POST /users/recover.json,
 * GET /setup/recover/start/{userId}/{tokenId}.json,
 * PUT|POST /setup/recover/complete/{userId}.json and
 * PUT|POST /setup/recover/abort/{userId}.json.
 *
 * <p>
 * No class-level @WithMockUser — these endpoints are guest-only and must be
 * reachable anonymously (SecurityConfig whitelists "/setup/**" and
 * "/users/recover").
 * </p>
 *
 * <p>
 * Recovery differs from setup: it operates on an ACTIVE user who already owns a
 * stored key, it installs no new key, never flips user.active, and only
 * consumes a "recover" token whose fingerprint matches the existing key.
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class RecoverControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GpgService gpgService;

    @Autowired
    private AuthenticationTokenRepository authenticationTokenRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private GpgKeyRepository gpgKeyRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private Role userRole;
    private User activeUser;
    private GpgKey existingKey;
    private AuthenticationToken recoverToken;

    @BeforeEach
    void setUp() {
        authenticationTokenRepository.deleteAll();
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        profileRepository.deleteAll();
        gpgKeyRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        userRole = new Role();
        userRole.setName("user");
        roleRepository.save(userRole);

        // An ACTIVE, set-up user (usernames are stored lowercased/trimmed).
        activeUser = new User();
        activeUser.setUsername("betty@example.com");
        activeUser.setRoleId(userRole.getId());
        activeUser.setActive(true);
        activeUser.setDeleted(false);
        userRepository.save(activeUser);

        Profile profile = new Profile();
        profile.setUserId(activeUser.getId());
        profile.setFirstName("Betty");
        profile.setLastName("Holberton");
        profileRepository.save(profile);

        // The user's already-stored public key (the server key pair stands in
        // for it in tests). RecoverComplete must match this fingerprint.
        String fingerprint = gpgService.getServerKeyFingerprint();
        existingKey = new GpgKey();
        existingKey.setUserId(activeUser.getId());
        existingKey.setArmoredKey(gpgService.getServerPublicKey());
        existingKey.setUid("Betty <betty@example.com>");
        existingKey.setKeyId(GpgTestHelper.fingerprintToKeyId(fingerprint));
        existingKey.setFingerprint(fingerprint);
        existingKey.setType("RSA");
        existingKey.setBits(4096);
        existingKey.setDeleted(false);
        gpgKeyRepository.save(existingKey);

        recoverToken = new AuthenticationToken();
        recoverToken.setUserId(activeUser.getId());
        recoverToken.setToken(UUID.randomUUID().toString());
        recoverToken.setType("recover");
        recoverToken.setActive(true);
        authenticationTokenRepository.save(recoverToken);
    }

    private String startUrl(String userId, String token) {
        return "/setup/recover/start/" + userId + "/" + token + ".json";
    }

    private String completeUrl(String userId) {
        return "/setup/recover/complete/" + userId + ".json";
    }

    private String abortUrl(String userId) {
        return "/setup/recover/abort/" + userId + ".json";
    }

    private String completePayload(String token, String armoredKey) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "authentication_token", Map.of("token", token),
                "gpgkey", Map.of("armored_key", armoredKey)));
    }

    /**
     * Age a token past the expiry window via raw SQL — the created column is
     * updatable=false, repository.save can never touch it.
     */
    private void ageToken(String tokenId, int days) {
        jdbcTemplate.update("UPDATE authentication_tokens SET created = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(days)), tokenId);
    }

    // ------------------------------------------------------------------
    // POST /users/recover
    // ------------------------------------------------------------------

    @Test
    void testRecover_ActiveUser_IssuesRecoverToken() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", "betty@example.com"));

        mockMvc.perform(post("/users/recover.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.message").value("Recovery process started, check your email."))
                .andExpect(jsonPath("$.body").value(nullValue()));

        // A new active recover token now exists for the user (the seeded one
        // plus the freshly issued one).
        List<AuthenticationToken> recoverTokens = authenticationTokenRepository
                .findAllByUserIdAndTypeAndActiveTrue(activeUser.getId(), "recover");
        assertThat(recoverTokens).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void testRecover_WithValidCase_IssuesRecoverToken() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "betty@example.com", "case", "lost-passphrase"));

        mockMvc.perform(post("/users/recover.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.message").value("Recovery process started, check your email."));
    }

    @Test
    void testRecover_InvalidCase_BadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "betty@example.com", "case", "not-a-real-case"));

        mockMvc.perform(post("/users/recover.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("Account recovery reason not supported."));
    }

    @Test
    void testRecover_NonExistentUser_EnumerationSafeSuccess() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", "ghost@example.com"));

        long tokensBefore = authenticationTokenRepository.count();

        mockMvc.perform(post("/users/recover.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.message").value("Recovery process started, check your email."));

        // No token was issued for a non-existent user.
        assertThat(authenticationTokenRepository.count()).isEqualTo(tokensBefore);
    }

    @Test
    void testRecover_InactiveUser_IssuesRegisterToken() throws Exception {
        // A user who never completed setup → recovery restarts setup with a
        // "register" token (PHP UserRecoverService GH#73).
        activeUser.setActive(false);
        userRepository.save(activeUser);

        String body = objectMapper.writeValueAsString(Map.of("username", "betty@example.com"));

        mockMvc.perform(post("/users/recover.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.message").value("Recovery process started, check your email."));

        assertThat(authenticationTokenRepository
                .findAllByUserIdAndTypeAndActiveTrue(activeUser.getId(), "register")).isNotEmpty();
    }

    @Test
    void testRecover_MissingUsername_BadRequest() throws Exception {
        mockMvc.perform(post("/users/recover.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("Please provide a valid email address."));
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = { "USER" })
    void testRecover_AuthenticatedCaller_Forbidden() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", "betty@example.com"));

        mockMvc.perform(post("/users/recover.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.message").value(
                        "Only guests are allowed to recover an account. Please logout first."));
    }

    // ------------------------------------------------------------------
    // GET /setup/recover/start
    // ------------------------------------------------------------------

    @Test
    void testStart_Success() throws Exception {
        mockMvc.perform(get(startUrl(activeUser.getId(), recoverToken.getToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.user.username").value("betty@example.com"))
                .andExpect(jsonPath("$.body.user.active").value(true))
                .andExpect(jsonPath("$.body.user.profile.first_name").value("Betty"))
                .andExpect(jsonPath("$.body.user.role.name").value("user"));

        // start is read-only
        assertThat(authenticationTokenRepository.findById(recoverToken.getId())
                .orElseThrow().getActive()).isTrue();
    }

    @Test
    void testStart_InactiveUser_BadRequest() throws Exception {
        // Recovery requires an ACTIVE user — the opposite of setup-start.
        activeUser.setActive(false);
        userRepository.save(activeUser);

        mockMvc.perform(get(startUrl(activeUser.getId(), recoverToken.getToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The user does not exist or is not active or is disabled."));
    }

    @Test
    void testStart_DisabledUser_BadRequest() throws Exception {
        activeUser.setDisabled(LocalDateTime.now().minusDays(1));
        userRepository.save(activeUser);

        mockMvc.perform(get(startUrl(activeUser.getId(), recoverToken.getToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The user does not exist or is not active or is disabled."));
    }

    @Test
    void testStart_WrongTokenType_BadRequest() throws Exception {
        // A register token is NOT a recover token.
        recoverToken.setType("register");
        authenticationTokenRepository.save(recoverToken);

        mockMvc.perform(get(startUrl(activeUser.getId(), recoverToken.getToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The authentication token is not valid."));
    }

    @Test
    void testStart_InactiveToken_BadRequest() throws Exception {
        recoverToken.setActive(false);
        authenticationTokenRepository.save(recoverToken);

        mockMvc.perform(get(startUrl(activeUser.getId(), recoverToken.getToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The authentication token is not valid."));
    }

    @Test
    void testStart_ExpiredToken_BadRequest() throws Exception {
        ageToken(recoverToken.getId(), 11);

        mockMvc.perform(get(startUrl(activeUser.getId(), recoverToken.getToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The authentication token is not valid."));
    }

    @Test
    void testStart_InvalidUserUuid_BadRequest() throws Exception {
        mockMvc.perform(get(startUrl("not-a-uuid", recoverToken.getToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The user identifier should be a valid UUID."));
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = { "USER" })
    void testStart_AuthenticatedCaller_Forbidden() throws Exception {
        mockMvc.perform(get(startUrl(activeUser.getId(), recoverToken.getToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.message").value(
                        "Only guests are allowed to proceed with account recovery."));
    }

    // ------------------------------------------------------------------
    // PUT|POST /setup/recover/complete
    // ------------------------------------------------------------------

    @Test
    void testComplete_Success_ConsumesTokenNoStateChange() throws Exception {
        mockMvc.perform(put(completeUrl(activeUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(completePayload(recoverToken.getToken(), gpgService.getServerPublicKey())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.message").value("The recovery was completed successfully."))
                .andExpect(jsonPath("$.body").value(nullValue()));

        // Token consumed; user stays active; no extra key installed.
        assertThat(authenticationTokenRepository.findById(recoverToken.getId())
                .orElseThrow().getActive()).isFalse();
        assertThat(userRepository.findById(activeUser.getId()).orElseThrow().getActive()).isTrue();
        assertThat(gpgKeyRepository.findByUserId(activeUser.getId())).hasSize(1);
    }

    @Test
    void testComplete_PostMethodAlsoAccepted() throws Exception {
        mockMvc.perform(post(completeUrl(activeUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(completePayload(recoverToken.getToken(), gpgService.getServerPublicKey())))
                .andExpect(status().isOk());
    }

    @Test
    void testComplete_LegacyTokenKeyAccepted() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "authenticationtoken", Map.of("token", recoverToken.getToken()),
                "gpgkey", Map.of("armored_key", gpgService.getServerPublicKey())));

        mockMvc.perform(put(completeUrl(activeUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void testComplete_WrongFingerprint_BadRequest() throws Exception {
        // Submit a different valid key whose fingerprint is NOT the user's.
        String otherKey = generateOtherArmoredPublicKey();

        mockMvc.perform(put(completeUrl(activeUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(completePayload(recoverToken.getToken(), otherKey)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The key provided does not belong to given user."));

        // The recover token is untouched on failure.
        assertThat(authenticationTokenRepository.findById(recoverToken.getId())
                .orElseThrow().getActive()).isTrue();
    }

    @Test
    void testComplete_UnknownToken_BadRequest() throws Exception {
        mockMvc.perform(put(completeUrl(activeUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(completePayload(UUID.randomUUID().toString(),
                        gpgService.getServerPublicKey())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The authentication token is not valid."));
    }

    @Test
    void testComplete_MissingToken_BadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "gpgkey", Map.of("armored_key", gpgService.getServerPublicKey())));

        mockMvc.perform(put(completeUrl(activeUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "An authentication token should be provided."));
    }

    @Test
    void testComplete_ExpiredToken_BadRequest() throws Exception {
        ageToken(recoverToken.getId(), 11);

        mockMvc.perform(put(completeUrl(activeUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(completePayload(recoverToken.getToken(), gpgService.getServerPublicKey())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The authentication token is not valid."));
    }

    @Test
    void testComplete_InactiveUser_BadRequest() throws Exception {
        activeUser.setActive(false);
        userRepository.save(activeUser);

        mockMvc.perform(put(completeUrl(activeUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(completePayload(recoverToken.getToken(), gpgService.getServerPublicKey())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The user does not exist, has not completed the setup or was deleted."));
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = { "USER" })
    void testComplete_AuthenticatedCaller_Forbidden() throws Exception {
        mockMvc.perform(put(completeUrl(activeUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(completePayload(recoverToken.getToken(), gpgService.getServerPublicKey())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.message").value(
                        "Only guests are allowed to complete an account recovery."));
    }

    // ------------------------------------------------------------------
    // PUT|POST /setup/recover/abort
    // ------------------------------------------------------------------

    @Test
    void testAbort_Success_ConsumesToken() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "authentication_token", Map.of("token", recoverToken.getToken())));

        mockMvc.perform(put(abortUrl(activeUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body").value(nullValue()));

        assertThat(authenticationTokenRepository.findById(recoverToken.getId())
                .orElseThrow().getActive()).isFalse();
    }

    @Test
    void testAbort_UnknownToken_BadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "authentication_token", Map.of("token", UUID.randomUUID().toString())));

        mockMvc.perform(put(abortUrl(activeUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The authentication token is not valid."));
    }

    // ------------------------------------------------------------------
    // Test fixtures
    // ------------------------------------------------------------------

    /**
     * Generate a fresh, parseable RSA OpenPGP PUBLIC key (armored) whose
     * fingerprint is guaranteed NOT to be the user's stored key — used by the
     * wrong-fingerprint recover-complete case. Pure Bouncy Castle (iron rule
     * #1: no system gpg), test-side only.
     */
    private static String generateOtherArmoredPublicKey() throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(2048);
        PGPKeyPair keyPair = new JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, kpg.generateKeyPair(), new Date());

        PGPSignatureSubpacketGenerator subpackets = new PGPSignatureSubpacketGenerator();
        subpackets.setKeyFlags(false, KeyFlags.CERTIFY_OTHER | KeyFlags.SIGN_DATA
                | KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE);

        PGPKeyRingGenerator ringGenerator;
        try {
            // PGPKeyRingGenerator requires a SHA1 digest calculator for the
            // secret-key checksum ("only SHA1 supported for key checksum
            // calculations") — distinct from the SHA256 self-signature hash.
            ringGenerator = new PGPKeyRingGenerator(
                    PGPSignature.POSITIVE_CERTIFICATION,
                    keyPair,
                    "Other User <other@example.com>",
                    new JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1),
                    subpackets.generate(),
                    null,
                    new JcaPGPContentSignerBuilder(keyPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256),
                    new JcePBESecretKeyEncryptorBuilder(org.bouncycastle.openpgp.PGPEncryptedData.AES_256)
                            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                            .build("passphrase".toCharArray()));
        } catch (PGPException e) {
            throw new IllegalStateException("Failed to build test key ring", e);
        }

        PGPPublicKeyRing publicKeyRing = ringGenerator.generatePublicKeyRing();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ArmoredOutputStream armored = new ArmoredOutputStream(out)) {
            publicKeyRing.encode(armored);
        }
        return out.toString("UTF-8");
    }
}
