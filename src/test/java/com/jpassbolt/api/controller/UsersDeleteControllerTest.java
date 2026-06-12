package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.model.GpgKey;
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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for DELETE /users/{id}.json (soft delete with cascade,
 * ownership transfer and sole-owner protection).
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin@example.com", roles = { "USER" })
class UsersDeleteControllerTest {

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

    private GpgKey createGpgKey(String userId) {
        GpgKey key = new GpgKey();
        key.setUserId(userId);
        key.setArmoredKey("-----BEGIN PGP PUBLIC KEY BLOCK-----\ntest\n-----END PGP PUBLIC KEY BLOCK-----");
        key.setUid("Victim User <victim@example.com>");
        key.setKeyId("AAAABBBBCCCCDDDD");
        key.setFingerprint("1111222233334444555566667777888899990000");
        key.setType("RSA");
        key.setBits(2048);
        key.setDeleted(false);
        return gpgKeyRepository.save(key);
    }

    @Test
    void testDeleteUserWithoutContent_Success() throws Exception {
        mockMvc.perform(delete("/users/" + victim.getId() + ".json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.message").value(
                        "The user has been deleted successfully."))
                .andExpect(jsonPath("$.body").value(nullValue()));

        assertThat(userRepository.findById(victim.getId()).orElseThrow().getDeleted()).isTrue();
    }

    @Test
    void testDeleteUserWithPrivateResource_Cascades() throws Exception {
        // Resource only the victim can access: soft-deleted with the user;
        // permissions and secrets hard-deleted, gpg key soft-deleted.
        Resource resource = createResource("victim-private", victim.getId());
        createPermission(resource.getId(), victim.getId(), Permission.OWNER);
        createSecret(resource.getId(), victim.getId());
        GpgKey key = createGpgKey(victim.getId());

        mockMvc.perform(delete("/users/" + victim.getId() + ".json"))
                .andExpect(status().isOk());

        assertThat(resourceRepository.findById(resource.getId()).orElseThrow().getDeleted()).isTrue();
        assertThat(permissionRepository.findByAroAndAroForeignKey(
                Permission.USER_ARO, victim.getId())).isEmpty();
        assertThat(secretRepository.findByResourceId(resource.getId())).isEmpty();
        assertThat(gpgKeyRepository.findById(key.getId()).orElseThrow().getDeleted()).isTrue();
        assertThat(userRepository.findById(victim.getId()).orElseThrow().getDeleted()).isTrue();
    }

    @Test
    void testDeleteWithOwnershipTransfer_Success() throws Exception {
        // victim is sole OWNER of a shared resource; plain has READ.
        Resource shared = createResource("shared", victim.getId());
        createPermission(shared.getId(), victim.getId(), Permission.OWNER);
        Permission plainPermission = createPermission(shared.getId(), plainUser.getId(), Permission.READ);

        String body = objectMapper.writeValueAsString(Map.of(
                "transfer", Map.of("owners", List.of(Map.of(
                        "id", plainPermission.getId(),
                        "aco_foreign_key", shared.getId())))));

        mockMvc.perform(delete("/users/" + victim.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk());

        assertThat(permissionRepository.findById(plainPermission.getId()).orElseThrow().getType())
                .isEqualTo(Permission.OWNER);
        assertThat(userRepository.findById(victim.getId()).orElseThrow().getDeleted()).isTrue();
        // The shared resource survives (it is not private to the victim).
        assertThat(resourceRepository.findById(shared.getId()).orElseThrow().getDeleted()).isFalse();
    }

    @Test
    @WithMockUser(username = "plain@example.com", roles = { "USER" })
    void testNonAdmin_Forbidden() throws Exception {
        mockMvc.perform(delete("/users/" + victim.getId() + ".json"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.message").value(
                        "You are not authorized to access that location."));
    }

    @Test
    void testInvalidUuid_BadRequest() throws Exception {
        mockMvc.perform(delete("/users/not-a-uuid.json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The user identifier should be a valid UUID."));
    }

    @Test
    void testDeleteSelf_BadRequest() throws Exception {
        mockMvc.perform(delete("/users/" + adminUser.getId() + ".json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "You are not allowed to delete yourself."));
    }

    @Test
    void testTargetDoesNotExist_NotFound() throws Exception {
        mockMvc.perform(delete("/users/00000000-0000-0000-0000-000000000000.json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.message").value(
                        "The user does not exist or has been already deleted."));
    }

    @Test
    void testSoftDeletedTarget_NotFound() throws Exception {
        victim.setDeleted(true);
        userRepository.save(victim);

        mockMvc.perform(delete("/users/" + victim.getId() + ".json"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testSoleOwnerConflict_BadRequestAndNoChanges() throws Exception {
        Resource shared = createResource("shared", victim.getId());
        createPermission(shared.getId(), victim.getId(), Permission.OWNER);
        createPermission(shared.getId(), plainUser.getId(), Permission.READ);

        mockMvc.perform(delete("/users/" + victim.getId() + ".json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The user cannot be deleted. The user should not be sole owner of shared content, "
                                + "transfer the ownership to other users."))
                .andExpect(jsonPath("$.body.errors.resources.sole_owner[0].id")
                        .value(shared.getId()))
                .andExpect(jsonPath("$.body.errors.resources.sole_owner[0].permissions").isArray());

        // Zero data change (transaction rolled back / nothing written).
        assertThat(userRepository.findById(victim.getId()).orElseThrow().getDeleted()).isFalse();
        assertThat(permissionRepository.findByResourceId(shared.getId())).hasSize(2);
    }

    @Test
    void testIncompleteTransfer_BadRequest() throws Exception {
        // Two blocking resources but the transfer only covers one of them.
        Resource sharedA = createResource("shared-a", victim.getId());
        createPermission(sharedA.getId(), victim.getId(), Permission.OWNER);
        Permission plainPermA = createPermission(sharedA.getId(), plainUser.getId(), Permission.READ);

        Resource sharedB = createResource("shared-b", victim.getId());
        createPermission(sharedB.getId(), victim.getId(), Permission.OWNER);
        createPermission(sharedB.getId(), plainUser.getId(), Permission.READ);

        String body = objectMapper.writeValueAsString(Map.of(
                "transfer", Map.of("owners", List.of(Map.of(
                        "id", plainPermA.getId(),
                        "aco_foreign_key", sharedA.getId())))));

        mockMvc.perform(delete("/users/" + victim.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("The transfer is not authorized"));

        assertThat(userRepository.findById(victim.getId()).orElseThrow().getDeleted()).isFalse();
        assertThat(permissionRepository.findById(plainPermA.getId()).orElseThrow().getType())
                .isEqualTo(Permission.READ);
    }

    @Test
    void testTransferWithInvalidPermissionId_BadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "transfer", Map.of("owners", List.of(Map.of(
                        "id", "not-a-uuid",
                        "aco_foreign_key", "11111111-1111-1111-1111-111111111111")))));

        mockMvc.perform(delete("/users/" + victim.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "The permissions identifiers must be valid UUID."));
    }
}
