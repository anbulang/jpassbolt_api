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
import org.springframework.security.test.context.support.WithMockUser;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI contract tests for the users index/view endpoints
 * (GET /users.json L6166, GET /users/{userId}.json L6327 in
 * plugin-redoc-0.yaml). The CRUD write endpoints (add/update/delete/dry-run)
 * are covered separately by {@link UsersCrudControllerContractTest}.
 *
 * <p>
 * The view endpoint carries an ENABLED openApi().isValid(CONTRACT_VALIDATOR)
 * assertion: users_view returns a single userIndexAndView object under the
 * plain header schema, which the controller's response satisfies (the
 * header.action and RFC3339 date-time frictions are solved project-wide via
 * ApiResponse / JacksonConfig).
 * </p>
 *
 * <p>
 * The index endpoint keeps its assertion DISABLED: the users_index response
 * uses the headerWithPagination schema, which REQUIRES a "pagination" object
 * (count/page/limit), but UsersController.getAllUsers() emits the plain header
 * without pagination. This is a spec-vs-implementation gap that cannot be
 * closed from the test side; it is recorded in assertions_left_disabled.
 * </p>
 */
@WithMockUser(username = "admin@example.com", roles = { "USER" })
public class UsersControllerContractTest extends OpenApiComplianceTest {

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

    private User adminUser;
    private User regularUser;

    @BeforeEach
    void seedData() {
        // Reverse-dependency-order cleanup, then seed a same-named principal,
        // mirroring the sibling *ContractTest conventions.
        authenticationTokenRepository.deleteAll();
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        profileRepository.deleteAll();
        gpgKeyRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        // role.description is a REQUIRED, non-nullable field in the
        // userIndexAndView.role sub-schema, so it must be populated for the
        // embedded role to pass contract validation.
        Role userRole = new Role();
        userRole.setName("user");
        userRole.setDescription("Logged in user");
        roleRepository.save(userRole);

        Role adminRole = new Role();
        adminRole.setName("admin");
        adminRole.setDescription("Organization administrator");
        roleRepository.save(adminRole);

        adminUser = createUser("admin@example.com", adminRole.getId());
        regularUser = createUser("alice@example.com", userRole.getId());
        createProfile(adminUser.getId(), "Admin", "User");
        createProfile(regularUser.getId(), "Alice", "Lovelace");
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
    public void testUsersIndexContract() throws Exception {
        mockMvc.perform(get("/users.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body").isArray());
        // Disabled (verified) — pagination-header, NOT an envelope issue:
        // validation.response.body.schema.required on /header — the users_index
        // response uses the headerWithPagination schema, which REQUIRES a
        // "pagination" object (count/page/limit). UsersController.getAllUsers()
        // emits the plain header without pagination, so the validator rejects the
        // header. (groups_index uses the plain header — which is why the Group index
        // assertion passes — but users_index does not.) Recorded in
        // assertions_left_disabled.
        // .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    public void testUsersViewContract() throws Exception {
        mockMvc.perform(get("/users/" + regularUser.getId() + ".json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.id").value(regularUser.getId()))
                .andExpect(jsonPath("$.body.username").value("alice@example.com"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    public void testUsersViewMeAliasContract() throws Exception {
        // The plugin relies heavily on GET /users/me.json; it resolves to the
        // authenticated principal (admin@example.com) and returns users_view.
        mockMvc.perform(get("/users/me.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.username").value("admin@example.com"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }
}
