package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.model.Favorite;
import com.jpassbolt.api.model.Group;
import com.jpassbolt.api.model.GroupUser;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.FavoriteRepository;
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
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for PUT /share/{foreignModel}/{foreignId}.json in the
 * ARO=Group dimension (group-share cluster): group grant with member secret
 * fan-out, group-inherited OWNER share rights, secret ciphertext updates,
 * revocation with access-path merging, and the PHP-parity validation errors
 * (aro inList / uuid / aro_exists / permission_unique / secrets_provided).
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class GroupShareUpdateControllerTest {

    private static final String PGP_DATA = "-----BEGIN PGP MESSAGE-----\nData\n-----END PGP MESSAGE-----";
    private static final String PGP_DATA_UPDATED = "-----BEGIN PGP MESSAGE-----\nUpdated\n-----END PGP MESSAGE-----";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private FavoriteRepository favoriteRepository;

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
    private User outsider;
    private Resource resource;
    private Permission ownerPermission;

    @BeforeEach
    void setUp() {
        // Reverse FK-dependency order
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        favoriteRepository.deleteAll();
        groupUserRepository.deleteAll();
        groupRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();

        owner = createUser("test@example.com", true);
        memberA = createUser("membera@example.com", true);
        memberB = createUser("memberb@example.com", true);
        outsider = createUser("outsider@example.com", true);

        resource = new Resource();
        resource.setName("Group Shared Password");
        resource.setUsername("admin");
        resource.setUri("https://group-share.example.com");
        resource.setCreatedBy(owner.getId());
        resource.setModifiedBy(owner.getId());
        resource.setDeleted(false);
        resourceRepository.save(resource);

        ownerPermission = createUserPermission(resource.getId(), owner.getId(), Permission.OWNER);
        createSecret(resource.getId(), owner.getId(), PGP_DATA);
    }

    // ------------------------------------------------------------------
    // Positive cases
    // ------------------------------------------------------------------

    @Test
    void testShareWithGroup_GrantsAccessAndFanOutSecrets() throws Exception {
        Group group = createGroupWithMembers("Devs", memberA, memberB);

        Map<String, Object> request = Map.of(
                "permissions", List.of(groupAdd(group, Permission.READ)),
                "secrets", List.of(secretFor(memberA), secretFor(memberB)));

        performShare(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                // nullBody contract: body is JSON null, NOT {}
                .andExpect(jsonPath("$.body").value(nullValue()));

        assertThat(permissionRepository
                .findByAcoForeignKeyAndAroForeignKey(resource.getId(), group.getId()))
                .hasValueSatisfying(p -> {
                    assertThat(p.getAro()).isEqualTo(Permission.GROUP_ARO);
                    assertThat(p.getType()).isEqualTo(Permission.READ);
                });
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), memberA.getId())).isPresent();
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), memberB.getId())).isPresent();
    }

    @Test
    void testShareByGroupInheritedOwner_Succeeds() throws Exception {
        // Move the requester's OWNER from a direct User row to group
        // inheritance: a group-inherited OWNER may initiate a share
        // (PHP PermissionsTable::hasAccess semantics).
        Group ownersGroup = createGroupWithMembers("Owners", owner);
        createGroupPermission(resource.getId(), ownersGroup.getId(), Permission.OWNER);
        permissionRepository.deleteById(ownerPermission.getId());

        Group group = createGroupWithMembers("Devs", memberA);
        Map<String, Object> request = Map.of(
                "permissions", List.of(groupAdd(group, Permission.READ)),
                "secrets", List.of(secretFor(memberA)));

        performShare(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(nullValue()));

        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), memberA.getId())).isPresent();
    }

    @Test
    void testUpdateSecretDataForExistingUser() throws Exception {
        // Re-submitting a secret for a user who KEEPS access updates the
        // ciphertext (PHP SecretsUpdateSecretsService::updateSecret is an
        // update, not a skip).
        createUserPermission(resource.getId(), memberA.getId(), Permission.READ);
        createSecret(resource.getId(), memberA.getId(), PGP_DATA);

        Map<String, Object> request = Map.of(
                "permissions", List.of(),
                "secrets", List.of(Map.of(
                        "user_id", memberA.getId(),
                        "data", PGP_DATA_UPDATED)));

        performShare(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(nullValue()));

        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), memberA.getId()))
                .hasValueSatisfying(s -> assertThat(s.getData()).isEqualTo(PGP_DATA_UPDATED));
    }

    @Test
    void testDeleteAllUserOwners_GroupOwnerRemains_Succeeds() throws Exception {
        // The at_least_one_owner rule counts Group OWNER rows: deleting the
        // only User OWNER row succeeds when a group holds OWNER.
        Group group = createGroupWithMembers("Owners", memberA, memberB);
        createGroupPermission(resource.getId(), group.getId(), Permission.OWNER);
        createSecret(resource.getId(), memberA.getId(), PGP_DATA);
        createSecret(resource.getId(), memberB.getId(), PGP_DATA);

        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "id", ownerPermission.getId(),
                "delete", true)));

        performShare(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(nullValue()));

        assertThat(permissionRepository.findById(ownerPermission.getId())).isEmpty();
        // The former owner lost access: secret cascade
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), owner.getId())).isEmpty();
        // Group members keep theirs
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), memberA.getId())).isPresent();
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), memberB.getId())).isPresent();
    }

    // ------------------------------------------------------------------
    // Revocation / access-path merging
    // ------------------------------------------------------------------

    @Test
    void testRevokeGroupPermission_DeletesMemberSecrets() throws Exception {
        Group group = createGroupWithMembers("Devs", memberA, memberB);
        Permission groupPermission = createGroupPermission(resource.getId(), group.getId(), Permission.READ);
        createSecret(resource.getId(), memberA.getId(), PGP_DATA);
        createSecret(resource.getId(), memberB.getId(), PGP_DATA);
        favoriteRepository.save(new Favorite(memberA.getId(), resource.getId(),
                Favorite.FOREIGN_MODEL_RESOURCE));

        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "id", groupPermission.getId(),
                "delete", true)));

        performShare(request).andExpect(status().isOk());

        assertThat(permissionRepository.findById(groupPermission.getId())).isEmpty();
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), memberA.getId())).isEmpty();
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), memberB.getId())).isEmpty();
        assertThat(favoriteRepository
                .findByUserIdAndForeignKey(memberA.getId(), resource.getId())).isEmpty();
        // The owner is untouched
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), owner.getId())).isPresent();
    }

    @Test
    void testRevokeGroup_MemberWithDirectPermissionKeepsSecret() throws Exception {
        Group group = createGroupWithMembers("Devs", memberA, memberB);
        Permission groupPermission = createGroupPermission(resource.getId(), group.getId(), Permission.READ);
        Permission directPermission = createUserPermission(resource.getId(), memberA.getId(), Permission.READ);
        createSecret(resource.getId(), memberA.getId(), PGP_DATA);
        createSecret(resource.getId(), memberB.getId(), PGP_DATA);

        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "id", groupPermission.getId(),
                "delete", true)));

        performShare(request).andExpect(status().isOk());

        // memberA keeps access through the direct permission
        assertThat(permissionRepository.findById(directPermission.getId())).isPresent();
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), memberA.getId())).isPresent();
        // memberB only had the group path
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), memberB.getId())).isEmpty();
    }

    @Test
    void testRevokeGroup_MemberInAnotherGroupKeepsSecret() throws Exception {
        Group group1 = createGroupWithMembers("Devs", memberA, memberB);
        Group group2 = createGroupWithMembers("Ops", memberA);
        Permission group1Permission = createGroupPermission(resource.getId(), group1.getId(), Permission.READ);
        createGroupPermission(resource.getId(), group2.getId(), Permission.READ);
        createSecret(resource.getId(), memberA.getId(), PGP_DATA);
        createSecret(resource.getId(), memberB.getId(), PGP_DATA);

        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "id", group1Permission.getId(),
                "delete", true)));

        performShare(request).andExpect(status().isOk());

        // memberA keeps access through the still-permitted second group
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), memberA.getId())).isPresent();
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), memberB.getId())).isEmpty();
    }

    @Test
    void testRevokeGroupWithZeroMembers() throws Exception {
        // A zero-member group's permission is deletable and must not wipe
        // any secret (the non-empty "after" NOT IN branch).
        Group emptyGroup = createGroupWithMembers("Empty");
        Permission groupPermission = createGroupPermission(resource.getId(), emptyGroup.getId(), Permission.READ);

        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "id", groupPermission.getId(),
                "delete", true)));

        performShare(request).andExpect(status().isOk());

        assertThat(permissionRepository.findById(groupPermission.getId())).isEmpty();
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), owner.getId())).isPresent();
    }

    // ------------------------------------------------------------------
    // Read path end-to-end (depends on the group-aware guards in
    // ResourceController/SecretController — userHasAccessIncludingGroups)
    // ------------------------------------------------------------------

    @Test
    void testGroupMemberCanReadResourceAndSecret() throws Exception {
        Group group = createGroupWithMembers("Devs", memberA);
        Map<String, Object> request = Map.of(
                "permissions", List.of(groupAdd(group, Permission.READ)),
                "secrets", List.of(secretFor(memberA)));
        performShare(request).andExpect(status().isOk());

        // The group member can see the resource…
        mockMvc.perform(get("/resources/" + resource.getId() + ".json")
                .with(user("membera@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.id").value(resource.getId()));

        // …and fetch their fanned-out secret
        mockMvc.perform(get("/secrets/resource/" + resource.getId() + ".json")
                .with(user("membera@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.user_id").value(memberA.getId()))
                .andExpect(jsonPath("$.body.data").value(PGP_DATA));
    }

    // ------------------------------------------------------------------
    // Negative cases
    // ------------------------------------------------------------------

    @Test
    void testShareWithGroup_MissingMemberSecret_Returns400() throws Exception {
        Group group = createGroupWithMembers("Devs", memberA, memberB);

        // memberB's secret is missing from the fan-out
        Map<String, Object> request = Map.of(
                "permissions", List.of(groupAdd(group, Permission.READ)),
                "secrets", List.of(secretFor(memberA)));

        performShare(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("Could not validate resource data."))
                .andExpect(jsonPath("$.body.secrets.secrets_provided")
                        .value("The secrets of all the users having access to the resource are required."));

        // Zero persistence: the transaction never wrote anything
        assertThat(permissionRepository
                .findByAcoForeignKeyAndAroForeignKey(resource.getId(), group.getId())).isEmpty();
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), memberA.getId())).isEmpty();
        assertThat(secretRepository.findByResourceId(resource.getId())).hasSize(1); // owner only
    }

    @Test
    void testSecretForStrangerReturns400() throws Exception {
        // A secret for a user who never gains access (provided ⊄ after)
        Map<String, Object> request = Map.of(
                "permissions", List.of(),
                "secrets", List.of(secretFor(outsider)));

        performShare(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("Could not validate resource data."))
                .andExpect(jsonPath("$.body.secrets.secrets_provided")
                        .value("The secrets of all the users having access to the resource are required."));

        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), outsider.getId())).isEmpty();
    }

    @Test
    void testShareWithNonexistentGroup_Returns400() throws Exception {
        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "aro", "Group",
                "aro_foreign_key", UUID.randomUUID().toString(),
                "type", Permission.READ)));

        performShare(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("Could not validate resource data."))
                .andExpect(jsonPath("$.body.permissions['0'].aro_foreign_key.aro_exists")
                        .value("The access request object does not exist."));
    }

    @Test
    void testShareWithSoftDeletedGroup_Returns400() throws Exception {
        Group group = createGroupWithMembers("Ghost", memberA);
        group.setDeleted(true);
        groupRepository.save(group);

        Map<String, Object> request = Map.of(
                "permissions", List.of(groupAdd(group, Permission.READ)),
                "secrets", List.of(secretFor(memberA)));

        performShare(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.permissions['0'].aro_foreign_key.aro_exists")
                        .value("The access request object does not exist."));
    }

    @Test
    void testShareWithInactiveUserAro_Returns400() throws Exception {
        // aro_exists IsActiveRule branch: an inactive User ARO is rejected
        User inactive = createUser("inactive@example.com", false);

        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "aro", "User",
                "aro_foreign_key", inactive.getId(),
                "type", Permission.READ)));

        performShare(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.permissions['0'].aro_foreign_key.aro_exists")
                        .value("The access request object does not exist."));
    }

    @Test
    void testShareDuplicateGroupPermission_Returns400() throws Exception {
        // Same (aro, aro_foreign_key) and same type on an id-less add →
        // permission_unique (the real MySQL schema has no unique constraint,
        // the service layer must guard it).
        Group group = createGroupWithMembers("Devs", memberA);
        createGroupPermission(resource.getId(), group.getId(), Permission.READ);

        Map<String, Object> request = Map.of("permissions", List.of(groupAdd(group, Permission.READ)));

        performShare(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.permissions['0'].aro_foreign_key.permission_unique")
                        .value("A permission already exists for the given access control object and access request object."));
    }

    @Test
    void testInvalidAroReturns400() throws Exception {
        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "aro", "Banana",
                "aro_foreign_key", memberA.getId(),
                "type", Permission.READ)));

        performShare(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.permissions['0'].aro.inList")
                        .value("The aro must be one of the following: User, Group."));
    }

    @Test
    void testMalformedAroForeignKeyReturns400() throws Exception {
        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "aro", "Group",
                "aro_foreign_key", "not-a-uuid",
                "type", Permission.READ)));

        performShare(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.permissions['0'].aro_foreign_key.uuid")
                        .value("The identifier should be a valid UUID."));
    }

    @Test
    void testRemoveAllOwners_Returns400() throws Exception {
        // A group holding only READ does not satisfy at_least_one_owner
        Group group = createGroupWithMembers("Devs", memberA);
        createGroupPermission(resource.getId(), group.getId(), Permission.READ);

        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "id", ownerPermission.getId(),
                "delete", true)));

        performShare(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.permissions.at_least_one_owner")
                        .value("At least one owner permission must be provided."));

        assertThat(permissionRepository.findById(ownerPermission.getId())).isPresent();
    }

    @Test
    @WithMockUser(username = "membera@example.com", roles = { "USER" })
    void testShareByNonOwner_Returns403() throws Exception {
        // memberA inherits UPDATE (not OWNER) through the group: sharing is
        // forbidden.
        Group group = createGroupWithMembers("Editors", memberA);
        createGroupPermission(resource.getId(), group.getId(), Permission.UPDATE);

        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "aro", "User",
                "aro_foreign_key", outsider.getId(),
                "type", Permission.READ)));

        performShare(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.message")
                        .value("You are not authorized to share this resource."));
    }

    @Test
    @WithAnonymousUser
    void testUnauthenticated() throws Exception {
        // OpenAPI says 401; the project-wide SecurityConfig has no
        // authenticationEntryPoint so the actual status is 403 — asserted
        // as-is (same as ShareUpdateControllerTest).
        performShare(Map.of("permissions", List.of()))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ResultActions performShare(Map<String, Object> request) throws Exception {
        return mockMvc.perform(put("/share/resource/" + resource.getId() + ".json")
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

    private Map<String, Object> secretFor(User user) {
        return Map.of("user_id", user.getId(), "data", PGP_DATA);
    }

    private User createUser(String username, boolean active) {
        User user = new User();
        user.setUsername(username);
        user.setRoleId("user");
        user.setActive(active);
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

    private Secret createSecret(String resourceId, String userId, String data) {
        Secret secret = new Secret();
        secret.setResourceId(resourceId);
        secret.setUserId(userId);
        secret.setData(data);
        return secretRepository.save(secret);
    }
}
