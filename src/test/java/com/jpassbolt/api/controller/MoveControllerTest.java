package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.Folder;
import com.jpassbolt.api.model.FoldersRelation;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.FolderRepository;
import com.jpassbolt.api.repository.FoldersRelationRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.FolderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for MoveController
 * (PUT|POST /move/{foreignModel}/{foreignId}.json).
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class MoveControllerTest {

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
        private SecretRepository secretRepository;

        @Autowired
        private UserRepository userRepository;

        private User testUser;
        private User otherUser;

        @BeforeEach
        void setUp() {
                foldersRelationRepository.deleteAll();
                permissionRepository.deleteAll();
                folderRepository.deleteAll();
                secretRepository.deleteAll();
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

        private String moveBody(String folderParentId) {
                return folderParentId == null
                                ? "{\"folder_parent_id\":null}"
                                : "{\"folder_parent_id\":\"" + folderParentId + "\"}";
        }

        // ---------------------------------------------------------------
        // Success cases
        // ---------------------------------------------------------------

        @Test
        void testMoveFolderIntoAnotherFolder() throws Exception {
                Folder destination = createFolderFor("Destination", testUser, null, Permission.OWNER);
                Folder moved = createFolderFor("Moved", testUser, null, Permission.OWNER);

                mockMvc.perform(put("/move/folder/" + moved.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(moveBody(destination.getId())))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"));

                FoldersRelation rel = foldersRelationRepository
                                .findByUserIdAndForeignId(testUser.getId(), moved.getId()).orElseThrow();
                assertThat(rel.getFolderParentId()).isEqualTo(destination.getId());
        }

        @Test
        void testMoveFolderToRoot() throws Exception {
                Folder parent = createFolderFor("Parent", testUser, null, Permission.OWNER);
                Folder moved = createFolderFor("Moved", testUser, parent.getId(), Permission.OWNER);

                mockMvc.perform(put("/move/folder/" + moved.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(moveBody(null)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"));

                FoldersRelation rel = foldersRelationRepository
                                .findByUserIdAndForeignId(testUser.getId(), moved.getId()).orElseThrow();
                assertThat(rel.getFolderParentId()).isNull();
        }

        @Test
        void testMoveResourceIntoFolder_WithPost() throws Exception {
                // POST is registered alongside PUT (PHP route setMethods(['PUT','POST']))
                Folder destination = createFolderFor("Destination", testUser, null, Permission.OWNER);
                Resource moved = createResourceFor("MovedRes", testUser, null, Permission.OWNER);

                mockMvc.perform(post("/move/resource/" + moved.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(moveBody(destination.getId())))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"));

                FoldersRelation rel = foldersRelationRepository
                                .findByUserIdAndForeignId(testUser.getId(), moved.getId()).orElseThrow();
                assertThat(rel.getFolderParentId()).isEqualTo(destination.getId());
                assertThat(rel.getForeignModel()).isEqualTo(FoldersRelation.FOREIGN_MODEL_RESOURCE);
        }

        // ---------------------------------------------------------------
        // Validation negatives (400)
        // ---------------------------------------------------------------

        @Test
        void testMoveInvalidForeignModel_BadRequest() throws Exception {
                mockMvc.perform(put("/move/group/" + UUID.randomUUID() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(moveBody(null)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testMoveInvalidForeignId_BadRequest() throws Exception {
                mockMvc.perform(put("/move/folder/not-a-uuid.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(moveBody(null)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testMoveMissingFolderParentIdKey_BadRequest() throws Exception {
                Folder moved = createFolderFor("Moved", testUser, null, Permission.OWNER);

                mockMvc.perform(put("/move/folder/" + moved.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testMoveDestinationNotInUserTree_BadRequest() throws Exception {
                Folder moved = createFolderFor("Moved", testUser, null, Permission.OWNER);

                mockMvc.perform(put("/move/folder/" + moved.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(moveBody(UUID.randomUUID().toString())))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testMoveFolderIntoItself_BadRequest() throws Exception {
                Folder moved = createFolderFor("Moved", testUser, null, Permission.OWNER);

                mockMvc.perform(put("/move/folder/" + moved.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(moveBody(moved.getId())))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testMoveFolderIntoOwnDescendant_BadRequest() throws Exception {
                Folder grandParent = createFolderFor("A", testUser, null, Permission.OWNER);
                Folder child = createFolderFor("B", testUser, grandParent.getId(), Permission.OWNER);

                // A -> B would make A a descendant of itself
                mockMvc.perform(put("/move/folder/" + grandParent.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(moveBody(child.getId())))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));

                // Tree unchanged
                FoldersRelation rel = foldersRelationRepository
                                .findByUserIdAndForeignId(testUser.getId(), grandParent.getId()).orElseThrow();
                assertThat(rel.getFolderParentId()).isNull();
        }

        @Test
        void testMoveOutOfSharedFolderWithoutPermission_BadRequest() throws Exception {
                // Shared parent (two users see it) where testUser only has READ
                Folder sharedParent = createFolderFor("SharedParent", otherUser, null, Permission.OWNER);
                addRelation(FoldersRelation.FOREIGN_MODEL_FOLDER, sharedParent.getId(), testUser.getId(), null);
                Permission readPerm = new Permission();
                readPerm.setAco(FolderService.FOLDER_ACO);
                readPerm.setAcoForeignKey(sharedParent.getId());
                readPerm.setAro(Permission.USER_ARO);
                readPerm.setAroForeignKey(testUser.getId());
                readPerm.setType(Permission.READ);
                permissionRepository.save(readPerm);

                Folder moved = createFolderFor("Moved", testUser, sharedParent.getId(), Permission.OWNER);

                mockMvc.perform(put("/move/folder/" + moved.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(moveBody(null)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));

                // Still under the shared parent
                FoldersRelation rel = foldersRelationRepository
                                .findByUserIdAndForeignId(testUser.getId(), moved.getId()).orElseThrow();
                assertThat(rel.getFolderParentId()).isEqualTo(sharedParent.getId());
        }

        // ---------------------------------------------------------------
        // Not found (404)
        // ---------------------------------------------------------------

        @Test
        void testMoveItemNotInUserTree_NotFound() throws Exception {
                mockMvc.perform(put("/move/folder/" + UUID.randomUUID() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(moveBody(null)))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }
}
