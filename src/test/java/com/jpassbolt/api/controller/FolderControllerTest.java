package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.FolderDto;
import com.jpassbolt.api.model.Folder;
import com.jpassbolt.api.model.FoldersRelation;
import com.jpassbolt.api.model.MetadataKey;
import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.FolderRepository;
import com.jpassbolt.api.repository.FoldersRelationRepository;
import com.jpassbolt.api.repository.MetadataKeyRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.FolderService;
import org.hamcrest.Matchers;
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
 * Integration tests for FolderController (folders CRUD, per-user tree).
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class FolderControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private FolderRepository folderRepository;

        @Autowired
        private FoldersRelationRepository foldersRelationRepository;

        @Autowired
        private PermissionRepository permissionRepository;

        @Autowired
        private ResourceRepository resourceRepository;

        @Autowired
        private UserRepository userRepository;

        @Autowired
        private MetadataKeyRepository metadataKeyRepository;

        @Autowired
        private OrganizationSettingRepository organizationSettingRepository;

        @Autowired
        private ObjectMapper objectMapper;

        private User testUser;
        private User otherUser;

        /** Single-line placeholder accepted by the lenient parse-only validator. */
        private static final String ARMORED = "-----BEGIN PGP MESSAGE-----";

        @BeforeEach
        void setUp() {
                organizationSettingRepository.deleteAll();
                metadataKeyRepository.deleteAll();
                foldersRelationRepository.deleteAll();
                permissionRepository.deleteAll();
                folderRepository.deleteAll();
                resourceRepository.deleteAll();
                userRepository.deleteAll();

                testUser = new User();
                testUser.setUsername("test@example.com");
                testUser.setRoleId("user");
                testUser.setActive(true);
                testUser.setDeleted(false);
                userRepository.save(testUser);

                otherUser = new User();
                otherUser.setUsername("other@example.com");
                otherUser.setRoleId("user");
                otherUser.setActive(true);
                otherUser.setDeleted(false);
                userRepository.save(otherUser);
        }

        /**
         * Helper: folder + permission + relation row in the user's tree.
         */
        private Folder createFolderFor(String name, User user, String parentId, int permType) {
                Folder folder = new Folder();
                folder.setName(name);
                folder.setCreatedBy(user.getId());
                folder.setModifiedBy(user.getId());
                folderRepository.save(folder);

                Permission perm = new Permission();
                perm.setAco(FolderService.FOLDER_ACO);
                perm.setAcoForeignKey(folder.getId());
                perm.setAro(Permission.USER_ARO);
                perm.setAroForeignKey(user.getId());
                perm.setType(permType);
                permissionRepository.save(perm);

                addRelation(FoldersRelation.FOREIGN_MODEL_FOLDER, folder.getId(), user.getId(), parentId);
                return folder;
        }

        /**
         * Helper: resource + permission + relation row in the user's tree.
         */
        private Resource createResourceFor(String name, User user, String parentId, int permType) {
                Resource resource = new Resource();
                resource.setName(name);
                resource.setCreatedBy(user.getId());
                resource.setModifiedBy(user.getId());
                resource.setDeleted(false);
                resourceRepository.save(resource);

                Permission perm = new Permission();
                perm.setAco(Permission.RESOURCE_ACO);
                perm.setAcoForeignKey(resource.getId());
                perm.setAro(Permission.USER_ARO);
                perm.setAroForeignKey(user.getId());
                perm.setType(permType);
                permissionRepository.save(perm);

                addRelation(FoldersRelation.FOREIGN_MODEL_RESOURCE, resource.getId(), user.getId(), parentId);
                return resource;
        }

        private FoldersRelation addRelation(String foreignModel, String foreignId, String userId, String parentId) {
                FoldersRelation rel = new FoldersRelation();
                rel.setForeignModel(foreignModel);
                rel.setForeignId(foreignId);
                rel.setUserId(userId);
                rel.setFolderParentId(parentId);
                return foldersRelationRepository.save(rel);
        }

        // ---------------------------------------------------------------
        // GET /folders.json
        // ---------------------------------------------------------------

        @Test
        void testGetAllFolders_ReturnsEmptyList() throws Exception {
                mockMvc.perform(get("/folders.json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body").isArray())
                                .andExpect(jsonPath("$.body").isEmpty());
        }

        @Test
        void testGetAllFolders_OnlyShowsPermittedFolders() throws Exception {
                createFolderFor("Visible", testUser, null, Permission.READ);
                // A folder of another user — invisible to testUser
                createFolderFor("Hidden", otherUser, null, Permission.OWNER);

                mockMvc.perform(get("/folders.json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.length()").value(1))
                                .andExpect(jsonPath("$.body[0].name").value("Visible"))
                                .andExpect(jsonPath("$.body[0].folder_parent_id").value(Matchers.nullValue()))
                                .andExpect(jsonPath("$.body[0].personal").value(true));
        }

        @Test
        void testGetAllFolders_FilterHasId() throws Exception {
                Folder wanted = createFolderFor("Wanted", testUser, null, Permission.OWNER);
                createFolderFor("Other", testUser, null, Permission.OWNER);

                mockMvc.perform(get("/folders.json").param("filter[has-id]", wanted.getId()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.length()").value(1))
                                .andExpect(jsonPath("$.body[0].id").value(wanted.getId()));
        }

        @Test
        void testGetAllFolders_ContainChildrenAndPermissions() throws Exception {
                Folder parent = createFolderFor("Parent", testUser, null, Permission.OWNER);
                Folder child = createFolderFor("Child", testUser, parent.getId(), Permission.OWNER);
                Resource childResource = createResourceFor("ChildRes", testUser, parent.getId(), Permission.OWNER);

                mockMvc.perform(get("/folders.json")
                                .param("filter[has-id]", parent.getId())
                                .param("contain[children_folders]", "1")
                                .param("contain[children_resources]", "1")
                                .param("contain[permissions]", "1"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.length()").value(1))
                                .andExpect(jsonPath("$.body[0].children_folders.length()").value(1))
                                .andExpect(jsonPath("$.body[0].children_folders[0].id").value(child.getId()))
                                .andExpect(jsonPath("$.body[0].children_folders[0].folder_parent_id")
                                                .value(parent.getId()))
                                .andExpect(jsonPath("$.body[0].children_resources.length()").value(1))
                                .andExpect(jsonPath("$.body[0].children_resources[0].id")
                                                .value(childResource.getId()))
                                .andExpect(jsonPath("$.body[0].permissions.length()").value(1))
                                .andExpect(jsonPath("$.body[0].permissions[0].aco").value("Folder"))
                                .andExpect(jsonPath("$.body[0].permissions[0].aro_foreign_key")
                                                .value(testUser.getId()));
        }

        // ---------------------------------------------------------------
        // GET /folders/{id}.json
        // ---------------------------------------------------------------

        @Test
        void testGetFolder_WithPermission() throws Exception {
                Folder folder = createFolderFor("My Folder", testUser, null, Permission.READ);

                mockMvc.perform(get("/folders/" + folder.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.name").value("My Folder"))
                                .andExpect(jsonPath("$.body.folder_parent_id").value(Matchers.nullValue()))
                                .andExpect(jsonPath("$.body.personal").value(true));
        }

        @Test
        void testGetFolder_WithoutPermission_NotFound() throws Exception {
                // Exists but belongs to another user — PHP renders view misses as 404
                Folder folder = createFolderFor("Foreign", otherUser, null, Permission.OWNER);

                mockMvc.perform(get("/folders/" + folder.getId() + ".json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testGetFolder_NotExisting_NotFound() throws Exception {
                mockMvc.perform(get("/folders/" + UUID.randomUUID() + ".json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        // ---------------------------------------------------------------
        // POST /folders.json
        // ---------------------------------------------------------------

        @Test
        void testCreateFolder_CreatesPermissionAndRelation() throws Exception {
                FolderDto.CreateRequest request = FolderDto.CreateRequest.builder()
                                .name("New Folder")
                                .build();

                mockMvc.perform(post("/folders.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.name").value("New Folder"))
                                .andExpect(jsonPath("$.body.folder_parent_id").value(Matchers.nullValue()))
                                .andExpect(jsonPath("$.body.personal").value(true));

                List<Folder> folders = folderRepository.findAll();
                assertThat(folders).hasSize(1);
                String folderId = folders.get(0).getId();

                // OWNER permission with aco = "Folder"
                List<Permission> perms = permissionRepository
                                .findByAcoAndAcoForeignKey(FolderService.FOLDER_ACO, folderId);
                assertThat(perms).hasSize(1);
                assertThat(perms.get(0).getType()).isEqualTo(Permission.OWNER);
                assertThat(perms.get(0).getAroForeignKey()).isEqualTo(testUser.getId());

                // Relation row at the root of the creator's tree
                FoldersRelation rel = foldersRelationRepository
                                .findByUserIdAndForeignId(testUser.getId(), folderId).orElseThrow();
                assertThat(rel.getForeignModel()).isEqualTo(FoldersRelation.FOREIGN_MODEL_FOLDER);
                assertThat(rel.getFolderParentId()).isNull();
        }

        @Test
        void testCreateFolder_WithParent() throws Exception {
                Folder parent = createFolderFor("Parent", testUser, null, Permission.OWNER);

                FolderDto.CreateRequest request = FolderDto.CreateRequest.builder()
                                .name("Child")
                                .folderParentId(parent.getId())
                                .build();

                mockMvc.perform(post("/folders.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.folder_parent_id").value(parent.getId()));
        }

        @Test
        void testCreateFolder_ParentReadOnly_BadRequest() throws Exception {
                // PHP: a parent the user cannot write into is a validation error (400)
                Folder parent = createFolderFor("ReadOnlyParent", testUser, null, Permission.READ);

                FolderDto.CreateRequest request = FolderDto.CreateRequest.builder()
                                .name("Child")
                                .folderParentId(parent.getId())
                                .build();

                mockMvc.perform(post("/folders.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testCreateFolder_ParentNotExisting_BadRequest() throws Exception {
                FolderDto.CreateRequest request = FolderDto.CreateRequest.builder()
                                .name("Child")
                                .folderParentId(UUID.randomUUID().toString())
                                .build();

                mockMvc.perform(post("/folders.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testCreateFolder_EmptyName_BadRequest() throws Exception {
                mockMvc.perform(post("/folders.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"\"}"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));

                assertThat(folderRepository.findAll()).isEmpty();
        }

        // ---------------------------------------------------------------
        // PUT /folders/{id}.json
        // ---------------------------------------------------------------

        @Test
        void testUpdateFolder_WithUpdatePermission() throws Exception {
                Folder folder = createFolderFor("Original", testUser, null, Permission.UPDATE);

                mockMvc.perform(put("/folders/" + folder.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Renamed\"}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.name").value("Renamed"));

                Folder updated = folderRepository.findById(folder.getId()).orElseThrow();
                assertThat(updated.getName()).isEqualTo("Renamed");
                assertThat(updated.getModifiedBy()).isEqualTo(testUser.getId());
        }

        @Test
        void testUpdateFolder_ReadOnly_Forbidden() throws Exception {
                Folder folder = createFolderFor("ReadOnly", testUser, null, Permission.READ);

                mockMvc.perform(put("/folders/" + folder.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Renamed\"}"))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testUpdateFolder_NoPermission_NotFound() throws Exception {
                // PHP: no permission at all on the folder renders a 404
                Folder folder = createFolderFor("Foreign", otherUser, null, Permission.OWNER);

                mockMvc.perform(put("/folders/" + folder.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Renamed\"}"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testUpdateFolder_EmptyName_BadRequest() throws Exception {
                Folder folder = createFolderFor("Original", testUser, null, Permission.OWNER);

                mockMvc.perform(put("/folders/" + folder.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"\"}"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        // ---------------------------------------------------------------
        // DELETE /folders/{id}.json
        // ---------------------------------------------------------------

        @Test
        void testDeleteFolder_MovesChildrenToRoot() throws Exception {
                Folder parent = createFolderFor("Parent", testUser, null, Permission.OWNER);
                Folder child = createFolderFor("Child", testUser, parent.getId(), Permission.OWNER);

                mockMvc.perform(delete("/folders/" + parent.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"));

                // Hard delete: folder row, relations and permissions are gone
                assertThat(folderRepository.findById(parent.getId())).isEmpty();
                assertThat(foldersRelationRepository.findByForeignId(parent.getId())).isEmpty();
                assertThat(permissionRepository
                                .findByAcoAndAcoForeignKey(FolderService.FOLDER_ACO, parent.getId())).isEmpty();

                // Child moved to the root, not deleted
                assertThat(folderRepository.findById(child.getId())).isPresent();
                FoldersRelation childRel = foldersRelationRepository
                                .findByUserIdAndForeignId(testUser.getId(), child.getId()).orElseThrow();
                assertThat(childRel.getFolderParentId()).isNull();
        }

        @Test
        void testDeleteFolder_Cascade_DeletesContent() throws Exception {
                Folder parent = createFolderFor("Parent", testUser, null, Permission.OWNER);
                Folder childFolder = createFolderFor("ChildFolder", testUser, parent.getId(), Permission.OWNER);
                Resource childResource = createResourceFor("ChildRes", testUser, parent.getId(), Permission.OWNER);

                mockMvc.perform(delete("/folders/" + parent.getId() + ".json").param("cascade", "1"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"));

                // Folders hard deleted, resource soft deleted
                assertThat(folderRepository.findById(parent.getId())).isEmpty();
                assertThat(folderRepository.findById(childFolder.getId())).isEmpty();
                Resource deletedResource = resourceRepository.findById(childResource.getId()).orElseThrow();
                assertThat(deletedResource.getDeleted()).isTrue();

                // Tree rows are gone for all of them
                assertThat(foldersRelationRepository.findByForeignId(parent.getId())).isEmpty();
                assertThat(foldersRelationRepository.findByForeignId(childFolder.getId())).isEmpty();
                assertThat(foldersRelationRepository.findByForeignId(childResource.getId())).isEmpty();
        }

        @Test
        void testDeleteFolder_ReadOnly_Forbidden() throws Exception {
                Folder folder = createFolderFor("ReadOnly", testUser, null, Permission.READ);

                mockMvc.perform(delete("/folders/" + folder.getId() + ".json"))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.header.status").value("error"));

                assertThat(folderRepository.findById(folder.getId())).isPresent();
        }

        @Test
        void testDeleteFolder_NotExisting_NotFound() throws Exception {
                mockMvc.perform(delete("/folders/" + UUID.randomUUID() + ".json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        // =================================================================
        // v4 <-> v5 metadata fusion (FolderService.createFolder /
        // updateFolder branch on request.getMetadata() != null).
        //
        // v5 path: the encrypted metadata blob is stored VERBATIM
        // (zero-knowledge — never decrypted), the metadata trio persists, the
        // v4 plaintext name column is left NULL, and the OWNER permission +
        // tree relation are written as usual. The response carries the
        // metadata trio + folder_parent_id + personal and OMITS name
        // (NON_NULL). The folder gate / structural checks all surface as 400.
        // =================================================================

        @Test
        void testCreateFolder_V5_StoresMetadataVerbatim_NameNull() throws Exception {
                enableV5FolderCreation();
                MetadataKey key = seedActiveSharedKey();

                FolderDto.CreateRequest request = FolderDto.CreateRequest.builder()
                                .metadata(ARMORED)
                                .metadataKeyId(key.getId())
                                .metadataKeyType("shared_key")
                                .build();

                mockMvc.perform(post("/folders.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.metadata").value(ARMORED))
                                .andExpect(jsonPath("$.body.metadata_key_id").value(key.getId()))
                                .andExpect(jsonPath("$.body.metadata_key_type").value("shared_key"))
                                .andExpect(jsonPath("$.body.folder_parent_id").value(Matchers.nullValue()))
                                .andExpect(jsonPath("$.body.personal").value(true))
                                // v5 folder omits name (NON_NULL).
                                .andExpect(jsonPath("$.body.name").doesNotExist());

                List<Folder> folders = folderRepository.findAll();
                assertThat(folders).hasSize(1);
                Folder stored = folders.get(0);
                assertThat(stored.getMetadata()).isEqualTo(ARMORED);
                assertThat(stored.getMetadataKeyId()).isEqualTo(key.getId());
                assertThat(stored.getMetadataKeyType()).isEqualTo("shared_key");
                assertThat(stored.getName()).isNull();

                // OWNER permission + tree relation still written in the v5 branch.
                List<Permission> perms = permissionRepository
                                .findByAcoAndAcoForeignKey(FolderService.FOLDER_ACO, stored.getId());
                assertThat(perms).hasSize(1);
                assertThat(perms.get(0).getType()).isEqualTo(Permission.OWNER);
                FoldersRelation rel = foldersRelationRepository
                                .findByUserIdAndForeignId(testUser.getId(), stored.getId()).orElseThrow();
                assertThat(rel.getFolderParentId()).isNull();
        }

        @Test
        void testCreateFolder_V5_WithParent_HonorsTree() throws Exception {
                enableV5FolderCreation();
                MetadataKey key = seedActiveSharedKey();
                Folder parent = createFolderFor("Parent", testUser, null, Permission.OWNER);

                FolderDto.CreateRequest request = FolderDto.CreateRequest.builder()
                                .metadata(ARMORED)
                                .metadataKeyId(key.getId())
                                .metadataKeyType("shared_key")
                                .folderParentId(parent.getId())
                                .build();

                mockMvc.perform(post("/folders.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.metadata").value(ARMORED))
                                .andExpect(jsonPath("$.body.folder_parent_id").value(parent.getId()));
        }

        @Test
        void testUpdateFolder_V5_SetsMetadataTrio() throws Exception {
                enableV5FolderCreation();
                MetadataKey key = seedActiveSharedKey();
                Folder folder = createFolderFor("v4 name", testUser, null, Permission.UPDATE);

                FolderDto.UpdateRequest request = FolderDto.UpdateRequest.builder()
                                .metadata(ARMORED)
                                .metadataKeyId(key.getId())
                                .metadataKeyType("shared_key")
                                .build();

                mockMvc.perform(put("/folders/" + folder.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.metadata").value(ARMORED))
                                .andExpect(jsonPath("$.body.metadata_key_id").value(key.getId()))
                                .andExpect(jsonPath("$.body.metadata_key_type").value("shared_key"));

                Folder stored = folderRepository.findById(folder.getId()).orElseThrow();
                assertThat(stored.getMetadata()).isEqualTo(ARMORED);
                assertThat(stored.getMetadataKeyId()).isEqualTo(key.getId());
                assertThat(stored.getMetadataKeyType()).isEqualTo("shared_key");
                // The stale v4 plaintext name MUST be cleared on the v5 upgrade
                // (PHP FoldersUpdateService::patchEntity nulls it server-side) —
                // otherwise the row leaks the old plaintext name alongside the blob.
                assertThat(stored.getName()).isNull();
        }

        @Test
        void testUpdateFolder_MixedPayload_BadRequest() throws Exception {
                enableV5FolderCreation();
                MetadataKey key = seedActiveSharedKey();
                Folder folder = createFolderFor("v4 name", testUser, null, Permission.UPDATE);

                FolderDto.UpdateRequest request = FolderDto.UpdateRequest.builder()
                                .name("v4 name not allowed with v5")
                                .metadata(ARMORED)
                                .metadataKeyId(key.getId())
                                .metadataKeyType("shared_key")
                                .build();

                mockMvc.perform(put("/folders/" + folder.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message")
                                                .value(Matchers.containsString(
                                                                "V4 related fields are not supported for V5")));

                // Rejected: the v4 row is untouched (no partial/corrupt write).
                Folder stored = folderRepository.findById(folder.getId()).orElseThrow();
                assertThat(stored.getName()).isEqualTo("v4 name");
                assertThat(stored.getMetadata()).isNull();
        }

        @Test
        void testGetFolder_V5_EmitsMetadataTrio_OmitsName() throws Exception {
                MetadataKey key = seedActiveSharedKey();
                Folder folder = createV5FolderFor(testUser, key.getId());

                mockMvc.perform(get("/folders/" + folder.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.metadata").value(ARMORED))
                                .andExpect(jsonPath("$.body.metadata_key_id").value(key.getId()))
                                .andExpect(jsonPath("$.body.metadata_key_type").value("shared_key"))
                                .andExpect(jsonPath("$.body.name").doesNotExist())
                                .andExpect(jsonPath("$.body.personal").value(true));
        }

        @Test
        void testCreateFolder_MixedPayload_BadRequest() throws Exception {
                enableV5FolderCreation();
                MetadataKey key = seedActiveSharedKey();

                FolderDto.CreateRequest request = FolderDto.CreateRequest.builder()
                                .name("v4 name not allowed with v5")
                                .metadata(ARMORED)
                                .metadataKeyId(key.getId())
                                .metadataKeyType("shared_key")
                                .build();

                mockMvc.perform(post("/folders.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message")
                                                .value(Matchers.containsString(
                                                                "V4 related fields are not supported for V5")));

                assertThat(folderRepository.findAll()).isEmpty();
        }

        @Test
        void testCreateFolder_PartialV5_BadRequest() throws Exception {
                enableV5FolderCreation();
                // metadata + metadata_key_id but NO metadata_key_type -> partial-v5.
                MetadataKey key = seedActiveSharedKey();

                FolderDto.CreateRequest request = FolderDto.CreateRequest.builder()
                                .metadata(ARMORED)
                                .metadataKeyId(key.getId())
                                .build();

                mockMvc.perform(post("/folders.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message")
                                                .value(Matchers.containsString("Few fields are missing for the V5")));

                assertThat(folderRepository.findAll()).isEmpty();
        }

        @Test
        void testCreateFolder_PartialAndMixedV5_ReportsMissingFieldsFirst() throws Exception {
                enableV5FolderCreation();
                // Simultaneously PARTIAL (no metadata_key_type) AND MIXED (a v4 name).
                // PHP MetadataFolderDto::validate checks partial-v5 BEFORE the
                // superfluous-v4 (mixed) check, so the missing-fields message wins.
                MetadataKey key = seedActiveSharedKey();

                FolderDto.CreateRequest request = FolderDto.CreateRequest.builder()
                                .name("v4 name present")
                                .metadata(ARMORED)
                                .metadataKeyId(key.getId())
                                .build();

                mockMvc.perform(post("/folders.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message")
                                                .value(Matchers.containsString("Few fields are missing for the V5")));

                assertThat(folderRepository.findAll()).isEmpty();
        }

        @Test
        void testCreateFolder_V5_MalformedMetadataBlob_BadRequest() throws Exception {
                enableV5FolderCreation();
                MetadataKey key = seedActiveSharedKey();

                FolderDto.CreateRequest request = FolderDto.CreateRequest.builder()
                                .metadata("not an armored pgp message")
                                .metadataKeyId(key.getId())
                                .metadataKeyType("shared_key")
                                .build();

                mockMvc.perform(post("/folders.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));

                assertThat(folderRepository.findAll()).isEmpty();
        }

        @Test
        void testCreateFolder_V5_BadMetadataKeyType_BadRequest() throws Exception {
                enableV5FolderCreation();
                MetadataKey key = seedActiveSharedKey();

                FolderDto.CreateRequest request = FolderDto.CreateRequest.builder()
                                .metadata(ARMORED)
                                .metadataKeyId(key.getId())
                                .metadataKeyType("bogus_type")
                                .build();

                mockMvc.perform(post("/folders.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));

                assertThat(folderRepository.findAll()).isEmpty();
        }

        @Test
        void testCreateFolder_V5_DisabledBySettings_BadRequest() throws Exception {
                // No metadataTypes row -> allow_creation_of_v5_folders defaults to
                // false. The folder service throws PassboltApiException(BAD_REQUEST);
                // FolderController does not wrap, so the global handler surfaces 400.
                MetadataKey key = seedActiveSharedKey();

                FolderDto.CreateRequest request = FolderDto.CreateRequest.builder()
                                .metadata(ARMORED)
                                .metadataKeyId(key.getId())
                                .metadataKeyType("shared_key")
                                .build();

                mockMvc.perform(post("/folders.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message")
                                                .value(Matchers.containsString("prevent from creating a V5 folder")));

                assertThat(folderRepository.findAll()).isEmpty();
        }

        // ----------------------------------------------------------------
        // v5 helpers
        // ----------------------------------------------------------------

        /** Seed the metadataTypes org-setting flipping v5 folder creation ON. */
        private void enableV5FolderCreation() {
                OrganizationSetting setting = new OrganizationSetting();
                setting.setProperty("metadataTypes");
                setting.setPropertyId(UUID.randomUUID().toString());
                setting.setValue("{\"allow_creation_of_v5_folders\":true}");
                setting.setCreatedBy(testUser.getId());
                setting.setModifiedBy(testUser.getId());
                organizationSettingRepository.save(setting);
        }

        private MetadataKey seedActiveSharedKey() {
                MetadataKey key = new MetadataKey();
                key.setFingerprint(randomFingerprint());
                key.setArmoredKey("-----BEGIN PGP PUBLIC KEY BLOCK-----");
                key.setCreatedBy(testUser.getId());
                key.setModifiedBy(testUser.getId());
                return metadataKeyRepository.save(key);
        }

        /** A persisted v5 folder (metadata trio, name null) + OWNER perm + tree row. */
        private Folder createV5FolderFor(User user, String metadataKeyId) {
                Folder folder = new Folder();
                folder.setMetadata(ARMORED);
                folder.setMetadataKeyId(metadataKeyId);
                folder.setMetadataKeyType("shared_key");
                folder.setCreatedBy(user.getId());
                folder.setModifiedBy(user.getId());
                folderRepository.save(folder);

                Permission perm = new Permission();
                perm.setAco(FolderService.FOLDER_ACO);
                perm.setAcoForeignKey(folder.getId());
                perm.setAro(Permission.USER_ARO);
                perm.setAroForeignKey(user.getId());
                perm.setType(Permission.OWNER);
                permissionRepository.save(perm);

                addRelation(FoldersRelation.FOREIGN_MODEL_FOLDER, folder.getId(), user.getId(), null);
                return folder;
        }

        /** 40-hex-char fingerprint (column is varchar(51) unique). */
        private String randomFingerprint() {
                return (UUID.randomUUID().toString() + UUID.randomUUID().toString())
                                .replace("-", "").substring(0, 40).toUpperCase();
        }
}
