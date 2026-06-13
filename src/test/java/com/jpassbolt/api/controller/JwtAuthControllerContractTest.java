package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.AuthenticationToken;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.JwtAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.UUID;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI contract tests for the auth-extras cluster. All paths exist in
 * src/test/resources/plugin-redoc-0.yaml: /auth/jwt/jwks.json,
 * /auth/jwt/rsa.json, /auth/jwt/login.json, /auth/jwt/refresh.json,
 * /auth/jwt/logout.json, /auth/is-authenticated.json, /auth/logout.json,
 * /auth/verify.json.
 */
public class JwtAuthControllerContractTest extends OpenApiComplianceTest {

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

    private User testUser;

    @BeforeEach
    void setUpData() {
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

    @Test
    public void testJwksContract() throws Exception {
        // The jwks response is the one endpoint with a bare schema (no
        // {header, body} envelope) — components/responses/jwks.
        mockMvc.perform(get("/auth/jwt/jwks.json")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    public void testRsaContract() throws Exception {
        mockMvc.perform(get("/auth/jwt/rsa.json")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.keydata").exists())
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    public void testRefreshContract() throws Exception {
        AuthenticationToken token = new AuthenticationToken();
        token.setUserId(testUser.getId());
        token.setToken(UUID.randomUUID().toString());
        token.setType(JwtAuthService.TYPE_REFRESH_TOKEN);
        token.setActive(true);
        tokenRepository.save(token);

        mockMvc.perform(post("/auth/jwt/refresh.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"user_id\":\"" + testUser.getId()
                        + "\",\"refresh_token\":\"" + token.getToken() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.access_token").exists())
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    @WithMockUser(username = "test@example.com", roles = { "USER" })
    public void testIsAuthenticatedContract() throws Exception {
        mockMvc.perform(get("/auth/is-authenticated.json")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    public void testAuthLogoutContract() throws Exception {
        mockMvc.perform(post("/auth/logout.json")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }
}
