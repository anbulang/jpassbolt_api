package com.jpassbolt.api.controller;

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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.Map;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI contract tests for the users CRUD endpoints
 * (/users.json L6166, /users/{userId}.json L6327,
 * /users/{userId}/dry-run.json L6568 in plugin-redoc-0.yaml).
 *
 * <p>
 * The header.action and RFC3339 date-time frictions that previously blocked
 * every assertion are now solved project-wide (ApiResponse emits all 7
 * required header fields incl. action; JacksonConfig serialises LocalDateTime
 * as +00:00 RFC3339), so the DELETE and dry-run assertions — whose 200 body
 * is the nullBody schema (body: null) — are ENABLED.
 * </p>
 *
 * <p>
 * All four assertions (POST / PUT / DELETE / dry-run) are now ENABLED and pass
 * (verified). The single-object users_add / users_update body we return
 * (aligned with PHP/the official example) is accepted by the validator built
 * with withResolveCombinators(true), so the previously-claimed "ARRAY body
 * mismatch" did not actually block validation.
 * </p>
 *
 * <p>
 * The users index/view endpoints are covered by UsersControllerContractTest;
 * the (out-of-spec) setup endpoints by SetupControllerContractTest.
 * </p>
 */
@WithMockUser(username = "admin@example.com", roles = { "USER" })
public class UsersCrudControllerContractTest extends OpenApiComplianceTest {

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
    private User victim;

    @BeforeEach
    void seedData() {
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

        adminRole = new Role();
        adminRole.setName("admin");
        roleRepository.save(adminRole);

        adminUser = createUser("admin@example.com", adminRole.getId());
        victim = createUser("victim@example.com", userRole.getId());
        createProfile(adminUser.getId(), "Admin", "User");
        createProfile(victim.getId(), "Victim", "User");
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
    public void testUsersAddContract() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "new.user@example.com",
                "profile", Map.of("first_name", "New", "last_name", "User")));

        mockMvc.perform(post("/users.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.username").value("new.user@example.com"))
                // Enabled (verified): POST /users.json passes contract validation.
                // The single-object body we return (aligned with PHP/the official
                // example) is accepted by the validator built with
                // withResolveCombinators(true); the envelope is spec-valid. The
                // earlier "spec declares an ARRAY body" reason did not actually block
                // the assertion.
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    public void testUsersUpdateContract() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "profile", Map.of("first_name", "Updated", "last_name", "User")));

        mockMvc.perform(put("/users/" + victim.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.profile.first_name").value("Updated"))
                // Enabled (verified): PUT /users/{id}.json passes contract validation
                // — the single-object response we return is accepted by the validator;
                // the earlier "spec declares an ARRAY body" reason did not actually
                // block the assertion.
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    public void testUsersDeleteContract() throws Exception {
        mockMvc.perform(delete("/users/" + victim.getId() + ".json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
        // Enabled: 200 body is the nullBody schema (body: null) and the header now
        // carries all required fields incl. action, so the response is spec-valid.
    }

    @Test
    public void testUsersDeleteDryRunContract() throws Exception {
        mockMvc.perform(delete("/users/" + victim.getId() + "/dry-run.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
        // Enabled: 200 body is the nullBody schema (body: null) and the header now
        // carries all required fields incl. action, so the response is spec-valid.
    }
}
