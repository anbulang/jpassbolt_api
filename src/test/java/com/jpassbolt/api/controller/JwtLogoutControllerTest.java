package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.JwtAuthDto;
import com.jpassbolt.api.model.AuthenticationToken;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.JwtAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for POST /auth/jwt/logout.json (PHP JwtLogoutController
 * + RefreshTokenLogoutService): single token revocation, revoke-all with an
 * empty body, and the manual authentication guard (401 for anonymous —
 * /auth/** is permitAll in SecurityConfig).
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class JwtLogoutControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

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

    private User testUser;

    @BeforeEach
    void setUp() {
        // Clear in reverse FK-dependency order (see UsersControllerTest)
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();

        testUser = new User();
        testUser.setUsername("test@example.com");
        testUser.setRoleId("user");
        testUser.setActive(true);
        testUser.setDeleted(false);
        userRepository.save(testUser);
    }

    @Test
    void testLogoutSingleToken_DeactivatesIt() throws Exception {
        AuthenticationToken token = seedRefreshToken(testUser.getId());
        AuthenticationToken untouched = seedRefreshToken(testUser.getId());

        mockMvc.perform(post("/auth/jwt/logout.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(JwtAuthDto.LogoutRequest.builder()
                        .refreshToken(token.getToken())
                        .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"));

        assertThat(tokenRepository.findByToken(token.getToken()).orElseThrow().getActive()).isFalse();
        // Only the named session is revoked
        assertThat(tokenRepository.findByToken(untouched.getToken()).orElseThrow().getActive()).isTrue();
    }

    @Test
    void testLogoutWithEmptyBody_RevokesAllSessions() throws Exception {
        AuthenticationToken token1 = seedRefreshToken(testUser.getId());
        AuthenticationToken token2 = seedRefreshToken(testUser.getId());

        mockMvc.perform(post("/auth/jwt/logout.json")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"));

        assertThat(tokenRepository.findByToken(token1.getToken()).orElseThrow().getActive()).isFalse();
        assertThat(tokenRepository.findByToken(token2.getToken()).orElseThrow().getActive()).isFalse();
    }

    @Test
    void testLogoutWithUnknownToken_Returns400() throws Exception {
        mockMvc.perform(post("/auth/jwt/logout.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(JwtAuthDto.LogoutRequest.builder()
                        .refreshToken(UUID.randomUUID().toString())
                        .build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testLogoutWithInvalidUuid_Returns400() throws Exception {
        mockMvc.perform(post("/auth/jwt/logout.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(JwtAuthDto.LogoutRequest.builder()
                        .refreshToken("not-a-uuid")
                        .build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testLogoutWithOtherUsersToken_Returns400() throws Exception {
        User other = new User();
        other.setUsername("other@example.com");
        other.setRoleId("user");
        other.setActive(true);
        other.setDeleted(false);
        userRepository.save(other);
        AuthenticationToken otherToken = seedRefreshToken(other.getId());

        mockMvc.perform(post("/auth/jwt/logout.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(JwtAuthDto.LogoutRequest.builder()
                        .refreshToken(otherToken.getToken())
                        .build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"));

        assertThat(tokenRepository.findByToken(otherToken.getToken()).orElseThrow().getActive()).isTrue();
    }

    @Test
    @WithAnonymousUser
    void testLogoutUnauthenticated_Returns401() throws Exception {
        mockMvc.perform(post("/auth/jwt/logout.json")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    private AuthenticationToken seedRefreshToken(String userId) {
        AuthenticationToken token = new AuthenticationToken();
        token.setUserId(userId);
        token.setToken(UUID.randomUUID().toString());
        token.setType(JwtAuthService.TYPE_REFRESH_TOKEN);
        token.setActive(true);
        return tokenRepository.save(token);
    }
}
