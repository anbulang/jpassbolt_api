package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.model.Group;
import com.jpassbolt.api.model.GroupUser;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.GroupRepository;
import com.jpassbolt.api.repository.GroupUserRepository;
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
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for POST /share/simulate/{foreignModel}/{foreignId}.json
 * in the ARO=Group dimension (group-share cluster): the dry run returns the
 * group-expanded USER set difference — added = after − before (members with a
 * pre-existing direct path are excluded), removed = before − after (members
 * keeping another access path are excluded) — and never persists anything.
 * The client uses "added" to encrypt one secret per member before the real
 * PUT.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class GroupShareSimulateControllerTest {

    private static final String PGP_DATA = "-----BEGIN PGP MESSAGE-----\nData\n-----END PGP MESSAGE-----";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private GroupRepository groupRepository;

    @Autowired
    private GroupUserRepository groupUserRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private User owner;
    private User memberA;
    private User memberB;
    private Resource resource;
    private Permission ownerPermission;

    @BeforeEach
    void setUp() {
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        groupUserRepository.deleteAll();
        groupRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();

        owner = createUser("test@example.com");
        memberA = createUser("membera@example.com");
        memberB = createUser("memberb@example.com");

        resource = new Resource();
        resource.setName("Group Simulated Password");
        resource.setUsername("admin");
        resource.setUri("https://group-simulate.example.com");
        resource.setCreatedBy(owner.getId());
        resource.setModifiedBy(owner.getId());
        resource.setDeleted(false);
        resourceRepository.save(resource);

        ownerPermission = createUserPermission(resource.getId(), owner.getId(), Permission.OWNER);

        Secret ownerSecret = new Secret();
        ownerSecret.setResourceId(resource.getId());
        ownerSecret.setUserId(owner.getId());
        ownerSecret.setData(PGP_DATA);
        secretRepository.save(ownerSecret);
    }

    // ------------------------------------------------------------------
    // Positive cases
    // ------------------------------------------------------------------

    @Test
    void testSimulateAddGroup_ReturnsAllMembersAsAdded() throws Exception {
        Group group = createGroupWithMembers("Devs", memberA, memberB);

        performSimulate(Map.of("permissions", List.of(groupAdd(group, Permission.READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.changes.added.length()").value(2))
                .andExpect(jsonPath("$.body.changes.added[*].User.id",
                        containsInAnyOrder(memberA.getId(), memberB.getId())))
                .andExpect(jsonPath("$.body.changes.removed").isEmpty());
    }

    @Test
    void testSimulateAddGroup_MemberAlreadyHavingDirectAccess_NotInAdded() throws Exception {
        // memberB already holds a direct READ: only memberA newly gains
        // access (added = after − before).
        createUserPermission(resource.getId(), memberB.getId(), Permission.READ);
        Group group = createGroupWithMembers("Devs", memberA, memberB);

        performSimulate(Map.of("permissions", List.of(groupAdd(group, Permission.READ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.changes.added.length()").value(1))
                .andExpect(jsonPath("$.body.changes.added[0].User.id").value(memberA.getId()))
                .andExpect(jsonPath("$.body.changes.removed").isEmpty());
    }

    @Test
    void testSimulateDeleteGroupPermission_ReturnsLosersOnly() throws Exception {
        // memberA keeps a direct READ; only memberB loses every access path.
        Group group = createGroupWithMembers("Devs", memberA, memberB);
        Permission groupPermission = createGroupPermission(resource.getId(), group.getId(), Permission.READ);
        createUserPermission(resource.getId(), memberA.getId(), Permission.READ);

        performSimulate(Map.of("permissions", List.of(Map.of(
                "id", groupPermission.getId(),
                "delete", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.changes.removed.length()").value(1))
                .andExpect(jsonPath("$.body.changes.removed[0].User.id").value(memberB.getId()))
                .andExpect(jsonPath("$.body.changes.added").isEmpty());
    }

    @Test
    void testSimulate_DoesNotPersist() throws Exception {
        Group group = createGroupWithMembers("Devs", memberA, memberB);
        long permissionsBefore = permissionRepository.count();
        long secretsBefore = secretRepository.count();
        long groupUsersBefore = groupUserRepository.count();

        performSimulate(Map.of("permissions", List.of(groupAdd(group, Permission.READ))))
                .andExpect(status().isOk());

        assertThat(permissionRepository.count()).isEqualTo(permissionsBefore);
        assertThat(secretRepository.count()).isEqualTo(secretsBefore);
        assertThat(groupUserRepository.count()).isEqualTo(groupUsersBefore);
    }

    @Test
    void testSimulateIgnoresSecrets() throws Exception {
        // The dry run ignores any provided secrets (PHP parity): they alter
        // neither the result nor the database.
        Group group = createGroupWithMembers("Devs", memberA);
        long secretsBefore = secretRepository.count();

        performSimulate(Map.of(
                "permissions", List.of(groupAdd(group, Permission.READ)),
                "secrets", List.of(Map.of("user_id", memberA.getId(), "data", PGP_DATA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.changes.added[0].User.id").value(memberA.getId()));

        assertThat(secretRepository.count()).isEqualTo(secretsBefore);
    }

    // ------------------------------------------------------------------
    // Negative cases
    // ------------------------------------------------------------------

    @Test
    void testSimulateNonexistentGroupReturns400() throws Exception {
        performSimulate(Map.of("permissions", List.of(Map.of(
                "aro", "Group",
                "aro_foreign_key", UUID.randomUUID().toString(),
                "type", Permission.READ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("Could not validate resource data."))
                .andExpect(jsonPath("$.body.permissions['0'].aro_foreign_key.aro_exists")
                        .value("The access request object does not exist."));
    }

    @Test
    void testSimulateUnknownPermissionIdReturns400() throws Exception {
        performSimulate(Map.of("permissions", List.of(Map.of(
                "id", UUID.randomUUID().toString(),
                "type", Permission.OWNER))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.permissions['0'].id.exists")
                        .value("The permission does not exist."));
    }

    @Test
    void testSimulateInvalidTypeReturns400() throws Exception {
        Group group = createGroupWithMembers("Devs", memberA);

        performSimulate(Map.of("permissions", List.of(Map.of(
                "aro", "Group",
                "aro_foreign_key", group.getId(),
                "type", 5))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("Could not validate resource data."));
    }

    @Test
    @WithMockUser(username = "membera@example.com", roles = { "USER" })
    void testSimulateByNonOwnerReturns403() throws Exception {
        // memberA inherits UPDATE (not OWNER) through a group
        Group group = createGroupWithMembers("Editors", memberA);
        createGroupPermission(resource.getId(), group.getId(), Permission.UPDATE);

        performSimulate(Map.of("permissions", List.of(Map.of(
                "aro", "User",
                "aro_foreign_key", memberB.getId(),
                "type", Permission.READ))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.message")
                        .value("You are not authorized to share this resource."));
    }

    @Test
    void testSimulateSoftDeletedResourceReturns404() throws Exception {
        resource.setDeleted(true);
        resourceRepository.save(resource);

        performSimulate(Map.of("permissions", List.of()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.message").value("The resource does not exist."));
    }

    @Test
    void testSimulateInvalidUuidReturns400() throws Exception {
        mockMvc.perform(post("/share/simulate/resource/not-a-uuid.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("permissions", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message")
                        .value("The resource identifier should be a valid UUID."));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ResultActions performSimulate(Map<String, Object> request) throws Exception {
        return mockMvc.perform(post("/share/simulate/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private Map<String, Object> groupAdd(Group group, int type) {
        return Map.of(
                "aro", "Group",
                "aro_foreign_key", group.getId(),
                "type", type,
                "is_new", true);
    }

    private User createUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setRoleId("user");
        user.setActive(true);
        user.setDeleted(false);
        return userRepository.save(user);
    }

    private Group createGroupWithMembers(String name, User... members) {
        Group group = new Group();
        group.setName(name);
        group.setDeleted(false);
        group.setCreatedBy(owner.getId());
        group.setModifiedBy(owner.getId());
        group = groupRepository.save(group);
        for (User member : members) {
            GroupUser groupUser = new GroupUser();
            groupUser.setGroupId(group.getId());
            groupUser.setUserId(member.getId());
            groupUser.setIsAdmin(false);
            groupUserRepository.save(groupUser);
        }
        return group;
    }

    private Permission createUserPermission(String resourceId, String userId, int type) {
        Permission permission = new Permission();
        permission.setAco(Permission.RESOURCE_ACO);
        permission.setAcoForeignKey(resourceId);
        permission.setAro(Permission.USER_ARO);
        permission.setAroForeignKey(userId);
        permission.setType(type);
        return permissionRepository.save(permission);
    }

    private Permission createGroupPermission(String resourceId, String groupId, int type) {
        Permission permission = new Permission();
        permission.setAco(Permission.RESOURCE_ACO);
        permission.setAcoForeignKey(resourceId);
        permission.setAro(Permission.GROUP_ARO);
        permission.setAroForeignKey(groupId);
        permission.setType(type);
        return permissionRepository.save(permission);
    }
}
