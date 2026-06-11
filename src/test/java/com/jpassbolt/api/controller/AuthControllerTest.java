package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.AuthDto;
import com.jpassbolt.api.model.GpgKey;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.AuthService;
import com.jpassbolt.api.service.GpgService;
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

    @BeforeEach
    void setUp() {
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
    void testLoginWithInvalidKeyId_ReturnsError() throws Exception {
        AuthDto.LoginRequest request = new AuthDto.LoginRequest();
        AuthDto.DataWrapper data = new AuthDto.DataWrapper();
        AuthDto.GpgAuth gpgAuth = new AuthDto.GpgAuth();
        gpgAuth.setKeyid("INVALID_KEY_ID");
        data.setGpgAuth(gpgAuth);
        request.setData(data);

        mockMvc.perform(post("/auth/login.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-GPGAuth-Error", "true"))
                .andExpect(jsonPath("$.header.status").value("error"));
    }
}
