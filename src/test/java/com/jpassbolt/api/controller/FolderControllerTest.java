package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.FolderDto;
import com.jpassbolt.api.model.Folder;
import com.jpassbolt.api.model.FoldersRelation;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.FolderRepository;
import com.jpassbolt.api.repository.FoldersRelationRepository;
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
        private ObjectMapper objectMapper;

        private User testUser;
        private User otherUser;

        @BeforeEach
        void setUp() {
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
}
