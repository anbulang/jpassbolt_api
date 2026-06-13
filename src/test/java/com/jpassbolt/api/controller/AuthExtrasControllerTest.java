package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.AuthDto;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.GpgService;
import com.jpassbolt.api.service.JwtService;
import com.jpassbolt.api.util.GpgTestHelper;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the GpgAuth-channel auth extras:
 * <ul>
 * <li>GET /auth/is-authenticated.json (PHP AuthIsAuthenticatedController)</li>
 * <li>POST /auth/logout.json (PHP AuthLogoutController — POST only, GET
 * disabled by default)</li>
 * <li>POST /auth/verify.json (PHP routes this onto AuthLogin::loginPost —
 * GpgAuth Stage 0 server identity verification)</li>
 * </ul>
 *
 * Note on negatives: the GpgAuth protocol reports Stage 0 errors with
 * HTTP 200 + error envelope + X-GPGAuth-Error header (the browser extension
 * depends on the 200) — same behaviour as the existing /auth/login.json.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthExtrasControllerTest {

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

    @Autowired
    private JwtService jwtService;

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
    }

    // ------------------------------------------------------------------
    // GET /auth/is-authenticated.json
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "test@example.com", roles = { "USER" })
    void testIsAuthenticatedWithMockUser_Returns200() throws Exception {
        mockMvc.perform(get("/auth/is-authenticated.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"));
    }

    @Test
    @WithAnonymousUser
    void testIsAuthenticatedAnonymous_Returns401() throws Exception {
        mockMvc.perform(get("/auth/is-authenticated.json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testIsAuthenticatedWithRealBearerToken_Returns200() throws Exception {
        // RS256 token with sub = user UUID, resolved by JwtAuthenticationFilter
        String accessToken = jwtService.generateToken(testUser.getId());

        mockMvc.perform(get("/auth/is-authenticated.json")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"));
    }

    @Test
    void testIsAuthenticatedWithGarbageBearerToken_Returns401() throws Exception {
        mockMvc.perform(get("/auth/is-authenticated.json")
                .header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    // ------------------------------------------------------------------
    // POST /auth/logout.json
    // ------------------------------------------------------------------

    @Test
    @WithAnonymousUser
    void testLogoutPost_AlwaysSucceeds() throws Exception {
        // PHP allows unauthenticated logout — stateless compatibility action
        mockMvc.perform(post("/auth/logout.json")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.message").value("You are successfully logged out."));
    }

    @Test
    @WithMockUser(username = "test@example.com", roles = { "USER" })
    void testLogoutGet_Returns405() throws Exception {
        // GET logout is disabled by default in PHP
        // (passbolt.security.getLogoutEndpointEnabled) — only POST is mapped
        mockMvc.perform(get("/auth/logout.json"))
                .andExpect(status().isMethodNotAllowed());
    }

    // ------------------------------------------------------------------
    // POST /auth/verify.json (GpgAuth Stage 0)
    // ------------------------------------------------------------------

    @Test
    void testVerifyPost_DecryptsServerVerifyToken() throws Exception {
        String nonce = GpgTestHelper.generateValidNonce();
        PGPPublicKey serverKey = GpgTestHelper.loadPublicKey(gpgService.getServerPublicKey());
        String encryptedToken = GpgTestHelper.encrypt(nonce, serverKey);

        mockMvc.perform(post("/auth/verify.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        verifyBody(gpgService.getServerKeyFingerprint(), encryptedToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(header().string("X-GPGAuth-Verify-Response", nonce));
    }

    @Test
    void testVerifyPostWithInvalidNonceFormat_ReturnsGpgAuthError() throws Exception {
        PGPPublicKey serverKey = GpgTestHelper.loadPublicKey(gpgService.getServerPublicKey());
        String encryptedToken = GpgTestHelper.encrypt("not-a-valid-nonce", serverKey);

        mockMvc.perform(post("/auth/verify.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        verifyBody(gpgService.getServerKeyFingerprint(), encryptedToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(header().string("X-GPGAuth-Error", "true"));
    }

    @Test
    void testVerifyPostWithUndecryptableToken_ReturnsGpgAuthError() throws Exception {
        mockMvc.perform(post("/auth/verify.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        verifyBody(gpgService.getServerKeyFingerprint(), "-----BEGIN PGP MESSAGE-----\ngarbage"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(header().string("X-GPGAuth-Error", "true"));
    }

    @Test
    void testVerifyPostWithMissingToken_ReturnsGpgAuthError() throws Exception {
        mockMvc.perform(post("/auth/verify.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        verifyBody(gpgService.getServerKeyFingerprint(), null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(header().string("X-GPGAuth-Error", "true"));
    }

    private AuthDto.LoginRequest verifyBody(String keyId, String serverVerifyToken) {
        AuthDto.LoginRequest request = new AuthDto.LoginRequest();
        AuthDto.DataWrapper data = new AuthDto.DataWrapper();
        AuthDto.GpgAuth gpgAuth = new AuthDto.GpgAuth();
        gpgAuth.setKeyid(keyId);
        if (serverVerifyToken != null) {
            gpgAuth.setServerVerifyToken(serverVerifyToken);
        }
        data.setGpgAuth(gpgAuth);
        request.setData(data);
        return request;
    }
}
