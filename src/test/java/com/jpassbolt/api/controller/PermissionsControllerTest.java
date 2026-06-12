package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.model.Group;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.GroupRepository;
import com.jpassbolt.api.repository.GroupUserRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for GET /permissions/resource/{resourceId}.json
 * (PermissionsController) — the standard permissions path that replaced
 * GET /share/resource/{id}/permissions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class PermissionsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private GroupUserRepository groupUserRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private User ownerUser;
    private User readerUser;
    private User otherUser;
    private Group group;
    private Resource resource;
    private Resource otherResource;

    @BeforeEach
    void setUp() {
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        profileRepository.deleteAll();
        groupUserRepository.deleteAll();
        groupRepository.deleteAll();
        userRepository.deleteAll();

        ownerUser = createUser("test@example.com");
        readerUser = createUser("reader@example.com");
        otherUser = createUser("other@example.com");

        Profile ownerProfile = new Profile();
        ownerProfile.setUserId(ownerUser.getId());
        ownerProfile.setFirstName("Test");
        ownerProfile.setLastName("Owner");
        profileRepository.save(ownerProfile);

        group = new Group();
        group.setName("Devs");
        group.setDeleted(false);
        group.setCreatedBy(ownerUser.getId());
        group.setModifiedBy(ownerUser.getId());
        groupRepository.save(group);

        // Main resource: owner OWNER + reader READ + group READ
        resource = createResource("Main Password", ownerUser);
        createPermission(resource.getId(), Permission.USER_ARO, ownerUser.getId(), Permission.OWNER);
        createPermission(resource.getId(), Permission.USER_ARO, readerUser.getId(), Permission.READ);
        createPermission(resource.getId(), Permission.GROUP_ARO, group.getId(), Permission.READ);

        // A resource the current user has NO access to
        otherResource = createResource("Foreign Password", otherUser);
        createPermission(otherResource.getId(), Permission.USER_ARO, otherUser.getId(), Permission.OWNER);
    }

    // ------------------------------------------------------------------
    // Positive cases
    // ------------------------------------------------------------------

    @Test
    void testViewPermissionsAsOwner() throws Exception {
        mockMvc.perform(get("/permissions/resource/" + resource.getId() + ".json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body").isArray())
                .andExpect(jsonPath("$.body.length()").value(3))
                .andExpect(jsonPath("$.body[0].aco").value("Resource"))
                .andExpect(jsonPath("$.body[0].aco_foreign_key").value(resource.getId()));
    }

    @Test
    @WithMockUser(username = "reader@example.com", roles = { "USER" })
    void testViewPermissionsAsReader() throws Exception {
        // READ access is enough to see ALL rows, including other users' and
        // the Group row.
        mockMvc.perform(get("/permissions/resource/" + resource.getId() + ".json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.length()").value(3));
    }

    @Test
    void testContainUser() throws Exception {
        MvcResult result = mockMvc.perform(get("/permissions/resource/" + resource.getId() + ".json")
                .param("contain[user]", "1"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode ownerRow = findRowByAro(result, ownerUser.getId());
        assertThat(ownerRow).isNotNull();
        assertThat(ownerRow.get("user").get("username").asText()).isEqualTo("test@example.com");
        assertThat(ownerRow.get("user").has("role_id")).isTrue();
        // contain[user] alone must NOT hydrate the profile
        assertThat(ownerRow.get("user").has("profile")).isFalse();
    }

    @Test
    void testContainUserProfile() throws Exception {
        MvcResult result = mockMvc.perform(get("/permissions/resource/" + resource.getId() + ".json")
                .param("contain[user.profile]", "1"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode ownerRow = findRowByAro(result, ownerUser.getId());
        assertThat(ownerRow).isNotNull();
        JsonNode profile = ownerRow.get("user").get("profile");
        assertThat(profile.get("first_name").asText()).isEqualTo("Test");
        assertThat(profile.get("avatar").get("url").get("medium").asText())
                .endsWith("/img/avatar/user_medium.png");
        assertThat(profile.get("avatar").get("url").get("small").asText())
                .endsWith("/img/avatar/user.png");
    }

    @Test
    void testGroupAroRowHasNoUser() throws Exception {
        MvcResult result = mockMvc.perform(get("/permissions/resource/" + resource.getId() + ".json")
                .param("contain[user]", "1"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode groupRow = findRowByAro(result, group.getId());
        assertThat(groupRow).isNotNull();
        assertThat(groupRow.get("aro").asText()).isEqualTo("Group");
        assertThat(groupRow.has("user")).isFalse();
    }

    @Test
    void testContainGroup() throws Exception {
        MvcResult result = mockMvc.perform(get("/permissions/resource/" + resource.getId() + ".json")
                .param("contain[group]", "1"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode groupRow = findRowByAro(result, group.getId());
        assertThat(groupRow).isNotNull();
        assertThat(groupRow.get("group").get("name").asText()).isEqualTo("Devs");
        // group contain does not affect User rows
        JsonNode ownerRow = findRowByAro(result, ownerUser.getId());
        assertThat(ownerRow.has("group")).isFalse();
        assertThat(ownerRow.has("user")).isFalse();
    }

    // ------------------------------------------------------------------
    // Negative cases
    // ------------------------------------------------------------------

    @Test
    void testNoAccessReturns404() throws Exception {
        // Authenticated but without any permission: 404 (NOT 403) so the
        // existence of the resource is not leaked — key difference from the
        // ResourceController guard pattern.
        mockMvc.perform(get("/permissions/resource/" + otherResource.getId() + ".json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.message").value("The resource does not exist."));
    }

    @Test
    void testInvalidUuidReturns400() throws Exception {
        mockMvc.perform(get("/permissions/resource/not-a-uuid.json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("The identifier should be a valid UUID."));
    }

    @Test
    void testNonexistentResourceReturns404() throws Exception {
        mockMvc.perform(get("/permissions/resource/" + UUID.randomUUID() + ".json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.message").value("The resource does not exist."));
    }

    @Test
    void testSoftDeletedResourceReturns404() throws Exception {
        Resource deletedResource = createResource("Deleted Password", ownerUser);
        createPermission(deletedResource.getId(), Permission.USER_ARO, ownerUser.getId(), Permission.OWNER);
        deletedResource.setDeleted(true);
        resourceRepository.save(deletedResource);

        mockMvc.perform(get("/permissions/resource/" + deletedResource.getId() + ".json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.message").value("The resource does not exist."));
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

    private Resource createResource(String name, User creator) {
        Resource res = new Resource();
        res.setName(name);
        res.setUsername("admin");
        res.setUri("https://example.com");
        res.setCreatedBy(creator.getId());
        res.setModifiedBy(creator.getId());
        res.setDeleted(false);
        return resourceRepository.save(res);
    }

    private Permission createPermission(String resourceId, String aro, String aroForeignKey, int type) {
        Permission permission = new Permission();
        permission.setAco(Permission.RESOURCE_ACO);
        permission.setAcoForeignKey(resourceId);
        permission.setAro(aro);
        permission.setAroForeignKey(aroForeignKey);
        permission.setType(type);
        return permissionRepository.save(permission);
    }

    private JsonNode findRowByAro(MvcResult result, String aroForeignKey) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString()).get("body");
        for (JsonNode row : body) {
            if (aroForeignKey.equals(row.get("aro_foreign_key").asText())) {
                return row;
            }
        }
        return null;
    }
}
