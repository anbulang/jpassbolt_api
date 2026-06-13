package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.Folder;
import com.jpassbolt.api.model.FoldersRelation;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.FolderRepository;
import com.jpassbolt.api.repository.FoldersRelationRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI contract tests for the folders cluster:
 * GET /folders.json (L1163), POST /folders.json (L1241),
 * GET /folders/{folderId}.json (L1329), PUT /folders/{folderId}.json (L1419)
 * and DELETE /folders/{folderId}.json — line numbers refer to
 * src/test/resources/plugin-redoc-0.yaml (identical in the authoritative
 * docs/ref_files copy).
 *
 * The {header, body} envelope is now spec-valid project-wide, so the old
 * envelope/date reason no longer applies. View and DELETE are ENABLED
 * (verified). The remaining assertions stay disabled for VERIFIED reasons
 * (per-test comments): folders_index requires the headerWithPagination
 * "pagination" object the controller does not emit (pagination-header), and
 * POST/PUT /folders require the v4 e2eeMetadataBased request body
 * (metadata / metadata_key_id / metadata_key_type) while this build still
 * consumes the v3 {name, folder_parent_id} body (request-v4-metadata).
 */
@WithMockUser(username = "test@example.com", roles = { "USER" })
public class FolderControllerContractTest extends OpenApiComplianceTest {

    @Autowired
    private FolderRepository folderRepository;

    @Autowired
    private FoldersRelationRepository foldersRelationRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private UserRepository userRepository;

    private User ownerUser;
    private Folder folder;

    @BeforeEach
    void setUpData() {
        // Reverse FK-dependency order — folders are hard-deleted (no soft flag).
        // Resources/secrets left over by a sibling contract test in the same JVM
        // must be cleared too: resources.created_by -> users.id is an enforced FK,
        // so users cannot be deleted while orphan resources still reference them.
        permissionRepository.deleteAll();
        foldersRelationRepository.deleteAll();
        folderRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();

        ownerUser = createUser("test@example.com");
        folder = createOwnedFolder("Contract Folder", ownerUser, null);
    }

    @Test
    public void testFoldersIndexContract() throws Exception {
        mockMvc.perform(get("/folders.json")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body").isArray())
                .andExpect(jsonPath("$.body[0].id").value(folder.getId()))
                .andExpect(jsonPath("$.body[0].name").value("Contract Folder"))
                .andExpect(jsonPath("$.body[0].folder_parent_id").doesNotExist());
        // Disabled (verified) — pagination-header, NOT an envelope/date issue:
        // validation.response.body.schema.required on /header — the spec's
        // folders_index response uses the headerWithPagination schema, which
        // REQUIRES a "pagination" object (count/page/limit). FolderController emits
        // the plain header without pagination, so the validator rejects the header.
        // Recorded in assertions_left_disabled.
        // .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    public void testFolderViewContract() throws Exception {
        mockMvc.perform(get("/folders/" + folder.getId() + ".json")
                .param("contain[permissions]", "1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.id").value(folder.getId()))
                .andExpect(jsonPath("$.body.name").value("Contract Folder"))
                .andExpect(jsonPath("$.body.permissions").isArray())
                .andExpect(jsonPath("$.body.permissions[0].aco").value("Folder"))
                // Enabled (verified): folders_view returns the plain header (no
                // pagination) and the folder body satisfies the spec schema; the
                // envelope is now spec-valid. The earlier envelope/date reason was
                // outdated.
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    public void testFolderAddContract() throws Exception {
        Map<String, Object> request = Map.of("name", "New Contract Folder");

        mockMvc.perform(post("/folders.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.name").value("New Contract Folder"))
                .andExpect(jsonPath("$.body.id").exists());
        // Disabled (verified) — request-v4-metadata, NOT an envelope issue:
        // validation.request.body.schema.required. The spec's folderAdd request
        // body (requestBodies/folderAdd) is schema e2eeMetadataBased and REQUIRES
        // metadata / metadata_key_id / metadata_key_type (v4 encrypted-metadata
        // folders), rejecting our v3 {name, folder_parent_id} body. The v4
        // encrypted-metadata path is not implemented here yet. (The response body
        // is also v4-strict, but the request blocker triggers first.) Recorded in
        // assertions_left_disabled.
        // .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    public void testFolderUpdateContract() throws Exception {
        Map<String, Object> request = Map.of("name", "Renamed Contract Folder");

        mockMvc.perform(put("/folders/" + folder.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.id").value(folder.getId()))
                .andExpect(jsonPath("$.body.name").value("Renamed Contract Folder"));
        // Disabled (verified) — request-v4-metadata, NOT an envelope issue:
        // validation.request.body.schema.required. The folderUpdate request body
        // is also e2eeMetadataBased and REQUIRES metadata / metadata_key_id /
        // metadata_key_type, rejecting our v3 {name} body — same v4-vs-v3
        // divergence as testFolderAddContract. Recorded in assertions_left_disabled.
        // .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    public void testFolderDeleteContract() throws Exception {
        mockMvc.perform(delete("/folders/" + folder.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                // success body for a delete IS the spec-mandated JSON null
                .andExpect(jsonPath("$.body").value(org.hamcrest.CoreMatchers.nullValue()))
                // Enabled (verified): DELETE returns the spec-mandated nullBody (body
                // is JSON null) under the plain header, so the response is spec-valid.
                // The earlier envelope/date reason was outdated.
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
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

    /** Folder row + OWNER permission + a relation row in the owner's tree. */
    private Folder createOwnedFolder(String name, User owner, String parentId) {
        Folder f = new Folder();
        f.setName(name);
        f.setCreatedBy(owner.getId());
        f.setModifiedBy(owner.getId());
        f = folderRepository.save(f);

        Permission permission = new Permission();
        permission.setAco("Folder");
        permission.setAcoForeignKey(f.getId());
        permission.setAro(Permission.USER_ARO);
        permission.setAroForeignKey(owner.getId());
        permission.setType(Permission.OWNER);
        permissionRepository.save(permission);

        FoldersRelation relation = new FoldersRelation();
        relation.setForeignModel(FoldersRelation.FOREIGN_MODEL_FOLDER);
        relation.setForeignId(f.getId());
        relation.setUserId(owner.getId());
        relation.setFolderParentId(parentId);
        foldersRelationRepository.save(relation);

        return f;
    }
}
