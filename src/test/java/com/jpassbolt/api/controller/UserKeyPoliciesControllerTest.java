package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.AuthenticationToken;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.SetupService;
import com.jpassbolt.api.service.UserKeyPoliciesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link UserKeyPoliciesController}: on
 * GET /user-key-policies/settings.json any authenticated user gets the
 * default policy and anonymous is a 401; on the setup form
 * (GET /setup/user-key-policies/settings.json, the route the official
 * browser extension calls before generating the user key pair) a guest gets
 * the policy with a valid user_id + register token pair. The
 * invalid/override config handling lives in {@link UserKeyPoliciesService}
 * (plain constructor args), so it is unit-tested here directly without
 * spinning extra Spring contexts.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserKeyPoliciesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private AuthenticationTokenRepository authenticationTokenRepository;

    private String userId;

    @BeforeEach
    void setUp() {
        // Clear resource-graph rows a sibling test may have left in the
        // shared in-memory DB before deleting users: resources.created_by
        // -> users.id is an enforced FK, so orphan resources would block
        // userRepository.deleteAll().
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        authenticationTokenRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role userRole = new Role();
        userRole.setName(Role.USER);
        userRole = roleRepository.save(userRole);
        User regular = new User();
        regular.setUsername("user@passbolt.com");
        regular.setRoleId(userRole.getId());
        regular.setActive(true);
        regular.setDeleted(false);
        userId = userRepository.save(regular).getId();
    }

    private String createRegisterToken() {
        AuthenticationToken token = new AuthenticationToken();
        token.setUserId(userId);
        token.setToken(java.util.UUID.randomUUID().toString());
        token.setType(SetupService.TOKEN_TYPE_REGISTER);
        token.setActive(true);
        return authenticationTokenRepository.save(token).getToken();
    }

    @Test
    @WithMockUser(username = "user@passbolt.com", roles = { "USER" })
    void testGet_Authenticated_ReturnsDefaultPolicy() throws Exception {
        mockMvc.perform(get("/user-key-policies/settings.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.code").value(200))
                .andExpect(jsonPath("$.header.url").value("/user-key-policies/settings.json"))
                .andExpect(jsonPath("$.header.message").value("The operation was successful."))
                .andExpect(jsonPath("$.body.preferred_key_type").value("curve"))
                .andExpect(jsonPath("$.body.preferred_key_size").value((Object) null))
                .andExpect(jsonPath("$.body.preferred_key_curve")
                        .value("curve25519_legacy+ed25519_legacy"))
                .andExpect(jsonPath("$.body.source").value("default"));
    }

    @Test
    void testGet_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/user-key-policies/settings.json"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Setup form (guest access with user_id + register token)
    // ------------------------------------------------------------------

    @Test
    void testSetupGet_GuestWithValidRegisterToken_ReturnsPolicy() throws Exception {
        String token = createRegisterToken();

        mockMvc.perform(get("/setup/user-key-policies/settings.json")
                        .param("user_id", userId)
                        .param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.url")
                        .value("/setup/user-key-policies/settings.json"))
                .andExpect(jsonPath("$.body.preferred_key_type").value("curve"))
                .andExpect(jsonPath("$.body.preferred_key_curve")
                        .value("curve25519_legacy+ed25519_legacy"))
                .andExpect(jsonPath("$.body.source").value("default"));
    }

    @Test
    void testSetupGet_GuestWithoutCredentials_Returns401() throws Exception {
        mockMvc.perform(get("/setup/user-key-policies/settings.json"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testSetupGet_GuestWithNonUuidUserId_Returns400() throws Exception {
        mockMvc.perform(get("/setup/user-key-policies/settings.json")
                        .param("user_id", "not-a-uuid")
                        .param("token", java.util.UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message")
                        .value("The user ID must be a valid UUID."));
    }

    @Test
    void testSetupGet_GuestWithNonUuidToken_Returns400() throws Exception {
        mockMvc.perform(get("/setup/user-key-policies/settings.json")
                        .param("user_id", userId)
                        .param("token", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message")
                        .value("The authentication token must be a valid UUID."));
    }

    @Test
    void testSetupGet_GuestWithUnknownToken_Returns400() throws Exception {
        mockMvc.perform(get("/setup/user-key-policies/settings.json")
                        .param("user_id", userId)
                        .param("token", java.util.UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testSetupGet_GuestWithInactiveToken_Returns400() throws Exception {
        String token = createRegisterToken();
        AuthenticationToken saved = authenticationTokenRepository.findAll().get(0);
        saved.setActive(false);
        authenticationTokenRepository.save(saved);

        mockMvc.perform(get("/setup/user-key-policies/settings.json")
                        .param("user_id", userId)
                        .param("token", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "user@passbolt.com", roles = { "USER" })
    void testSetupGet_AuthenticatedWithoutParams_ReturnsPolicy() throws Exception {
        mockMvc.perform(get("/setup/user-key-policies/settings.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.preferred_key_type").value("curve"));
    }

    @Test
    @WithMockUser(username = "user@passbolt.com", roles = { "USER" })
    void testSetupGet_AuthenticatedWithGuestParams_Returns400() throws Exception {
        // Session confusion, mirroring PHP: signed-in callers must not also
        // send the guest user_id/token pair.
        mockMvc.perform(get("/setup/user-key-policies/settings.json")
                        .param("user_id", userId)
                        .param("token", createRegisterToken()))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // Service-level config handling (no extra Spring context needed)
    // ------------------------------------------------------------------

    @Test
    void testService_ValidRsaConfig_IsReturned() {
        UserKeyPoliciesService service = new UserKeyPoliciesService("rsa", "4096", "");

        Map<String, Object> settings = service.getSettings();
        assertThat(settings.get("preferred_key_type")).isEqualTo("rsa");
        assertThat(settings.get("preferred_key_size")).isEqualTo(4096);
        assertThat(settings.get("preferred_key_curve")).isNull();
        assertThat(settings.get("source")).isEqualTo("default");
    }

    @Test
    void testService_InvalidConfig_FallsBackToDefaults() {
        // rsa with an unsupported size, and an unknown type: both degrade to
        // the ECC defaults like the PHP form-validation fallback.
        for (UserKeyPoliciesService service : new UserKeyPoliciesService[] {
                new UserKeyPoliciesService("rsa", "1024", ""),
                new UserKeyPoliciesService("dsa", "3072", null),
                new UserKeyPoliciesService("curve", "3072", "curve25519_legacy+ed25519_legacy"),
        }) {
            Map<String, Object> settings = service.getSettings();
            assertThat(settings.get("preferred_key_type")).isEqualTo("curve");
            assertThat(settings.get("preferred_key_size")).isNull();
            assertThat(settings.get("preferred_key_curve"))
                    .isEqualTo("curve25519_legacy+ed25519_legacy");
            assertThat(settings.get("source")).isEqualTo("default");
        }
    }
}
