package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for POST /share/simulate/{foreignModel}/{foreignId}.json
 * (ShareController, generalized two-segment path).
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class ShareSimulateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private User ownerUser;
    private User targetUser;
    private User readerUser;
    private Resource resource;
    private Permission ownerPermission;
    private Permission readerPermission;

    @BeforeEach
    void setUp() {
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();

        ownerUser = createUser("test@example.com");
        targetUser = createUser("target@example.com");
        readerUser = createUser("reader@example.com");

        resource = new Resource();
        resource.setName("Simulated Password");
        resource.setUsername("admin");
        resource.setUri("https://simulate.example.com");
        resource.setCreatedBy(ownerUser.getId());
        resource.setModifiedBy(ownerUser.getId());
        resource.setDeleted(false);
        resourceRepository.save(resource);

        ownerPermission = createPermission(resource.getId(), ownerUser.getId(), Permission.OWNER);
        readerPermission = createPermission(resource.getId(), readerUser.getId(), Permission.READ);

        Secret ownerSecret = new Secret();
        ownerSecret.setResourceId(resource.getId());
        ownerSecret.setUserId(ownerUser.getId());
        ownerSecret.setData("-----BEGIN PGP MESSAGE-----\nOwner secret\n-----END PGP MESSAGE-----");
        secretRepository.save(ownerSecret);
    }

    // ------------------------------------------------------------------
    // Positive cases
    // ------------------------------------------------------------------

    @Test
    void testSimulateAddUser() throws Exception {
        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "aro", "User",
                "aro_foreign_key", targetUser.getId(),
                "type", Permission.READ,
                "is_new", true)));

        mockMvc.perform(post("/share/simulate/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.changes.added.length()").value(1))
                .andExpect(jsonPath("$.body.changes.added[0].User.id").value(targetUser.getId()))
                .andExpect(jsonPath("$.body.changes.removed").isEmpty());
    }

    @Test
    void testSimulateDeleteUserById() throws Exception {
        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "id", readerPermission.getId(),
                "delete", true)));

        mockMvc.perform(post("/share/simulate/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.changes.removed.length()").value(1))
                .andExpect(jsonPath("$.body.changes.removed[0].User.id").value(readerUser.getId()))
                .andExpect(jsonPath("$.body.changes.added").isEmpty());
    }

    @Test
    void testSimulateDeleteUserByAroForeignKey() throws Exception {
        // The plugin may also send the id-less deleteUser shape
        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "aro", "User",
                "aro_foreign_key", readerUser.getId(),
                "delete", true)));

        mockMvc.perform(post("/share/simulate/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.changes.removed[0].User.id").value(readerUser.getId()));
    }

    @Test
    void testSimulateDoesNotPersist() throws Exception {
        long permissionsBefore = permissionRepository.count();
        long secretsBefore = secretRepository.count();

        Map<String, Object> request = Map.of("permissions", List.of(
                Map.of("aro", "User", "aro_foreign_key", targetUser.getId(),
                        "type", Permission.OWNER, "is_new", true),
                Map.of("id", readerPermission.getId(), "delete", true)));

        mockMvc.perform(post("/share/simulate/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        assertThat(permissionRepository.count()).isEqualTo(permissionsBefore);
        assertThat(secretRepository.count()).isEqualTo(secretsBefore);
    }

    // ------------------------------------------------------------------
    // Negative cases
    // ------------------------------------------------------------------

    @Test
    void testRemoveAllOwnersReturns400() throws Exception {
        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "id", ownerPermission.getId(),
                "delete", true)));

        mockMvc.perform(post("/share/simulate/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("Could not validate resource data."))
                .andExpect(jsonPath("$.body.permissions.at_least_one_owner")
                        .value("At least one owner permission must be provided."));
    }

    @Test
    void testUnknownPermissionIdReturns400() throws Exception {
        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "id", UUID.randomUUID().toString(),
                "type", Permission.OWNER)));

        mockMvc.perform(post("/share/simulate/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("Could not validate resource data."))
                .andExpect(jsonPath("$.body.permissions['0'].id.exists")
                        .value("The permission does not exist."));
    }

    @Test
    void testInvalidTypeReturns400() throws Exception {
        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "aro", "User",
                "aro_foreign_key", targetUser.getId(),
                "type", 5)));

        mockMvc.perform(post("/share/simulate/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("Could not validate resource data."));
    }

    @Test
    @WithMockUser(username = "reader@example.com", roles = { "USER" })
    void testNonOwnerReturns403() throws Exception {
        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "aro", "User",
                "aro_foreign_key", targetUser.getId(),
                "type", Permission.READ)));

        mockMvc.perform(post("/share/simulate/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.message")
                        .value("You are not authorized to share this resource."));
    }

    @Test
    void testNotFoundResourceReturns404() throws Exception {
        mockMvc.perform(post("/share/simulate/resource/" + UUID.randomUUID() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("permissions", List.of()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.message").value("The resource does not exist."));
    }

    @Test
    void testSoftDeletedResourceReturns404() throws Exception {
        resource.setDeleted(true);
        resourceRepository.save(resource);

        mockMvc.perform(post("/share/simulate/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("permissions", List.of()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.message").value("The resource does not exist."));
    }

    @Test
    void testInvalidUuidReturns400() throws Exception {
        mockMvc.perform(post("/share/simulate/resource/not-a-uuid.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("permissions", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message")
                        .value("The resource identifier should be a valid UUID."));
    }

    @Test
    void testFolderModelReturns404() throws Exception {
        mockMvc.perform(post("/share/simulate/folder/" + UUID.randomUUID() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("permissions", List.of()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.message").value("The folder does not exist."));
    }

    @Test
    void testGarbageModelReturns404() throws Exception {
        mockMvc.perform(post("/share/simulate/banana/" + UUID.randomUUID() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("permissions", List.of()))))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private User createUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setRoleId("user");
        user.setActive(true);
        user.setDeleted(false);
        return userRepository.save(user);
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
}
