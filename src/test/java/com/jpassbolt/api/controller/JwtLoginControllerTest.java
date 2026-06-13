package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.JwtAuthDto;
import com.jpassbolt.api.model.GpgKey;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.GpgService;
import com.jpassbolt.api.service.JwtAuthService;
import com.jpassbolt.api.util.GpgTestHelper;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for POST /auth/jwt/login.json (PHP JwtLoginController +
 * GpgJwtAuthenticator).
 *
 * The test user's GPG key is the server's own public key (same pattern as
 * AuthControllerTest): the challenge is encrypted with the server public key
 * (as the protocol requires) and the response challenge — encrypted with the
 * "user" key — can be decrypted again with the server private key.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwtLoginControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GpgKeyRepository gpgKeyRepository;

    @Autowired
    private AuthenticationTokenRepository tokenRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GpgService gpgService;

    @Value("${jpassbolt.settings.full-base-url}")
    private String fullBaseUrl;

    private User testUser;

    @BeforeEach
    void setUp() {
        // Clear in reverse FK-dependency order (see UsersControllerTest)
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        tokenRepository.deleteAll();
        gpgKeyRepository.deleteAll();
        userRepository.deleteAll();

        testUser = new User();
        testUser.setUsername("test@example.com");
        testUser.setRoleId("user");
        testUser.setActive(true);
        testUser.setDeleted(false);
        userRepository.save(testUser);

        String serverPublicKey = gpgService.getServerPublicKey();
        String fingerprint = gpgService.getServerKeyFingerprint();

        GpgKey gpgKey = new GpgKey();
        gpgKey.setUserId(testUser.getId());
        gpgKey.setKeyId(GpgTestHelper.fingerprintToKeyId(fingerprint));
        gpgKey.setFingerprint(fingerprint);
        gpgKey.setUid("Test User <test@example.com>");
        gpgKey.setArmoredKey(serverPublicKey);
        gpgKey.setType("RSA");
        gpgKey.setBits(4096);
        gpgKey.setDeleted(false);
        gpgKeyRepository.save(gpgKey);
    }

    @Test
    void testLoginSuccess_ReturnsEncryptedChallengeWithTokens() throws Exception {
        String verifyToken = UUID.randomUUID().toString();
        String challenge = buildChallenge("1.0.0", fullBaseUrl, verifyToken,
                Instant.now().getEpochSecond() + 60);

        MvcResult result = mockMvc.perform(post("/auth/jwt/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginBody(testUser.getId(), challenge))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.challenge").exists())
                .andReturn();

        // Decrypt the server response challenge with the server private key
        JsonNode responseJson = objectMapper.readTree(result.getResponse().getContentAsString());
        String armoredResponse = responseJson.at("/body/challenge").asText();
        String clearText = gpgService.decrypt(armoredResponse);
        JsonNode payload = objectMapper.readTree(clearText);

        assertThat(payload.get("version").asText()).isEqualTo("1.0.0");
        assertThat(payload.get("domain").asText()).isEqualTo(fullBaseUrl);
        assertThat(payload.get("verify_token").asText()).isEqualTo(verifyToken);
        String accessToken = payload.get("access_token").asText();
        String refreshToken = payload.get("refresh_token").asText();
        assertThat(accessToken).isNotEmpty();

        // The refresh token must be persisted, active, type=refresh_token
        var refreshRow = tokenRepository.findByTokenAndType(refreshToken, JwtAuthService.TYPE_REFRESH_TOKEN);
        assertThat(refreshRow).isPresent();
        assertThat(refreshRow.get().getActive()).isTrue();
        assertThat(refreshRow.get().getUserId()).isEqualTo(testUser.getId());

        // The verify token must be stored for replay protection
        assertThat(tokenRepository.existsByTokenAndUserIdAndType(
                verifyToken, testUser.getId(), JwtAuthService.TYPE_VERIFY_TOKEN)).isTrue();

        // End to end: the RS256 access token must authenticate a request
        mockMvc.perform(get("/auth/is-authenticated.json")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"));
    }

    @Test
    void testLoginWithInvalidUserId_Returns400() throws Exception {
        String challenge = buildChallenge("1.0.0", fullBaseUrl,
                UUID.randomUUID().toString(), Instant.now().getEpochSecond() + 60);

        mockMvc.perform(post("/auth/jwt/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginBody("not-a-uuid", challenge))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testLoginWithUnknownUser_Returns404() throws Exception {
        String challenge = buildChallenge("1.0.0", fullBaseUrl,
                UUID.randomUUID().toString(), Instant.now().getEpochSecond() + 60);

        mockMvc.perform(post("/auth/jwt/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginBody(UUID.randomUUID().toString(), challenge))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testLoginWithInactiveUser_Returns404() throws Exception {
        User inactive = new User();
        inactive.setUsername("inactive@example.com");
        inactive.setRoleId("user");
        inactive.setActive(false);
        inactive.setDeleted(false);
        userRepository.save(inactive);

        String challenge = buildChallenge("1.0.0", fullBaseUrl,
                UUID.randomUUID().toString(), Instant.now().getEpochSecond() + 60);

        mockMvc.perform(post("/auth/jwt/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginBody(inactive.getId(), challenge))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testLoginWithNonPgpChallenge_Returns400() throws Exception {
        mockMvc.perform(post("/auth/jwt/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginBody(testUser.getId(), "garbage"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testLoginWithWrongDomain_Returns400() throws Exception {
        String challenge = buildChallenge("1.0.0", "https://evil.example.com",
                UUID.randomUUID().toString(), Instant.now().getEpochSecond() + 60);

        mockMvc.perform(post("/auth/jwt/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginBody(testUser.getId(), challenge))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testLoginWithWrongVersion_Returns400() throws Exception {
        String challenge = buildChallenge("2.0.0", fullBaseUrl,
                UUID.randomUUID().toString(), Instant.now().getEpochSecond() + 60);

        mockMvc.perform(post("/auth/jwt/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginBody(testUser.getId(), challenge))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testLoginWithExpiredVerifyToken_Returns400() throws Exception {
        String challenge = buildChallenge("1.0.0", fullBaseUrl,
                UUID.randomUUID().toString(), Instant.now().getEpochSecond() - 100);

        mockMvc.perform(post("/auth/jwt/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginBody(testUser.getId(), challenge))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testLoginWithReplayedVerifyToken_Returns400() throws Exception {
        String verifyToken = UUID.randomUUID().toString();
        long expiry = Instant.now().getEpochSecond() + 60;

        // First login succeeds
        mockMvc.perform(post("/auth/jwt/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        loginBody(testUser.getId(), buildChallenge("1.0.0", fullBaseUrl, verifyToken, expiry)))))
                .andExpect(status().isOk());

        // Replaying the same verify token is rejected
        mockMvc.perform(post("/auth/jwt/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        loginBody(testUser.getId(), buildChallenge("1.0.0", fullBaseUrl, verifyToken, expiry)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    /**
     * Build the armored client challenge: clear-text JSON encrypted with the
     * server public key (the official client also signs it with the user
     * key — signature verification is a known deviation, see JwtAuthService).
     */
    private String buildChallenge(String version, String domain, String verifyToken, long expiry) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", version);
        payload.put("domain", domain);
        payload.put("verify_token", verifyToken);
        payload.put("verify_token_expiry", expiry);
        String json = objectMapper.writeValueAsString(payload);

        PGPPublicKey serverKey = GpgTestHelper.loadPublicKey(gpgService.getServerPublicKey());
        return GpgTestHelper.encrypt(json, serverKey);
    }

    private JwtAuthDto.LoginRequest loginBody(String userId, String challenge) {
        return JwtAuthDto.LoginRequest.builder()
                .userId(userId)
                .challenge(challenge)
                .build();
    }
}
