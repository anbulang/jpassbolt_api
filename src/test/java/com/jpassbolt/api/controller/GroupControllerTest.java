package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.GroupDto;
import com.jpassbolt.api.model.Group;
import com.jpassbolt.api.model.GroupUser;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.GroupRepository;
import com.jpassbolt.api.repository.GroupUserRepository;
import com.jpassbolt.api.repository.PermissionRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for GroupController: groups CRUD, groups_users
 * memberships, update/delete dry-run, permission enforcement.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class GroupControllerTest {

        private static final String PGP_DATA = "-----BEGIN PGP MESSAGE-----\nEncrypted\n-----END PGP MESSAGE-----";

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private GroupRepository groupRepository;

        @Autowired
        private GroupUserRepository groupUserRepository;

        @Autowired
        private PermissionRepository permissionRepository;

        @Autowired
        private SecretRepository secretRepository;

        @Autowired
        private ResourceRepository resourceRepository;

        @Autowired
        private UserRepository userRepository;

        @Autowired
        private RoleRepository roleRepository;

        @Autowired
        private ObjectMapper objectMapper;

        private Role adminRole;
        private Role userRole;
        private User testUser;
        private User bob;
        private User carol;

        @BeforeEach
        void setUp() {
                // Delete in reverse dependency order.
                groupUserRepository.deleteAll();
                permissionRepository.deleteAll();
                secretRepository.deleteAll();
                resourceRepository.deleteAll();
                groupRepository.deleteAll();
                userRepository.deleteAll();
                roleRepository.deleteAll();

                adminRole = new Role();
                adminRole.setName(Role.ADMIN);
                roleRepository.save(adminRole);

                userRole = new Role();
                userRole.setName(Role.USER);
                roleRepository.save(userRole);

                testUser = createUser("test@example.com");
                bob = createUser("bob@example.com");
                carol = createUser("carol@example.com");
        }

        // ------------------------------------------------------------------
        // Helpers
        // ------------------------------------------------------------------

        private User createUser(String username) {
                User user = new User();
                user.setUsername(username);
                user.setRoleId(userRole.getId());
                user.setActive(true);
                user.setDeleted(false);
                return userRepository.save(user);
        }

        private void makeTestUserAdmin() {
                testUser.setRoleId(adminRole.getId());
                userRepository.save(testUser);
        }

        private Group createGroup(String name, User manager) {
                Group group = new Group();
                group.setName(name);
                group.setDeleted(false);
                group.setCreatedBy(testUser.getId());
                group.setModifiedBy(testUser.getId());
                groupRepository.save(group);
                addMember(group, manager, true);
                return group;
        }

        private GroupUser addMember(Group group, User user, boolean isAdmin) {
                GroupUser groupUser = new GroupUser();
                groupUser.setGroupId(group.getId());
                groupUser.setUserId(user.getId());
                groupUser.setIsAdmin(isAdmin);
                return groupUserRepository.save(groupUser);
        }

        private Resource createResource(String name) {
                Resource resource = new Resource();
                resource.setName(name);
                resource.setCreatedBy(testUser.getId());
                resource.setModifiedBy(testUser.getId());
                resource.setDeleted(false);
                return resourceRepository.save(resource);
        }

        private Permission createGroupPermission(Resource resource, Group group, int type) {
                Permission perm = new Permission();
                perm.setAco(Permission.RESOURCE_ACO);
                perm.setAcoForeignKey(resource.getId());
                perm.setAro(Permission.GROUP_ARO);
                perm.setAroForeignKey(group.getId());
                perm.setType(type);
                return permissionRepository.save(perm);
        }

        private Permission createUserPermission(Resource resource, User user, int type) {
                Permission perm = new Permission();
                perm.setAco(Permission.RESOURCE_ACO);
                perm.setAcoForeignKey(resource.getId());
                perm.setAro(Permission.USER_ARO);
                perm.setAroForeignKey(user.getId());
                perm.setType(type);
                return permissionRepository.save(perm);
        }

        private Secret createSecret(Resource resource, User user) {
                Secret secret = new Secret();
                secret.setResourceId(resource.getId());
                secret.setUserId(user.getId());
                secret.setData(PGP_DATA);
                return secretRepository.save(secret);
        }

        // ------------------------------------------------------------------
        // GET /groups.json
        // ------------------------------------------------------------------

        @Test
        void testGetGroups_EmptyList() throws Exception {
                mockMvc.perform(get("/groups.json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body").isArray())
                                .andExpect(jsonPath("$.body").isEmpty());
        }

        @Test
        void testGetGroups_ExcludesDeletedGroups() throws Exception {
                createGroup("Visible", testUser);
                Group deleted = createGroup("Removed", testUser);
                deleted.setDeleted(true);
                groupRepository.save(deleted);

                mockMvc.perform(get("/groups.json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.length()").value(1))
                                .andExpect(jsonPath("$.body[0].name").value("Visible"))
                                // no contain requested: groups_users must be absent
                                .andExpect(jsonPath("$.body[0].groups_users").doesNotExist());
        }

        @Test
        void testGetGroups_FilterHasUsers() throws Exception {
                Group withBob = createGroup("With Bob", testUser);
                addMember(withBob, bob, false);
                createGroup("Without Bob", testUser);

                mockMvc.perform(get("/groups.json").param("filter[has-users]", bob.getId()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.length()").value(1))
                                .andExpect(jsonPath("$.body[0].name").value("With Bob"));
        }

        @Test
        void testGetGroups_FilterHasManagers() throws Exception {
                Group managedByBob = createGroup("Bob Managed", bob);
                addMember(managedByBob, testUser, false);
                createGroup("Test Managed", testUser);

                mockMvc.perform(get("/groups.json").param("filter[has-managers]", bob.getId()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.length()").value(1))
                                .andExpect(jsonPath("$.body[0].name").value("Bob Managed"));
        }

        @Test
        void testGetGroups_ContainGroupsUsersWithUser() throws Exception {
                createGroup("Devs", testUser);

                mockMvc.perform(get("/groups.json")
                                .param("contain[groups_users.user.profile]", "1")
                                .param("contain[my_group_user]", "1"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body[0].groups_users.length()").value(1))
                                .andExpect(jsonPath("$.body[0].groups_users[0].user_id").value(testUser.getId()))
                                .andExpect(jsonPath("$.body[0].groups_users[0].is_admin").value(true))
                                .andExpect(jsonPath("$.body[0].groups_users[0].user.username")
                                                .value("test@example.com"))
                                .andExpect(jsonPath("$.body[0].my_group_user.user_id").value(testUser.getId()));
        }

        // ------------------------------------------------------------------
        // POST /groups.json
        // ------------------------------------------------------------------

        @Test
        void testCreateGroup_AsAdmin_Success() throws Exception {
                makeTestUserAdmin();
                String json = String.format(
                                "{\"name\":\"Accounting\",\"groups_users\":[{\"user_id\":\"%s\",\"is_admin\":true}]}",
                                bob.getId());

                mockMvc.perform(post("/groups.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.name").value("Accounting"))
                                .andExpect(jsonPath("$.body.deleted").value(false))
                                .andExpect(jsonPath("$.body.groups_users.length()").value(1))
                                .andExpect(jsonPath("$.body.groups_users[0].user_id").value(bob.getId()))
                                .andExpect(jsonPath("$.body.groups_users[0].is_admin").value(true));

                List<Group> groups = groupRepository.findByDeletedFalse();
                assertThat(groups).hasSize(1);
                assertThat(groupUserRepository
                                .existsByGroupIdAndUserIdAndIsAdminTrue(groups.get(0).getId(), bob.getId()))
                                .isTrue();
        }

        @Test
        void testCreateGroup_AsNonAdmin_Forbidden() throws Exception {
                String json = String.format(
                                "{\"name\":\"Accounting\",\"groups_users\":[{\"user_id\":\"%s\",\"is_admin\":true}]}",
                                bob.getId());

                mockMvc.perform(post("/groups.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.header.status").value("error"));

                assertThat(groupRepository.findByDeletedFalse()).isEmpty();
        }

        @Test
        void testCreateGroup_WithoutManager_BadRequest() throws Exception {
                makeTestUserAdmin();
                String json = String.format(
                                "{\"name\":\"No Manager\",\"groups_users\":[{\"user_id\":\"%s\",\"is_admin\":false}]}",
                                bob.getId());

                mockMvc.perform(post("/groups.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testCreateGroup_WithUnknownUser_BadRequest() throws Exception {
                makeTestUserAdmin();
                String json = String.format(
                                "{\"name\":\"Ghost\",\"groups_users\":[{\"user_id\":\"%s\",\"is_admin\":true}]}",
                                UUID.randomUUID());

                mockMvc.perform(post("/groups.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testCreateGroup_BlankName_BadRequest() throws Exception {
                makeTestUserAdmin();
                String json = String.format(
                                "{\"name\":\"\",\"groups_users\":[{\"user_id\":\"%s\",\"is_admin\":true}]}",
                                bob.getId());

                mockMvc.perform(post("/groups.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testCreateGroup_DuplicateName_BadRequest() throws Exception {
                makeTestUserAdmin();
                createGroup("Devs", testUser);
                String json = String.format(
                                "{\"name\":\"Devs\",\"groups_users\":[{\"user_id\":\"%s\",\"is_admin\":true}]}",
                                bob.getId());

                mockMvc.perform(post("/groups.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        // ------------------------------------------------------------------
        // GET /groups/{groupId}.json
        // ------------------------------------------------------------------

        @Test
        void testGetGroup_Success() throws Exception {
                Group group = createGroup("Devs", testUser);

                mockMvc.perform(get("/groups/" + group.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.id").value(group.getId()))
                                .andExpect(jsonPath("$.body.name").value("Devs"))
                                .andExpect(jsonPath("$.body.groups_users.length()").value(1))
                                .andExpect(jsonPath("$.body.groups_users[0].user.username")
                                                .value("test@example.com"));
        }

        @Test
        void testGetGroup_NotFound() throws Exception {
                mockMvc.perform(get("/groups/" + UUID.randomUUID() + ".json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testGetGroup_Deleted_NotFound() throws Exception {
                Group group = createGroup("Gone", testUser);
                group.setDeleted(true);
                groupRepository.save(group);

                mockMvc.perform(get("/groups/" + group.getId() + ".json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        // ------------------------------------------------------------------
        // PUT /groups/{groupId}.json
        // ------------------------------------------------------------------

        @Test
        void testUpdateGroup_RenameAsAdmin() throws Exception {
                makeTestUserAdmin();
                // testUser is NOT a member: rename works because of the admin role.
                Group group = createGroup("Old Name", bob);

                GroupDto.UpdateRequest request = GroupDto.UpdateRequest.builder()
                                .name("New Name")
                                .build();

                mockMvc.perform(put("/groups/" + group.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.name").value("New Name"));

                assertThat(groupRepository.findById(group.getId()).orElseThrow().getName())
                                .isEqualTo("New Name");
        }

        @Test
        void testUpdateGroup_RenameAsManagerIsIgnored() throws Exception {
                // testUser is a manager but not an admin: the name change is
                // silently ignored (PHP behavior).
                Group group = createGroup("Stable Name", testUser);

                GroupDto.UpdateRequest request = GroupDto.UpdateRequest.builder()
                                .name("Hijacked")
                                .build();

                mockMvc.perform(put("/groups/" + group.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.name").value("Stable Name"));

                assertThat(groupRepository.findById(group.getId()).orElseThrow().getName())
                                .isEqualTo("Stable Name");
        }

        @Test
        void testUpdateGroup_AsOutsider_Forbidden() throws Exception {
                // testUser is neither member nor admin.
                Group group = createGroup("Bob Group", bob);

                GroupDto.UpdateRequest request = GroupDto.UpdateRequest.builder()
                                .name("Hacked")
                                .build();

                mockMvc.perform(put("/groups/" + group.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testUpdateGroup_NotFound() throws Exception {
                GroupDto.UpdateRequest request = GroupDto.UpdateRequest.builder()
                                .name("Whatever")
                                .build();

                mockMvc.perform(put("/groups/" + UUID.randomUUID() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testUpdateGroup_AddMemberWithSecrets() throws Exception {
                Group group = createGroup("Devs", testUser);
                Resource resource = createResource("Shared Password");
                createGroupPermission(resource, group, Permission.OWNER);
                createSecret(resource, testUser);

                GroupDto.UpdateRequest request = GroupDto.UpdateRequest.builder()
                                .groupsUsers(List.of(GroupDto.GroupUserChange.builder()
                                                .userId(bob.getId())
                                                .isAdmin(false)
                                                .build()))
                                .secrets(List.of(GroupDto.SecretData.builder()
                                                .resourceId(resource.getId())
                                                .userId(bob.getId())
                                                .data(PGP_DATA)
                                                .build()))
                                .build();

                mockMvc.perform(put("/groups/" + group.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.groups_users.length()").value(2));

                assertThat(groupUserRepository.findByGroupIdAndUserId(group.getId(), bob.getId()))
                                .isPresent();
                assertThat(secretRepository.findByResourceIdAndUserId(resource.getId(), bob.getId()))
                                .isPresent();
        }

        @Test
        void testUpdateGroup_AddMemberWithoutRequiredSecret_BadRequest() throws Exception {
                Group group = createGroup("Devs", testUser);
                Resource resource = createResource("Shared Password");
                createGroupPermission(resource, group, Permission.OWNER);

                GroupDto.UpdateRequest request = GroupDto.UpdateRequest.builder()
                                .groupsUsers(List.of(GroupDto.GroupUserChange.builder()
                                                .userId(bob.getId())
                                                .isAdmin(false)
                                                .build()))
                                .build();

                mockMvc.perform(put("/groups/" + group.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));

                assertThat(groupUserRepository.findByGroupIdAndUserId(group.getId(), bob.getId()))
                                .isEmpty();
        }

        @Test
        void testUpdateGroup_RemoveMember_CleansUpSecrets() throws Exception {
                Group group = createGroup("Devs", testUser);
                GroupUser bobMembership = addMember(group, bob, false);
                Resource resource = createResource("Group Password");
                createGroupPermission(resource, group, Permission.OWNER);
                createSecret(resource, bob);

                GroupDto.UpdateRequest request = GroupDto.UpdateRequest.builder()
                                .groupsUsers(List.of(GroupDto.GroupUserChange.builder()
                                                .id(bobMembership.getId())
                                                .delete(true)
                                                .build()))
                                .build();

                mockMvc.perform(put("/groups/" + group.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.groups_users.length()").value(1));

                assertThat(groupUserRepository.findByGroupIdAndUserId(group.getId(), bob.getId()))
                                .isEmpty();
                // Bob's only access to the resource was through the group:
                // the secret must be gone.
                assertThat(secretRepository.findByResourceIdAndUserId(resource.getId(), bob.getId()))
                                .isEmpty();
        }

        @Test
        void testUpdateGroup_RemoveLastManager_BadRequest() throws Exception {
                Group group = createGroup("Devs", testUser);
                GroupUser myMembership = groupUserRepository
                                .findByGroupIdAndUserId(group.getId(), testUser.getId()).orElseThrow();

                GroupDto.UpdateRequest request = GroupDto.UpdateRequest.builder()
                                .groupsUsers(List.of(GroupDto.GroupUserChange.builder()
                                                .id(myMembership.getId())
                                                .delete(true)
                                                .build()))
                                .build();

                mockMvc.perform(put("/groups/" + group.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));

                assertThat(groupUserRepository.findByGroupIdAndUserId(group.getId(), testUser.getId()))
                                .isPresent();
        }

        // ------------------------------------------------------------------
        // PUT /groups/{groupId}/dry-run.json
        // ------------------------------------------------------------------

        @Test
        void testUpdateDryRun_ReturnsSecretsNeededAndOperatorSecrets() throws Exception {
                Group group = createGroup("Devs", testUser);
                Resource resource = createResource("Group Password");
                createGroupPermission(resource, group, Permission.OWNER);
                createSecret(resource, testUser);

                GroupDto.UpdateRequest request = GroupDto.UpdateRequest.builder()
                                .groupsUsers(List.of(GroupDto.GroupUserChange.builder()
                                                .userId(bob.getId())
                                                .isAdmin(false)
                                                .build()))
                                .build();

                mockMvc.perform(put("/groups/" + group.getId() + "/dry-run.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body['dry-run'].SecretsNeeded.length()").value(1))
                                .andExpect(jsonPath("$.body['dry-run'].SecretsNeeded[0].Secret.resource_id")
                                                .value(resource.getId()))
                                .andExpect(jsonPath("$.body['dry-run'].SecretsNeeded[0].Secret.user_id")
                                                .value(bob.getId()))
                                .andExpect(jsonPath("$.body['dry-run'].Secrets.length()").value(1))
                                .andExpect(jsonPath("$.body['dry-run'].Secrets[0].Secret.resource_id")
                                                .value(resource.getId()))
                                .andExpect(jsonPath("$.body['dry-run'].Secrets[0].Secret.data").value(PGP_DATA));

                // Dry run must not change anything.
                assertThat(groupUserRepository.findByGroupIdAndUserId(group.getId(), bob.getId()))
                                .isEmpty();
        }

        @Test
        void testUpdateDryRun_NoAdditions_EmptyLists() throws Exception {
                Group group = createGroup("Devs", testUser);

                GroupDto.UpdateRequest request = GroupDto.UpdateRequest.builder()
                                .name("Devs Renamed")
                                .build();

                mockMvc.perform(put("/groups/" + group.getId() + "/dry-run.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body['dry-run'].SecretsNeeded").isEmpty())
                                .andExpect(jsonPath("$.body['dry-run'].Secrets").isEmpty());
        }

        @Test
        void testUpdateDryRun_AsOutsider_Forbidden() throws Exception {
                Group group = createGroup("Bob Group", bob);

                GroupDto.UpdateRequest request = GroupDto.UpdateRequest.builder()
                                .name("X")
                                .build();

                mockMvc.perform(put("/groups/" + group.getId() + "/dry-run.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testUpdateDryRun_NotFound() throws Exception {
                GroupDto.UpdateRequest request = GroupDto.UpdateRequest.builder()
                                .name("X")
                                .build();

                mockMvc.perform(put("/groups/" + UUID.randomUUID() + "/dry-run.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testUpdateDryRun_UnknownUser_BadRequest() throws Exception {
                Group group = createGroup("Devs", testUser);

                GroupDto.UpdateRequest request = GroupDto.UpdateRequest.builder()
                                .groupsUsers(List.of(GroupDto.GroupUserChange.builder()
                                                .userId(UUID.randomUUID().toString())
                                                .isAdmin(false)
                                                .build()))
                                .build();

                mockMvc.perform(put("/groups/" + group.getId() + "/dry-run.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        // ------------------------------------------------------------------
        // DELETE /groups/{groupId}.json
        // ------------------------------------------------------------------

        @Test
        void testDeleteGroup_AsManager_Success() throws Exception {
                Group group = createGroup("Devs", testUser);
                addMember(group, bob, false);
                // Resource only accessible through the group: must be
                // soft-deleted with the group, and its secrets removed.
                Resource orphan = createResource("Orphan Password");
                createGroupPermission(orphan, group, Permission.OWNER);
                createSecret(orphan, bob);

                mockMvc.perform(delete("/groups/" + group.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"));

                assertThat(groupRepository.findById(group.getId()).orElseThrow().getDeleted()).isTrue();
                assertThat(groupUserRepository.findByGroupId(group.getId())).isEmpty();
                assertThat(permissionRepository
                                .findByAroAndAroForeignKey(Permission.GROUP_ARO, group.getId())).isEmpty();
                assertThat(resourceRepository.findById(orphan.getId()).orElseThrow().getDeleted()).isTrue();
                assertThat(secretRepository.findByResourceId(orphan.getId())).isEmpty();
        }

        @Test
        void testDeleteGroup_AsAdminNonMember_Success() throws Exception {
                makeTestUserAdmin();
                Group group = createGroup("Bob Group", bob);

                mockMvc.perform(delete("/groups/" + group.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"));

                assertThat(groupRepository.findById(group.getId()).orElseThrow().getDeleted()).isTrue();
        }

        @Test
        void testDeleteGroup_AsOutsider_Forbidden() throws Exception {
                Group group = createGroup("Bob Group", bob);

                mockMvc.perform(delete("/groups/" + group.getId() + ".json"))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.header.status").value("error"));

                assertThat(groupRepository.findById(group.getId()).orElseThrow().getDeleted()).isFalse();
        }

        @Test
        void testDeleteGroup_NotFound() throws Exception {
                mockMvc.perform(delete("/groups/" + UUID.randomUUID() + ".json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testDeleteGroup_SoleOwnerOfSharedResource_BadRequest() throws Exception {
                Group group = createGroup("Devs", testUser);
                Resource resource = createResource("Shared Password");
                // The group is the only OWNER, bob has READ: the resource is
                // shared and the deletion must be blocked.
                createGroupPermission(resource, group, Permission.OWNER);
                createUserPermission(resource, bob, Permission.READ);

                mockMvc.perform(delete("/groups/" + group.getId() + ".json"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.body.errors.resources.sole_owner.length()").value(1))
                                .andExpect(jsonPath("$.body.errors.resources.sole_owner[0].id")
                                                .value(resource.getId()));

                assertThat(groupRepository.findById(group.getId()).orElseThrow().getDeleted()).isFalse();
        }

        // ------------------------------------------------------------------
        // DELETE /groups/{groupId}/dry-run.json
        // ------------------------------------------------------------------

        @Test
        void testDeleteDryRun_Success() throws Exception {
                Group group = createGroup("Devs", testUser);
                Resource resource = createResource("Shared Password");
                // Bob is also OWNER: the group is not the sole owner.
                createGroupPermission(resource, group, Permission.OWNER);
                createUserPermission(resource, bob, Permission.OWNER);

                mockMvc.perform(delete("/groups/" + group.getId() + "/dry-run.json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body").isArray())
                                .andExpect(jsonPath("$.body.length()").value(1))
                                .andExpect(jsonPath("$.body[0].id").value(resource.getId()));

                // Dry run must not change anything.
                assertThat(groupRepository.findById(group.getId()).orElseThrow().getDeleted()).isFalse();
        }

        @Test
        void testDeleteDryRun_SoleOwner_BadRequest() throws Exception {
                Group group = createGroup("Devs", testUser);
                Resource resource = createResource("Shared Password");
                createGroupPermission(resource, group, Permission.OWNER);
                createUserPermission(resource, bob, Permission.READ);

                mockMvc.perform(delete("/groups/" + group.getId() + "/dry-run.json"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.body.errors.resources.sole_owner[0].id")
                                                .value(resource.getId()));
        }

        @Test
        void testDeleteDryRun_AsOutsider_Forbidden() throws Exception {
                Group group = createGroup("Bob Group", bob);

                mockMvc.perform(delete("/groups/" + group.getId() + "/dry-run.json"))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testDeleteDryRun_NotFound() throws Exception {
                mockMvc.perform(delete("/groups/" + UUID.randomUUID() + "/dry-run.json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }
}
