package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.AuthDto;
import com.jpassbolt.api.model.AuthenticationToken;
import com.jpassbolt.api.model.GpgKey;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.AuthService;
import com.jpassbolt.api.service.GpgService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for GPG authentication flow.
 * Uses server's own key for testing since we need the private key to decrypt.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GpgKeyRepository gpgKeyRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GpgService gpgService;

    private User testUser;
    private GpgKey testGpgKey;
    private String testFingerprint;

    @Autowired
    private com.jpassbolt.api.repository.PermissionRepository permissionRepository;

    @Autowired
    private AuthenticationTokenRepository authenticationTokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        authenticationTokenRepository.deleteAll();
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        gpgKeyRepository.deleteAll();
        userRepository.deleteAll();

        // Create test user
        testUser = new User();
        testUser.setUsername("test@example.com");
        testUser.setRoleId("user");
        testUser.setActive(true);
        testUser.setDeleted(false);
        userRepository.save(testUser);

        // Use server's public key for testing - this allows server to decrypt the nonce
        // In a real scenario, each user would have their own key pair
        String serverPublicKey = gpgService.getServerPublicKey();
        testFingerprint = gpgService.getServerKeyFingerprint();
        String serverKeyId = testFingerprint.substring(testFingerprint.length() - 16); // 16-char key ID

        testGpgKey = new GpgKey();
        testGpgKey.setUserId(testUser.getId());
        testGpgKey.setKeyId(serverKeyId);
        testGpgKey.setFingerprint(testFingerprint);
        testGpgKey.setUid("Test User <test@example.com>");
        testGpgKey.setArmoredKey(serverPublicKey); // Use armoredKey instead of key
        testGpgKey.setType("RSA");
        testGpgKey.setBits(4096);
        testGpgKey.setDeleted(false);
        gpgKeyRepository.save(testGpgKey);
    }

    @Test
    void testVerifyEndpoint() throws Exception {
        mockMvc.perform(get("/auth/verify.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.keydata").exists())
                .andExpect(jsonPath("$.body.fingerprint").exists())
                .andExpect(jsonPath("$.header.status").value("success"));
    }

    @Test
    void testLoginStage1_ReturnsEncryptedToken() throws Exception {
        // Stage 1: Initial Request - server will encrypt nonce with user's public key
        AuthDto.LoginRequest request = new AuthDto.LoginRequest();
        AuthDto.DataWrapper data = new AuthDto.DataWrapper();
        AuthDto.GpgAuth gpgAuth = new AuthDto.GpgAuth();
        gpgAuth.setKeyid(testFingerprint); // Use full fingerprint
        data.setGpgAuth(gpgAuth);
        request.setData(data);

        MvcResult result = mockMvc.perform(post("/auth/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-GPGAuth-Progress", "stage1"))
                .andExpect(header().exists("X-GPGAuth-User-Auth-Token"))
                .andReturn();

        // Verify we got an encrypted token
        String encryptedToken = result.getResponse().getHeader("X-GPGAuth-User-Auth-Token");
        assertThat(encryptedToken).isNotEmpty();
    }

    @Test
    void testFullLoginFlow() throws Exception {
        // Stage 1: Get encrypted token
        AuthDto.LoginRequest request1 = new AuthDto.LoginRequest();
        AuthDto.DataWrapper data1 = new AuthDto.DataWrapper();
        AuthDto.GpgAuth gpgAuth1 = new AuthDto.GpgAuth();
        gpgAuth1.setKeyid(testFingerprint);
        data1.setGpgAuth(gpgAuth1);
        request1.setData(data1);

        MvcResult result1 = mockMvc.perform(post("/auth/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request1)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-GPGAuth-Progress", "stage1"))
                .andReturn();

        // Get and URL-decode the encrypted token from header
        String encodedToken = result1.getResponse().getHeader("X-GPGAuth-User-Auth-Token");
        String encryptedNonce = java.net.URLDecoder.decode(encodedToken, java.nio.charset.StandardCharsets.UTF_8);

        // Decrypt the nonce (since we're using server's key for test user)
        String decryptedNonce = gpgService.decrypt(encryptedNonce);

        // Verify the nonce format
        assertThat(decryptedNonce).matches("gpgauthv1\\.3\\.0\\|36\\|[0-9a-f-]+\\|gpgauthv1\\.3\\.0");

        // Stage 2: Complete authentication with decrypted nonce
        AuthDto.LoginRequest request2 = new AuthDto.LoginRequest();
        AuthDto.DataWrapper data2 = new AuthDto.DataWrapper();
        AuthDto.GpgAuth gpgAuth2 = new AuthDto.GpgAuth();
        gpgAuth2.setKeyid(testFingerprint);
        gpgAuth2.setUserTokenResult(decryptedNonce); // Send the decrypted nonce
        data2.setGpgAuth(gpgAuth2);
        request2.setData(data2);

        mockMvc.perform(post("/auth/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request2)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-GPGAuth-Authenticated", "true"))
                .andExpect(header().exists("Authorization"))
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.user.username").value("test@example.com"));
    }

    @Test
    void testLoginStage1_UnknownKeyAndDisabledAccount_AreIndistinguishable() throws Exception {
        // Unknown key: well-formed fingerprint not present in the database
        AuthDto.LoginRequest unknownRequest = new AuthDto.LoginRequest();
        AuthDto.DataWrapper unknownData = new AuthDto.DataWrapper();
        AuthDto.GpgAuth unknownGpgAuth = new AuthDto.GpgAuth();
        String unknownFingerprint = "0123456789ABCDEF0123456789ABCDEF01234567";
        unknownGpgAuth.setKeyid(unknownFingerprint);
        unknownData.setGpgAuth(unknownGpgAuth);
        unknownRequest.setData(unknownData);

        MvcResult unknownResult = mockMvc.perform(post("/auth/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(unknownRequest)))
                .andExpect(header().string("X-GPGAuth-Error", "true"))
                .andExpect(header().string("X-GPGAuth-Debug", "There is no user associated with this key."))
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.message").value("There is no user associated with this key."))
                .andReturn();

        // Disabled account: registered key, but the user is disabled
        testUser.setDisabled(java.time.LocalDateTime.now());
        userRepository.save(testUser);

        AuthDto.LoginRequest disabledRequest = new AuthDto.LoginRequest();
        AuthDto.DataWrapper disabledData = new AuthDto.DataWrapper();
        AuthDto.GpgAuth disabledGpgAuth = new AuthDto.GpgAuth();
        disabledGpgAuth.setKeyid(testFingerprint);
        disabledData.setGpgAuth(disabledGpgAuth);
        disabledRequest.setData(disabledData);

        MvcResult disabledResult = mockMvc.perform(post("/auth/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(disabledRequest)))
                .andExpect(header().string("X-GPGAuth-Error", "true"))
                .andExpect(header().string("X-GPGAuth-Debug", "There is no user associated with this key."))
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.message").value("There is no user associated with this key."))
                .andReturn();

        // PHP GpgAuthenticator answers every lookup failure through the same
        // path: an attacker must not be able to tell an unknown key from an
        // existing but disabled/inactive account via status code or body.
        assertThat(disabledResult.getResponse().getStatus())
                .isEqualTo(unknownResult.getResponse().getStatus());
        assertThat(unknownResult.getResponse().getContentAsString())
                .doesNotContain(unknownFingerprint);
        assertThat(disabledResult.getResponse().getContentAsString())
                .doesNotContain(testFingerprint);
    }

    @Test
    void testLoginWithInvalidKeyId_ReturnsError() throws Exception {
        AuthDto.LoginRequest request = new AuthDto.LoginRequest();
        AuthDto.DataWrapper data = new AuthDto.DataWrapper();
        AuthDto.GpgAuth gpgAuth = new AuthDto.GpgAuth();
        gpgAuth.setKeyid("INVALID_KEY_ID");
        data.setGpgAuth(gpgAuth);
        request.setData(data);

        // 400, not 200: official Passbolt answers an unknown key with a 400
        // (AuthLoginControllerTest:96). A 200 here reads as success to any
        // client that keys off the status code, including the official
        // extension.
        mockMvc.perform(post("/auth/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-GPGAuth-Error", "true"))
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    // ------------------------------------------------------------------
    // Stage 2 token scoping — regressions for an account-takeover bypass:
    // findByToken(uuid) used to accept ANY user's token of ANY type and mint a
    // JWT for the TOKEN's owner, so a victim's register/recover UUID (handed
    // out in a plain-text email link) authenticated as the victim without ever
    // possessing their private key.
    // ------------------------------------------------------------------

    /** Post a stage-2 nonce carrying {@code uuid}, presenting testUser's keyid. */
    private MvcResult stage2WithNonce(String uuid) throws Exception {
        AuthDto.LoginRequest request = new AuthDto.LoginRequest();
        AuthDto.DataWrapper data = new AuthDto.DataWrapper();
        AuthDto.GpgAuth gpgAuth = new AuthDto.GpgAuth();
        gpgAuth.setKeyid(testFingerprint);
        gpgAuth.setUserTokenResult("gpgauthv1.3.0|36|" + uuid + "|gpgauthv1.3.0");
        data.setGpgAuth(gpgAuth);
        request.setData(data);

        return mockMvc.perform(post("/auth/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                // Official Passbolt rejects a bad stage-2 user_token with 400
                // (AuthLoginControllerTest:511) — NOT 401: the session did not
                // expire, the login attempt itself was refused.
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-GPGAuth-Authenticated", "false"))
                .andExpect(header().doesNotExist("Authorization"))
                .andExpect(jsonPath("$.header.status").value("error"))
                .andReturn();
    }

    private User createVictim() {
        User victim = new User();
        victim.setUsername("victim@example.com");
        victim.setRoleId("user");
        victim.setActive(true);
        victim.setDeleted(false);
        return userRepository.save(victim);
    }

    private AuthenticationToken saveToken(String userId, String type) {
        AuthenticationToken token = new AuthenticationToken();
        token.setUserId(userId);
        token.setToken(java.util.UUID.randomUUID().toString());
        token.setType(type);
        token.setActive(true);
        return authenticationTokenRepository.save(token);
    }

    @Test
    void testStage2_VictimsRecoverToken_CannotMintAJwt() throws Exception {
        // The recover UUID travels in a plain email link. Presenting it with
        // the ATTACKER's own keyid must not authenticate anyone.
        User victim = createVictim();
        AuthenticationToken recoverToken = saveToken(victim.getId(), "recover");

        stage2WithNonce(recoverToken.getToken());

        // and the victim's token is untouched (no silent consumption)
        assertThat(authenticationTokenRepository.findById(recoverToken.getId())
                .orElseThrow().getActive()).isTrue();
    }

    @Test
    void testStage2_VictimsLoginToken_CannotMintAJwt() throws Exception {
        // Even a token of the RIGHT type must belong to the presenter.
        User victim = createVictim();
        AuthenticationToken victimLogin = saveToken(victim.getId(), "login");

        stage2WithNonce(victimLogin.getToken());

        assertThat(authenticationTokenRepository.findById(victimLogin.getId())
                .orElseThrow().getActive()).isTrue();
    }

    @Test
    void testStage2_OwnRegisterToken_IsNotALoginCredential() throws Exception {
        // Type scoping: the presenter's OWN register token is not a login token.
        AuthenticationToken ownRegister = saveToken(testUser.getId(), "register");

        stage2WithNonce(ownRegister.getToken());

        assertThat(authenticationTokenRepository.findById(ownRegister.getId())
                .orElseThrow().getActive()).isTrue();
    }

    @Test
    void testStage2_ExpiredLoginToken_Rejected() throws Exception {
        // PHP login token expiry = 5 minutes (config/default.php).
        AuthenticationToken loginToken = saveToken(testUser.getId(), "login");
        // created is stored in UTC (BaseEntity.onCreate) — age it in UTC too,
        // or a non-UTC test machine writes a "future" timestamp and nothing expires.
        jdbcTemplate.update("UPDATE authentication_tokens SET created = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(
                        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(10)),
                loginToken.getId());

        // Authentication is refused (asserted inside stage2WithNonce: no JWT).
        // The row stays active=true — the rejection throws inside the
        // @Transactional service, so any flag write would be rolled back. An
        // expired token is inert anyway: the window is re-checked every lookup.
        stage2WithNonce(loginToken.getToken());
    }

    @Test
    void testStage2_NonceReplay_Rejected() throws Exception {
        // A consumed login token must not authenticate a second time.
        AuthenticationToken loginToken = saveToken(testUser.getId(), "login");
        String nonce = "gpgauthv1.3.0|36|" + loginToken.getToken() + "|gpgauthv1.3.0";

        AuthDto.LoginRequest ok = new AuthDto.LoginRequest();
        AuthDto.DataWrapper okData = new AuthDto.DataWrapper();
        AuthDto.GpgAuth okAuth = new AuthDto.GpgAuth();
        okAuth.setKeyid(testFingerprint);
        okAuth.setUserTokenResult(nonce);
        okData.setGpgAuth(okAuth);
        ok.setData(okData);

        mockMvc.perform(post("/auth/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ok)))
                .andExpect(header().string("X-GPGAuth-Authenticated", "true"))
                .andExpect(header().exists("Authorization"));

        stage2WithNonce(loginToken.getToken()); // replay → error, no JWT
    }

    @Test
    void testStage2_MalformedNonce_DoesNotEchoDecryptedPlaintext() throws Exception {
        // The stage-2 input is decrypted with the SERVER private key before the
        // format check, so echoing it would make login a decryption oracle for
        // anything encrypted to the server key (whitepaper p.25).
        String secret = "TOP-SECRET-SERVER-DECRYPTED-PLAINTEXT";
        String ciphertext = gpgService.encrypt(secret, gpgService.getServerPublicKey());

        AuthDto.LoginRequest request = new AuthDto.LoginRequest();
        AuthDto.DataWrapper data = new AuthDto.DataWrapper();
        AuthDto.GpgAuth gpgAuth = new AuthDto.GpgAuth();
        gpgAuth.setKeyid(testFingerprint);
        gpgAuth.setUserTokenResult(ciphertext);
        data.setGpgAuth(gpgAuth);
        request.setData(data);

        MvcResult result = mockMvc.perform(post("/auth/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(header().doesNotExist("Authorization"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(secret);
    }
}
