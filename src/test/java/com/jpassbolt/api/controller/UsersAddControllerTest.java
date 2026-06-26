package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for POST /users.json (admin invite-style creation).
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin@example.com", roles = { "USER" })
class UsersAddControllerTest {

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
    private Role guestRole;
    private User adminUser;
    private User plainUser;

    @BeforeEach
    void setUp() {
        // Reverse FK order cleanup
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
        guestRole = createRole("guest");

        adminUser = createUser("admin@example.com", adminRole.getId());
        plainUser = createUser("plain@example.com", userRole.getId());
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

    private String payload(String username) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "username", username,
                "profile", Map.of("first_name", "New", "last_name", "User")));
    }

    @Test
    void testAddUser_Success_DefaultsToUserRole() throws Exception {
        MvcResult result = mockMvc.perform(post("/users.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("new.user@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.message").value(
                        "The user was successfully added. This user now need to complete the setup."))
                .andExpect(jsonPath("$.body.id").isNotEmpty())
                .andExpect(jsonPath("$.body.username").value("new.user@example.com"))
                .andExpect(jsonPath("$.body.active").value(false))
                .andExpect(jsonPath("$.body.deleted").value(false))
                .andExpect(jsonPath("$.body.gpgkey").value(nullValue()))
                .andExpect(jsonPath("$.body.profile.first_name").value("New"))
                .andExpect(jsonPath("$.body.profile.avatar.url.medium").isNotEmpty())
                .andExpect(jsonPath("$.body.role.name").value("user"))
                .andReturn();

        String newUserId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("body").path("id").asText();
        // A register token must have been created in the same transaction
        assertThat(authenticationTokenRepository
                .findByUserIdAndTypeAndActiveTrue(newUserId, "register")).isPresent();
        assertThat(profileRepository.findByUserId(newUserId)).isPresent();
    }

    @Test
    void testAddUser_ExplicitAdminRole() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "boss@example.com",
                "role_id", adminRole.getId(),
                "profile", Map.of("first_name", "Big", "last_name", "Boss")));

        mockMvc.perform(post("/users.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.role.name").value("admin"));
    }

    @Test
    void testAddUser_UsernameIsLowercased() throws Exception {
        mockMvc.perform(post("/users.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("Ada@Example.COM")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.username").value("ada@example.com"));

        assertThat(userRepository.findByUsernameAndDeletedFalse("ada@example.com")).isPresent();
    }

    @Test
    @WithMockUser(username = "plain@example.com", roles = { "USER" })
    void testAddUser_NonAdmin_Forbidden() throws Exception {
        mockMvc.perform(post("/users.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("new.user@example.com")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.message").value(
                        "Only administrators can add new users."));
    }

    @Test
    @WithAnonymousUser
    void testAddUser_Unauthenticated_Forbidden() throws Exception {
        // No authenticationEntryPoint is configured: Spring Security returns
        // 403 for anonymous callers instead of the contract's 401 — known
        // project-wide deviation (same handling as the roles blueprint).
        mockMvc.perform(post("/users.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("new.user@example.com")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testAddUser_MissingUsername_BadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "profile", Map.of("first_name", "New", "last_name", "User")));

        mockMvc.perform(post("/users.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("Could not validate user data."))
                .andExpect(jsonPath("$.body.username").exists());
    }

    @Test
    void testAddUser_InvalidEmail_BadRequest() throws Exception {
        mockMvc.perform(post("/users.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("not-an-email")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("Could not validate user data."))
                .andExpect(jsonPath("$.body.username.email").exists());
    }

    @Test
    void testAddUser_MissingProfile_BadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", "new.user@example.com"));

        mockMvc.perform(post("/users.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.profile").exists());
    }

    @Test
    void testAddUser_MissingFirstName_BadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "new.user@example.com",
                "profile", Map.of("last_name", "User")));

        mockMvc.perform(post("/users.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.profile.first_name").exists());
    }

    @Test
    void testAddUser_DuplicateUsername_BadRequest() throws Exception {
        mockMvc.perform(post("/users.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("plain@example.com")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("Could not validate user data."))
                .andExpect(jsonPath("$.body.username.uniqueUsername").value(
                        "The username is already in use."));
    }

    @Test
    void testAddUser_DuplicateOfSoftDeletedUser_Succeeds() throws Exception {
        // Uniqueness only applies to deleted=false users (PHP
        // isUniqueUsername): re-inviting a soft-deleted username must work.
        // Relies on User.username no longer carrying unique=true.
        plainUser.setDeleted(true);
        userRepository.save(plainUser);

        mockMvc.perform(post("/users.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload("plain@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.username").value("plain@example.com"));
    }

    @Test
    void testAddUser_GuestRole_BadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "new.user@example.com",
                "role_id", guestRole.getId(),
                "profile", Map.of("first_name", "New", "last_name", "User")));

        mockMvc.perform(post("/users.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.role_id").exists());
    }
}
