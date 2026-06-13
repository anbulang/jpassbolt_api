package com.jpassbolt.api.controller;

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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;
import java.util.Map;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI contract tests for the group-share cluster: the existing
 * PUT /share/{foreignModel}/{foreignId}.json (L5646) and
 * POST /share/simulate/{foreignModel}/{foreignId}.json (L5815) exercised in
 * the ARO=Group dimension — no new path, the spec's permissionUpdate.aro
 * enum already contains "Group" (line numbers refer to
 * src/test/resources/plugin-redoc-0.yaml, identical in the authoritative
 * docs/ref_files copy).
 *
 * The openApi().isValid(CONTRACT_VALIDATOR) contract assertion is enabled on
 * the PUT /share update test. It remains intentionally disabled only on the
 * simulate test, for a body-shape reason (the spec self-contradiction around
 * changes.added vs top-level added/removed) — see the per-test comment.
 */
@WithMockUser(username = "test@example.com", roles = { "USER" })
public class GroupShareControllerContractTest extends OpenApiComplianceTest {

    private static final String PGP_DATA = "-----BEGIN PGP MESSAGE-----\nData\n-----END PGP MESSAGE-----";

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

    private User ownerUser;
    private User memberA;
    private User memberB;
    private Resource resource;
    private Group group;

    @BeforeEach
    void setUpData() {
        // Reverse FK-dependency order
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        groupUserRepository.deleteAll();
        groupRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();

        ownerUser = createUser("test@example.com");
        memberA = createUser("membera@example.com");
        memberB = createUser("memberb@example.com");

        resource = new Resource();
        resource.setName("Group Contract Password");
        resource.setUsername("admin");
        resource.setUri("https://group-contract.example.com");
        resource.setCreatedBy(ownerUser.getId());
        resource.setModifiedBy(ownerUser.getId());
        resource.setDeleted(false);
        resourceRepository.save(resource);

        Permission ownerPermission = new Permission();
        ownerPermission.setAco(Permission.RESOURCE_ACO);
        ownerPermission.setAcoForeignKey(resource.getId());
        ownerPermission.setAro(Permission.USER_ARO);
        ownerPermission.setAroForeignKey(ownerUser.getId());
        ownerPermission.setType(Permission.OWNER);
        permissionRepository.save(ownerPermission);

        Secret ownerSecret = new Secret();
        ownerSecret.setResourceId(resource.getId());
        ownerSecret.setUserId(ownerUser.getId());
        ownerSecret.setData(PGP_DATA);
        secretRepository.save(ownerSecret);

        group = new Group();
        group.setName("Contract Devs");
        group.setDeleted(false);
        group.setCreatedBy(ownerUser.getId());
        group.setModifiedBy(ownerUser.getId());
        group = groupRepository.save(group);
        addMember(group, memberA);
        addMember(group, memberB);
    }

    @Test
    public void testGroupShareUpdateContract() throws Exception {
        Map<String, Object> request = Map.of(
                "permissions", List.of(Map.of(
                        "aro", "Group",
                        "aro_foreign_key", group.getId(),
                        "type", Permission.READ,
                        "is_new", true)),
                "secrets", List.of(
                        Map.of("user_id", memberA.getId(), "data", PGP_DATA),
                        Map.of("user_id", memberB.getId(), "data", PGP_DATA)));

        mockMvc.perform(put("/share/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                // Spec-mandated nullBody (responses/nullBody): body IS JSON null
                .andExpect(jsonPath("$.body").value(nullValue()))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    public void testGroupShareSimulateContract() throws Exception {
        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "aro", "Group",
                "aro_foreign_key", group.getId(),
                "type", Permission.READ,
                "is_new", true)));

        mockMvc.perform(post("/share/simulate/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                // added = the full group fan-out (every member newly gaining
                // access) — the client encrypts one secret per entry
                .andExpect(jsonPath("$.body.changes.added[*].User.id",
                        containsInAnyOrder(memberA.getId(), memberB.getId())))
                .andExpect(jsonPath("$.body.changes.removed").isEmpty());
        // Disabled (verified) — body-shape-divergent, NOT an envelope/date issue:
        // validation.response.body.schema.additionalProperties ("[changes] not
        // allowed") + .required ("missing [added, removed]") on /body. The spec
        // contradicts ITSELF for this endpoint — schema shareUpdateDryRun requires
        // top-level added/removed, while the official example and the actual PHP
        // output wrap them in "changes". The plugin consumes changes.added, so we
        // follow PHP/the example; flattening the body to satisfy the schema would
        // break plugin compatibility. Same precedent as
        // ShareExtrasContractTest.testShareSimulateContract. Recorded in
        // assertions_left_disabled.
        // .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
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

    private void addMember(Group group, User user) {
        GroupUser groupUser = new GroupUser();
        groupUser.setGroupId(group.getId());
        groupUser.setUserId(user.getId());
        groupUser.setIsAdmin(false);
        groupUserRepository.save(groupUser);
    }
}
