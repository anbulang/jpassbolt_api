package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
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
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for POST /auth/jwt/refresh.json (PHP
 * RefreshTokenController + RefreshTokenRenewalService): rotating refresh
 * token, payload and cookie modes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwtRefreshControllerTest {

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
    void testRefreshPayloadMode_RotatesTokenAndReturnsAccessToken() throws Exception {
        AuthenticationToken oldToken = seedRefreshToken(testUser.getId());

        MvcResult result = mockMvc.perform(post("/auth/jwt/refresh.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(JwtAuthDto.RefreshRequest.builder()
                        .userId(testUser.getId())
                        .refreshToken(oldToken.getToken())
                        .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.access_token").exists())
                .andExpect(header().exists("Set-Cookie"))
                .andReturn();

        // The old refresh token is consumed (rotation), never deleted
        AuthenticationToken consumed = tokenRepository.findByToken(oldToken.getToken()).orElseThrow();
        assertThat(consumed.getActive()).isFalse();

        // The rotated refresh token travels back via the Set-Cookie header
        Cookie cookie = result.getResponse().getCookie("refresh_token");
        assertThat(cookie).isNotNull();
        var newRow = tokenRepository.findByTokenAndType(cookie.getValue(), JwtAuthService.TYPE_REFRESH_TOKEN);
        assertThat(newRow).isPresent();
        assertThat(newRow.get().getActive()).isTrue();
        assertThat(newRow.get().getUserId()).isEqualTo(testUser.getId());

        // The new RS256 access token must authenticate a request
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String accessToken = body.at("/body/access_token").asText();
        mockMvc.perform(get("/auth/is-authenticated.json")
                .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void testRefreshCookieMode_RotatesToken() throws Exception {
        AuthenticationToken oldToken = seedRefreshToken(testUser.getId());

        mockMvc.perform(post("/auth/jwt/refresh.json")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie(new Cookie("refresh_token", oldToken.getToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.access_token").exists())
                .andExpect(header().exists("Set-Cookie"));

        AuthenticationToken consumed = tokenRepository.findByToken(oldToken.getToken()).orElseThrow();
        assertThat(consumed.getActive()).isFalse();
    }

    @Test
    void testRefreshWithConsumedToken_Returns400() throws Exception {
        AuthenticationToken token = seedRefreshToken(testUser.getId());
        token.setActive(false);
        tokenRepository.save(token);

        mockMvc.perform(post("/auth/jwt/refresh.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(JwtAuthDto.RefreshRequest.builder()
                        .userId(testUser.getId())
                        .refreshToken(token.getToken())
                        .build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testRefreshWithUnknownToken_Returns400() throws Exception {
        mockMvc.perform(post("/auth/jwt/refresh.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(JwtAuthDto.RefreshRequest.builder()
                        .userId(testUser.getId())
                        .refreshToken(UUID.randomUUID().toString())
                        .build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testRefreshWithInvalidUuid_Returns400() throws Exception {
        mockMvc.perform(post("/auth/jwt/refresh.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(JwtAuthDto.RefreshRequest.builder()
                        .userId(testUser.getId())
                        .refreshToken("not-a-uuid")
                        .build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testRefreshWithOtherUsersToken_Returns400() throws Exception {
        User other = new User();
        other.setUsername("other@example.com");
        other.setRoleId("user");
        other.setActive(true);
        other.setDeleted(false);
        userRepository.save(other);
        AuthenticationToken otherToken = seedRefreshToken(other.getId());

        // user_id does not match the token owner — token "not found" (PHP
        // queryRefreshTokenWithUserId scopes by user)
        mockMvc.perform(post("/auth/jwt/refresh.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(JwtAuthDto.RefreshRequest.builder()
                        .userId(testUser.getId())
                        .refreshToken(otherToken.getToken())
                        .build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"));

        // The other user's token must remain untouched
        assertThat(tokenRepository.findByToken(otherToken.getToken()).orElseThrow().getActive()).isTrue();
    }

    @Test
    void testRefreshWithNoTokenAtAll_Returns400() throws Exception {
        mockMvc.perform(post("/auth/jwt/refresh.json")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
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
