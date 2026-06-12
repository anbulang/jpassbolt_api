package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.Secret;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for DELETE /users/{id}/dry-run.json — same preconditions
 * as the real delete, sole-owner check only, strictly zero writes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin@example.com", roles = { "USER" })
class UsersDeleteDryRunControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
    private User victim;

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
        victim = createUser("victim@example.com", userRole.getId());
        createProfile(victim.getId(), "Victim", "User");
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

    private Resource createResource(String name, String createdBy) {
        Resource resource = new Resource();
        resource.setName(name);
        resource.setCreatedBy(createdBy);
        resource.setModifiedBy(createdBy);
        resource.setDeleted(false);
        return resourceRepository.save(resource);
    }

    private Permission createPermission(String resourceId, String userId, int type) {
        Permission permission = new Permission();
        permission.setAco(Permission.RESOURCE_ACO);
        permission.setAcoForeignKey(resourceId);
        permission.setAro(Permission.USER_ARO);
        permission.setAroForeignKey(userId);
        permission.setType(type);
        return permissionRepository.save(permission);
    }

    private Secret createSecret(String resourceId, String userId) {
        Secret secret = new Secret();
        secret.setResourceId(resourceId);
        secret.setUserId(userId);
        secret.setData("-----BEGIN PGP MESSAGE-----\ntest\n-----END PGP MESSAGE-----");
        return secretRepository.save(secret);
    }

    @Test
    void testDryRunDeletableUser_SuccessAndZeroWrites() throws Exception {
        mockMvc.perform(delete("/users/" + victim.getId() + "/dry-run.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.message").value("The user can be deleted."))
                .andExpect(jsonPath("$.body").value(nullValue()));

        // dry-run writes nothing
        assertThat(userRepository.findById(victim.getId()).orElseThrow().getDeleted()).isFalse();
    }

    @Test
    void testDryRunUserWithPrivateResource_DoesNotBlockAndZeroWrites() throws Exception {
        // A resource only the victim can access never blocks deletion.
        Resource privateResource = createResource("victim-private", victim.getId());
        createPermission(privateResource.getId(), victim.getId(), Permission.OWNER);
        createSecret(privateResource.getId(), victim.getId());

        long permissionCount = permissionRepository.count();
        long secretCount = secretRepository.count();

        mockMvc.perform(delete("/users/" + victim.getId() + "/dry-run.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.message").value("The user can be deleted."));

        assertThat(userRepository.findById(victim.getId()).orElseThrow().getDeleted()).isFalse();
        assertThat(resourceRepository.findById(privateResource.getId()).orElseThrow().getDeleted())
                .isFalse();
        assertThat(permissionRepository.count()).isEqualTo(permissionCount);
        assertThat(secretRepository.count()).isEqualTo(secretCount);
    }

    @Test
    void testDryRunSoleOwnerConflict_BadRequestWithErrorList() throws Exception {
        Resource shared = createResource("shared", victim.getId());
        createPermission(shared.getId(), victim.getId(), Permission.OWNER);
        createPermission(shared.getId(), plainUser.getId(), Permission.READ);

        long permissionCount = permissionRepository.count();

        mockMvc.perform(delete("/users/" + victim.getId() + "/dry-run.json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.message").value(
                        "The user cannot be deleted. The user should not be sole owner of shared content, "
                                + "transfer the ownership to other users."))
                .andExpect(jsonPath("$.body.errors.resources.sole_owner[0].id").value(shared.getId()))
                .andExpect(jsonPath("$.body.errors.resources.sole_owner[0].permissions[0].user").exists());

        assertThat(userRepository.findById(victim.getId()).orElseThrow().getDeleted()).isFalse();
        assertThat(permissionRepository.count()).isEqualTo(permissionCount);
    }

    @Test
    @WithMockUser(username = "plain@example.com", roles = { "USER" })
    void testDryRunNonAdmin_Forbidden() throws Exception {
        mockMvc.perform(delete("/users/" + victim.getId() + "/dry-run.json"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.message").value(
                        "You are not authorized to access that location."));
    }

    @Test
    void testDryRunSelf_BadRequest() throws Exception {
        mockMvc.perform(delete("/users/" + adminUser.getId() + "/dry-run.json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "You are not allowed to delete yourself."));
    }

    @Test
    void testDryRunInvalidUuid_BadRequest() throws Exception {
        mockMvc.perform(delete("/users/not-a-uuid/dry-run.json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The user identifier should be a valid UUID."));
    }

    @Test
    void testDryRunTargetDoesNotExist_NotFound() throws Exception {
        mockMvc.perform(delete("/users/00000000-0000-0000-0000-000000000000/dry-run.json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.message").value(
                        "The user does not exist or has been already deleted."));
    }
}
