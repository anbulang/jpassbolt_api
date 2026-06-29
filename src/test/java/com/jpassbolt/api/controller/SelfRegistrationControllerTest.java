package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.CommentRepository;
import com.jpassbolt.api.repository.FavoriteRepository;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the PUBLIC self-registration endpoints:
 * POST /self-registration/dry-run.json, GET /users/register.json and
 * POST /users/register.json.
 *
 * <p>No class-level {@code @WithMockUser} — these are guest-only and must be
 * reachable anonymously (SecurityConfig whitelists them). Individual tests add
 * {@code @WithMockUser} to assert the authenticated-caller 403. Not
 * {@code @Transactional}: register commits so the gate + createUser + (best-effort,
 * swallowed) AFTER_COMMIT mail run end-to-end, then each test cleans up.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SelfRegistrationControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private ProfileRepository profileRepository;
    @Autowired private AuthenticationTokenRepository authenticationTokenRepository;
    @Autowired private OrganizationSettingRepository organizationSettingRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private FavoriteRepository favoriteRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private SecretRepository secretRepository;
    @Autowired private ResourceRepository resourceRepository;
    @Autowired private GpgKeyRepository gpgKeyRepository;

    private Role userRole;
    private Role adminRole;
    private String adminId;

    @BeforeEach
    void setUp() {
        authenticationTokenRepository.deleteAll();
        favoriteRepository.deleteAll();
        commentRepository.deleteAll();
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        profileRepository.deleteAll();
        gpgKeyRepository.deleteAll();
        organizationSettingRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        userRole = new Role();
        userRole.setName(Role.USER);
        userRole = roleRepository.save(userRole);

        adminRole = new Role();
        adminRole.setName(Role.ADMIN);
        adminRole = roleRepository.save(adminRole);

        User admin = new User();
        admin.setUsername("admin@passbolt.com");
        admin.setRoleId(adminRole.getId());
        admin.setActive(true);
        admin.setDeleted(false);
        adminId = userRepository.save(admin).getId();
    }

    /** Insert the selfRegistration org-setting row directly to open registration. */
    private void openWith(String allowedDomainsJson) {
        OrganizationSetting s = new OrganizationSetting();
        s.setProperty("selfRegistration");
        s.setPropertyId(UUID.randomUUID().toString());
        s.setValue("{\"provider\":\"email_domains\",\"data\":{\"allowed_domains\":["
                + allowedDomainsJson + "]}}");
        s.setCreatedBy(adminId);
        s.setModifiedBy(adminId);
        organizationSettingRepository.save(s);
    }

    private User saveActiveUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setRoleId(userRole.getId());
        u.setActive(true);
        u.setDeleted(false);
        return userRepository.save(u);
    }

    // ---- dry-run ----

    @Test
    void dryRun_allowedDomain_returns200() throws Exception {
        openWith("\"passbolt.com\"");
        mockMvc.perform(post("/self-registration/dry-run.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "new@passbolt.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"));
    }

    @Test
    void dryRun_disabled_returns403() throws Exception {
        // no settings row -> registration closed
        mockMvc.perform(post("/self-registration/dry-run.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "new@passbolt.com"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void dryRun_domainNotAllowed_returns422() throws Exception {
        openWith("\"passbolt.com\"");
        mockMvc.perform(post("/self-registration/dry-run.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "intruder@evil.com"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void dryRun_alreadyRegistered_returns403() throws Exception {
        openWith("\"passbolt.com\"");
        saveActiveUser("taken@passbolt.com");
        mockMvc.perform(post("/self-registration/dry-run.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "taken@passbolt.com"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin@passbolt.com", roles = { "ADMIN" })
    void dryRun_authenticatedCaller_returns403() throws Exception {
        openWith("\"passbolt.com\"");
        mockMvc.perform(post("/self-registration/dry-run.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "new@passbolt.com"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.message").value("Only guests are allowed to self register."));
    }

    // ---- GET register (is-open probe) ----

    @Test
    void registerGet_open_returns200() throws Exception {
        openWith("\"passbolt.com\"");
        mockMvc.perform(get("/users/register.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"));
    }

    @Test
    void registerGet_closed_returns404() throws Exception {
        mockMvc.perform(get("/users/register.json"))
                .andExpect(status().isNotFound());
    }

    // ---- POST register (actual sign-up) ----

    @Test
    void register_success_createsInactiveUserWithRegisterToken() throws Exception {
        openWith("\"passbolt.com\"");
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "newbie@passbolt.com",
                "profile", Map.of("first_name", "New", "last_name", "Bie")));

        mockMvc.perform(post("/users/register.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.username").value("newbie@passbolt.com"))
                .andExpect(jsonPath("$.body.active").value(false));

        User created = userRepository.findByUsername("newbie@passbolt.com").orElseThrow();
        assertThat(created.getActive()).isFalse();
        assertThat(created.getRoleId()).isEqualTo(userRole.getId());
        assertThat(authenticationTokenRepository
                .findAllByUserIdAndTypeAndActiveTrue(created.getId(), "register")).isNotEmpty();
    }

    @Test
    void register_forcesUserRoleIgnoringRequestedAdminRole() throws Exception {
        // Privilege-escalation guard: a guest cannot self-assign the admin role.
        openWith("\"passbolt.com\"");
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "sneaky@passbolt.com",
                "role_id", adminRole.getId(),
                "profile", Map.of("first_name", "Sne", "last_name", "Aky")));

        mockMvc.perform(post("/users/register.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk());

        User created = userRepository.findByUsername("sneaky@passbolt.com").orElseThrow();
        assertThat(created.getRoleId()).isEqualTo(userRole.getId()); // NOT adminRole
    }

    @Test
    void register_domainNotAllowed_returns422() throws Exception {
        openWith("\"passbolt.com\"");
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "intruder@evil.com",
                "profile", Map.of("first_name", "In", "last_name", "Truder")));

        mockMvc.perform(post("/users/register.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isUnprocessableEntity());

        assertThat(userRepository.findByUsername("intruder@evil.com")).isEmpty();
    }

    @Test
    void register_duplicateEmail_returns403() throws Exception {
        openWith("\"passbolt.com\"");
        saveActiveUser("taken@passbolt.com");
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "taken@passbolt.com",
                "profile", Map.of("first_name", "Ta", "last_name", "Ken")));

        mockMvc.perform(post("/users/register.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin@passbolt.com", roles = { "ADMIN" })
    void register_authenticatedCaller_returns403() throws Exception {
        openWith("\"passbolt.com\"");
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "new@passbolt.com",
                "profile", Map.of("first_name", "New", "last_name", "User")));

        mockMvc.perform(post("/users/register.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isForbidden());
    }
}
