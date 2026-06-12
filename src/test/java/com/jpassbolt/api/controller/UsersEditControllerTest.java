package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for PUT|POST /users/{id}.json (whitelist edit).
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin@example.com", roles = { "USER" })
class UsersEditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    private Role adminRole;
    private Role userRole;
    private User adminUser;
    private User plainUser;

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

        userRole = createRole("user");
        adminRole = createRole("admin");

        adminUser = createUser("admin@example.com", adminRole.getId());
        plainUser = createUser("plain@example.com", userRole.getId());
        createProfile(adminUser.getId(), "Admin", "User");
        createProfile(plainUser.getId(), "Plain", "User");
    }

    private Role createRole(String name) {
        Role role = new Role();
        role.setName(name);
        return roleRepository.save(role);
    }

    private User createUser(String username, String roleId) {
        User user = new User();
        user.setUsername(username);
        user.setRoleId(roleId);
        user.setActive(true);
        user.setDeleted(false);
        return userRepository.save(user);
    }

    private Profile createProfile(String userId, String firstName, String lastName) {
        Profile profile = new Profile();
        profile.setUserId(userId);
        profile.setFirstName(firstName);
        profile.setLastName(lastName);
        return profileRepository.save(profile);
    }

    @Test
    @WithMockUser(username = "plain@example.com", roles = { "USER" })
    void testEditOwnProfile_Success() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "profile", Map.of("first_name", "Updated", "last_name", "Name")));

        mockMvc.perform(put("/users/" + plainUser.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.message").value("The user has been updated successfully."))
                .andExpect(jsonPath("$.body.profile.first_name").value("Updated"))
                .andExpect(jsonPath("$.body.profile.last_name").value("Name"));
    }

    @Test
    void testAdminChangesRole_Success() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("role_id", adminRole.getId()));

        mockMvc.perform(put("/users/" + plainUser.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.role.name").value("admin"));
    }

    @Test
    void testAdminDisablesOtherUser_Success() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("disabled", "2026-01-01T00:00:00"));

        mockMvc.perform(put("/users/" + plainUser.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(plainUser.getId()).orElseThrow().getDisabled())
                .isNotNull();
    }

    @Test
    void testAdminDisablingSelf_SilentlyIgnored() throws Exception {
        // PHP parity: an admin disabling themselves is dropped without error.
        String body = objectMapper.writeValueAsString(Map.of(
                "disabled", "2026-01-01T00:00:00",
                "profile", Map.of("first_name", "Still", "last_name", "Admin")));

        mockMvc.perform(put("/users/" + adminUser.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(adminUser.getId()).orElseThrow().getDisabled())
                .isNull();
    }

    @Test
    void testPostMethodAlsoAccepted() throws Exception {
        // PHP registers PUT|POST on the same path (routes.php L273).
        String body = objectMapper.writeValueAsString(Map.of(
                "profile", Map.of("first_name", "ViaPost", "last_name", "User")));

        mockMvc.perform(post("/users/" + plainUser.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.profile.first_name").value("ViaPost"));
    }

    @Test
    void testUsernameAndActive_SilentlyDropped() throws Exception {
        // username/active are never updatable: unknown JSON keys are dropped
        // by Jackson, mirroring PHP's accessibleFields whitelist.
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", "hax@example.com");
        payload.put("active", false);
        payload.put("profile", Map.of("first_name", "Kept", "last_name", "User"));

        mockMvc.perform(put("/users/" + plainUser.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.username").value("plain@example.com"))
                .andExpect(jsonPath("$.body.active").value(true));

        User reloaded = userRepository.findById(plainUser.getId()).orElseThrow();
        assertThat(reloaded.getUsername()).isEqualTo("plain@example.com");
        assertThat(reloaded.getActive()).isTrue();
    }

    @Test
    @WithMockUser(username = "plain@example.com", roles = { "USER" })
    void testNonAdminEditingOther_Forbidden() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "profile", Map.of("first_name", "Evil", "last_name", "Edit")));

        mockMvc.perform(put("/users/" + adminUser.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.message").value(
                        "You are not authorized to access that location."));
    }

    @Test
    @WithMockUser(username = "plain@example.com", roles = { "USER" })
    void testNonAdminEditingOwnRole_Forbidden() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("role_id", adminRole.getId()));

        mockMvc.perform(put("/users/" + plainUser.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.message").value(
                        "You are not authorized to edit the role."));
    }

    @Test
    void testPayloadWithGpgkey_BadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "gpgkey", Map.of("armored_key", "xxx")));

        mockMvc.perform(put("/users/" + plainUser.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "Updating the OpenPGP key is not allowed."));
    }

    @Test
    void testPayloadWithGroupsUser_BadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "groups_user", Map.of("group_id", "x")));

        mockMvc.perform(put("/users/" + plainUser.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "Updating the groups is not allowed."));
    }

    @Test
    void testEmptyPayload_BadRequest() throws Exception {
        mockMvc.perform(put("/users/" + plainUser.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("Some user data should be provided."));
    }

    @Test
    void testInvalidUuid_BadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "profile", Map.of("first_name", "X", "last_name", "Y")));

        mockMvc.perform(put("/users/not-a-uuid.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The user identifier should be a valid UUID."));
    }

    @Test
    void testTargetDoesNotExist_BadRequest() throws Exception {
        // NOTE: PHP throws BadRequestException (400) here, NOT the 404 the
        // OpenAPI spec declares — the plugin relies on the 400.
        String body = objectMapper.writeValueAsString(Map.of(
                "profile", Map.of("first_name", "X", "last_name", "Y")));

        mockMvc.perform(put("/users/00000000-0000-0000-0000-000000000000.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The user does not exist or has been deleted."));
    }

    @Test
    void testSoftDeletedTarget_BadRequest() throws Exception {
        plainUser.setDeleted(true);
        userRepository.save(plainUser);

        String body = objectMapper.writeValueAsString(Map.of(
                "profile", Map.of("first_name", "X", "last_name", "Y")));

        mockMvc.perform(put("/users/" + plainUser.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The user does not exist or has been deleted."));
    }
}
