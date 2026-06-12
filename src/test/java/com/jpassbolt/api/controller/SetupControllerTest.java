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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.LocalDateTime;
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
 * Integration tests for the public setup endpoints:
 * GET /setup/start/{userId}/{tokenId}.json and
 * PUT|POST /setup/complete/{userId}.json.
 *
 * <p>
 * No class-level @WithMockUser — these endpoints are guest-only and must be
 * reachable anonymously (SecurityConfig whitelists "/setup/**").
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SetupControllerTest {

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
    private User pendingUser;
    private AuthenticationToken registerToken;

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

        pendingUser = new User();
        pendingUser.setUsername("betty@example.com");
        pendingUser.setRoleId(userRole.getId());
        pendingUser.setActive(false);
        pendingUser.setDeleted(false);
        userRepository.save(pendingUser);

        Profile profile = new Profile();
        profile.setUserId(pendingUser.getId());
        profile.setFirstName("Betty");
        profile.setLastName("Holberton");
        profileRepository.save(profile);

        registerToken = new AuthenticationToken();
        registerToken.setUserId(pendingUser.getId());
        registerToken.setToken(UUID.randomUUID().toString());
        registerToken.setType("register");
        registerToken.setActive(true);
        authenticationTokenRepository.save(registerToken);
    }

    private String startUrl(String userId, String token) {
        return "/setup/start/" + userId + "/" + token + ".json";
    }

    private String completeUrl(String userId) {
        return "/setup/complete/" + userId + ".json";
    }

    private String completePayload(String token, String armoredKey) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "authentication_token", Map.of("token", token),
                "gpgkey", Map.of("armored_key", armoredKey)));
    }

    /**
     * Age the register token past the expiry window via raw SQL — the
     * created column is updatable=false, repository.save can never touch it
     * (BaseEntity @PrePersist only fills created on insert).
     */
    private void ageToken(String tokenId, int days) {
        jdbcTemplate.update("UPDATE authentication_tokens SET created = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(days)), tokenId);
    }

    // ------------------------------------------------------------------
    // GET /setup/start
    // ------------------------------------------------------------------

    @Test
    void testStart_Success() throws Exception {
        mockMvc.perform(get(startUrl(pendingUser.getId(), registerToken.getToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.user.username").value("betty@example.com"))
                .andExpect(jsonPath("$.body.user.active").value(false))
                .andExpect(jsonPath("$.body.user.profile.first_name").value("Betty"))
                .andExpect(jsonPath("$.body.user.role.name").value("user"));

        // start is read-only
        assertThat(userRepository.findById(pendingUser.getId()).orElseThrow().getActive()).isFalse();
        assertThat(authenticationTokenRepository.findById(registerToken.getId())
                .orElseThrow().getActive()).isTrue();
    }

    @Test
    void testStart_AlreadyActiveUser_BadRequest() throws Exception {
        pendingUser.setActive(true);
        userRepository.save(pendingUser);

        mockMvc.perform(get(startUrl(pendingUser.getId(), registerToken.getToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The user does not exist or is already active or is disabled."));
    }

    @Test
    void testStart_WrongTokenType_BadRequest() throws Exception {
        registerToken.setType("login");
        authenticationTokenRepository.save(registerToken);

        mockMvc.perform(get(startUrl(pendingUser.getId(), registerToken.getToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The authentication token is not valid."));
    }

    @Test
    void testStart_InactiveToken_BadRequest() throws Exception {
        registerToken.setActive(false);
        authenticationTokenRepository.save(registerToken);

        mockMvc.perform(get(startUrl(pendingUser.getId(), registerToken.getToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The authentication token is not valid."));
    }

    @Test
    void testStart_ExpiredToken_BadRequest() throws Exception {
        // Default register token expiry is 10 days.
        ageToken(registerToken.getId(), 11);

        mockMvc.perform(get(startUrl(pendingUser.getId(), registerToken.getToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The authentication token is not valid."));
    }

    @Test
    void testStart_InvalidUserUuid_BadRequest() throws Exception {
        mockMvc.perform(get(startUrl("not-a-uuid", registerToken.getToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The user identifier should be a valid UUID."));
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = { "USER" })
    void testStart_AuthenticatedCaller_Forbidden() throws Exception {
        mockMvc.perform(get(startUrl(pendingUser.getId(), registerToken.getToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.message").value(
                        "Only guests are allowed to start setup."));
    }

    // ------------------------------------------------------------------
    // PUT|POST /setup/complete
    // ------------------------------------------------------------------

    @Test
    void testComplete_Success_ActivatesUserAndStoresKey() throws Exception {
        String armoredKey = gpgService.getServerPublicKey();
        String expectedFingerprint = gpgService.getServerKeyFingerprint();

        mockMvc.perform(put(completeUrl(pendingUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(completePayload(registerToken.getToken(), armoredKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.message").value("The setup was completed successfully."))
                .andExpect(jsonPath("$.body").value(nullValue()));

        assertThat(userRepository.findById(pendingUser.getId()).orElseThrow().getActive()).isTrue();
        assertThat(authenticationTokenRepository.findById(registerToken.getId())
                .orElseThrow().getActive()).isFalse();

        List<GpgKey> keys = gpgKeyRepository.findByUserId(pendingUser.getId());
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).getFingerprint()).isEqualTo(expectedFingerprint);
        assertThat(keys.get(0).getKeyId())
                .isEqualTo(GpgTestHelper.fingerprintToKeyId(expectedFingerprint));
        assertThat(keys.get(0).getDeleted()).isFalse();
    }

    @Test
    void testComplete_PostMethodAlsoAccepted() throws Exception {
        // PHP registers PUT|POST on the same path (routes.php L334).
        mockMvc.perform(post(completeUrl(pendingUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(completePayload(registerToken.getToken(), gpgService.getServerPublicKey())))
                .andExpect(status().isOk());
    }

    @Test
    void testComplete_LegacyTokenKeyAccepted() throws Exception {
        // Pre-v3.6 plugins send "authenticationtoken" instead of
        // "authentication_token".
        String body = objectMapper.writeValueAsString(Map.of(
                "authenticationtoken", Map.of("token", registerToken.getToken()),
                "gpgkey", Map.of("armored_key", gpgService.getServerPublicKey())));

        mockMvc.perform(put(completeUrl(pendingUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void testComplete_MissingToken_BadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "gpgkey", Map.of("armored_key", gpgService.getServerPublicKey())));

        mockMvc.perform(put(completeUrl(pendingUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "An authentication token should be provided."));
    }

    @Test
    void testComplete_TokenNotUuid_BadRequest() throws Exception {
        mockMvc.perform(put(completeUrl(pendingUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(completePayload("not-a-uuid", gpgService.getServerPublicKey())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The authentication token should be a valid UUID."));
    }

    @Test
    void testComplete_UnknownToken_BadRequest() throws Exception {
        mockMvc.perform(put(completeUrl(pendingUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(completePayload(UUID.randomUUID().toString(),
                        gpgService.getServerPublicKey())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The authentication token is not valid."));
    }

    @Test
    void testComplete_MissingArmoredKey_BadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "authentication_token", Map.of("token", registerToken.getToken())));

        mockMvc.perform(put(completeUrl(pendingUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "An OpenPGP key must be provided."));
    }

    @Test
    void testComplete_GarbageArmoredKey_BadRequest() throws Exception {
        mockMvc.perform(put(completeUrl(pendingUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(completePayload(registerToken.getToken(), "definitely not a pgp key")))
                .andExpect(status().isBadRequest());

        // Nothing was activated
        assertThat(userRepository.findById(pendingUser.getId()).orElseThrow().getActive()).isFalse();
    }

    @Test
    void testComplete_DuplicateFingerprint_BadRequest() throws Exception {
        // Another (non-deleted) user already registered this fingerprint.
        User other = new User();
        other.setUsername("other@example.com");
        other.setRoleId(userRole.getId());
        other.setActive(true);
        other.setDeleted(false);
        userRepository.save(other);

        String fingerprint = gpgService.getServerKeyFingerprint();
        GpgKey existing = new GpgKey();
        existing.setUserId(other.getId());
        existing.setArmoredKey(gpgService.getServerPublicKey());
        existing.setUid("Other <other@example.com>");
        existing.setKeyId(GpgTestHelper.fingerprintToKeyId(fingerprint));
        existing.setFingerprint(fingerprint);
        existing.setType("RSA");
        existing.setBits(4096);
        existing.setDeleted(false);
        gpgKeyRepository.save(existing);

        mockMvc.perform(put(completeUrl(pendingUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(completePayload(registerToken.getToken(), gpgService.getServerPublicKey())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The OpenPGP key fingerprint is already in use."));
    }

    @Test
    void testComplete_ExpiredToken_BadRequest() throws Exception {
        ageToken(registerToken.getId(), 11);

        mockMvc.perform(put(completeUrl(pendingUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(completePayload(registerToken.getToken(), gpgService.getServerPublicKey())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The authentication token is not valid."));
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = { "USER" })
    void testComplete_AuthenticatedCaller_Forbidden() throws Exception {
        mockMvc.perform(put(completeUrl(pendingUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(completePayload(registerToken.getToken(), gpgService.getServerPublicKey())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.message").value(
                        "Only guests are allowed to complete setup."));
    }

    @Test
    void testComplete_AlreadyActiveUser_BadRequest() throws Exception {
        pendingUser.setActive(true);
        userRepository.save(pendingUser);

        mockMvc.perform(put(completeUrl(pendingUser.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(completePayload(registerToken.getToken(), gpgService.getServerPublicKey())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The user does not exist, is already active or has been deleted."));
    }
}
