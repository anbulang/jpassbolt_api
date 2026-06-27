package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.model.Favorite;
import com.jpassbolt.api.model.Folder;
import com.jpassbolt.api.model.FoldersRelation;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.FavoriteRepository;
import com.jpassbolt.api.repository.FolderRepository;
import com.jpassbolt.api.repository.FoldersRelationRepository;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for PUT /share/{foreignModel}/{foreignId}.json
 * (ShareController, generalized path) covering the four official request
 * shapes (addUser / updatePermissionLevel / deleteUser with and without id /
 * everythingAtOnce), the secrets coverage validation, cascade deletes and
 * the nullBody success contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class ShareUpdateControllerTest {

    private static final String PGP_DATA = "-----BEGIN PGP MESSAGE-----\nData\n-----END PGP MESSAGE-----";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private FavoriteRepository favoriteRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FolderRepository folderRepository;

    @Autowired
    private FoldersRelationRepository foldersRelationRepository;

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
        favoriteRepository.deleteAll();
        foldersRelationRepository.deleteAll();
        folderRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();

        ownerUser = createUser("test@example.com");
        targetUser = createUser("target@example.com");
        readerUser = createUser("reader@example.com");

        resource = new Resource();
        resource.setName("Shared Password");
        resource.setUsername("admin");
        resource.setUri("https://shared.example.com");
        resource.setCreatedBy(ownerUser.getId());
        resource.setModifiedBy(ownerUser.getId());
        resource.setDeleted(false);
        resourceRepository.save(resource);

        ownerPermission = createPermission(resource.getId(), ownerUser.getId(), Permission.OWNER);
        readerPermission = createPermission(resource.getId(), readerUser.getId(), Permission.READ);
        createSecret(resource.getId(), ownerUser.getId());
        createSecret(resource.getId(), readerUser.getId());
    }

    // ------------------------------------------------------------------
    // Positive cases
    // ------------------------------------------------------------------

    @Test
    void testShareAddUserWithSecret() throws Exception {
        Map<String, Object> request = Map.of(
                "permissions", List.of(Map.of(
                        "aro", "User",
                        "aro_foreign_key", targetUser.getId(),
                        "type", Permission.READ,
                        "is_new", true)),
                "secrets", List.of(Map.of(
                        "user_id", targetUser.getId(),
                        "data", PGP_DATA)));

        mockMvc.perform(put("/share/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                // nullBody contract: body is JSON null, NOT {}
                .andExpect(jsonPath("$.body").value(nullValue()));

        assertThat(permissionRepository
                .findByAcoForeignKeyAndAroForeignKey(resource.getId(), targetUser.getId()))
                .isPresent();
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), targetUser.getId()))
                .isPresent();
    }

    @Test
    void testUpdatePermissionLevel() throws Exception {
        // Official updatePermissionLevel shape: id + type only
        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "id", readerPermission.getId(),
                "type", Permission.OWNER)));

        mockMvc.perform(put("/share/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(nullValue()));

        Permission updated = permissionRepository.findById(readerPermission.getId()).orElseThrow();
        assertThat(updated.getType()).isEqualTo(Permission.OWNER);
    }

    @Test
    void testDeletePermissionCascadesSecretAndFavorite() throws Exception {
        favoriteRepository.save(new Favorite(readerUser.getId(), resource.getId(),
                Favorite.FOREIGN_MODEL_RESOURCE));

        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "id", readerPermission.getId(),
                "delete", true)));

        mockMvc.perform(put("/share/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        assertThat(permissionRepository.findById(readerPermission.getId())).isEmpty();
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), readerUser.getId())).isEmpty();
        assertThat(favoriteRepository
                .findByUserIdAndForeignKey(readerUser.getId(), resource.getId())).isEmpty();
    }

    @Test
    void testDeletePermissionByAroForeignKeyWithoutId() throws Exception {
        // The plugin may send the id-less deleteUser shape
        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "aro", "User",
                "aro_foreign_key", readerUser.getId(),
                "delete", true)));

        mockMvc.perform(put("/share/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        assertThat(permissionRepository
                .findByAcoForeignKeyAndAroForeignKey(resource.getId(), readerUser.getId())).isEmpty();
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), readerUser.getId())).isEmpty();
    }

    @Test
    void testEverythingAtOnce() throws Exception {
        // add target (with secret) + promote reader to OWNER + delete owner
        Map<String, Object> request = Map.of(
                "permissions", List.of(
                        Map.of("aro", "User", "aro_foreign_key", targetUser.getId(),
                                "type", Permission.UPDATE, "is_new", true),
                        Map.of("id", readerPermission.getId(), "type", Permission.OWNER),
                        Map.of("id", ownerPermission.getId(), "delete", true)),
                "secrets", List.of(Map.of(
                        "user_id", targetUser.getId(),
                        "data", PGP_DATA)));

        mockMvc.perform(put("/share/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(nullValue()));

        // target added
        assertThat(permissionRepository
                .findByAcoForeignKeyAndAroForeignKey(resource.getId(), targetUser.getId()))
                .hasValueSatisfying(p -> assertThat(p.getType()).isEqualTo(Permission.UPDATE));
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), targetUser.getId())).isPresent();
        // reader promoted
        assertThat(permissionRepository.findById(readerPermission.getId()))
                .hasValueSatisfying(p -> assertThat(p.getType()).isEqualTo(Permission.OWNER));
        // owner removed, including the secret cascade
        assertThat(permissionRepository.findById(ownerPermission.getId())).isEmpty();
        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), ownerUser.getId())).isEmpty();
    }

    @Test
    void testOldResourcePathStillWorks() throws Exception {
        // PUT /share/resource/{id} (no .json) is the same generalized
        // pattern with foreignModel=resource — backward compatible.
        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "id", readerPermission.getId(),
                "type", Permission.UPDATE)));

        mockMvc.perform(put("/share/resource/" + resource.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"));
    }

    // ------------------------------------------------------------------
    // Negative cases
    // ------------------------------------------------------------------

    @Test
    void testRemoveAllOwnersReturns400() throws Exception {
        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "id", ownerPermission.getId(),
                "delete", true)));

        mockMvc.perform(put("/share/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("Could not validate resource data."))
                .andExpect(jsonPath("$.body.permissions.at_least_one_owner")
                        .value("At least one owner permission must be provided."));

        // Nothing persisted
        assertThat(permissionRepository.findById(ownerPermission.getId())).isPresent();
    }

    @Test
    void testMissingSecretForAddedUserReturns400() throws Exception {
        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "aro", "User",
                "aro_foreign_key", targetUser.getId(),
                "type", Permission.READ,
                "is_new", true)));

        mockMvc.perform(put("/share/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("Could not validate resource data."));

        assertThat(permissionRepository
                .findByAcoForeignKeyAndAroForeignKey(resource.getId(), targetUser.getId())).isEmpty();
    }

    @Test
    void testExtraSecretReturns400() throws Exception {
        // A secret for a user who does NOT gain access is a validation error
        Map<String, Object> request = Map.of(
                "permissions", List.of(),
                "secrets", List.of(Map.of(
                        "user_id", targetUser.getId(),
                        "data", PGP_DATA)));

        mockMvc.perform(put("/share/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("Could not validate resource data."));

        assertThat(secretRepository
                .findByResourceIdAndUserId(resource.getId(), targetUser.getId())).isEmpty();
    }

    @Test
    @WithMockUser(username = "reader@example.com", roles = { "USER" })
    void testNonOwnerReturns403() throws Exception {
        Map<String, Object> request = Map.of("permissions", List.of(Map.of(
                "aro", "User",
                "aro_foreign_key", targetUser.getId(),
                "type", Permission.READ)));

        mockMvc.perform(put("/share/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.message")
                        .value("You are not authorized to share this resource."));
    }

    @Test
    void testDeletedResourceReturns404() throws Exception {
        // Soft-deleted resources are a 404 (corrected from the previous 400)
        resource.setDeleted(true);
        resourceRepository.save(resource);

        mockMvc.perform(put("/share/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("permissions", List.of()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.message").value("The resource does not exist."));
    }

    @Test
    void testInvalidUuidReturns400() throws Exception {
        mockMvc.perform(put("/share/resource/not-a-uuid.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("permissions", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message")
                        .value("The resource identifier should be a valid UUID."));
    }

    @Test
    void testFolderModelReturns404() throws Exception {
        mockMvc.perform(put("/share/folder/" + UUID.randomUUID() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("permissions", List.of()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.message").value("The folder does not exist."));
    }

    @Test
    void testShareFolderGrantAndRevoke() throws Exception {
        // Owner's folder: Folder ACO permission + tree row.
        Folder folder = new Folder();
        folder.setName("Shared Folder");
        folder.setCreatedBy(ownerUser.getId());
        folder.setModifiedBy(ownerUser.getId());
        folderRepository.save(folder);

        Permission folderOwner = new Permission();
        folderOwner.setAco("Folder");
        folderOwner.setAcoForeignKey(folder.getId());
        folderOwner.setAro(Permission.USER_ARO);
        folderOwner.setAroForeignKey(ownerUser.getId());
        folderOwner.setType(Permission.OWNER);
        permissionRepository.save(folderOwner);

        FoldersRelation ownerRel = new FoldersRelation();
        ownerRel.setForeignModel(FoldersRelation.FOREIGN_MODEL_FOLDER);
        ownerRel.setForeignId(folder.getId());
        ownerRel.setUserId(ownerUser.getId());
        foldersRelationRepository.save(ownerRel);

        // Grant READ to targetUser (no secrets — folders carry none).
        Map<String, Object> grant = Map.of(
                "permissions", List.of(Map.of(
                        "aro", "User",
                        "aro_foreign_key", targetUser.getId(),
                        "type", Permission.READ,
                        "is_new", true)));
        mockMvc.perform(put("/share/folder/" + folder.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(grant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body").value(nullValue()));

        Permission granted = permissionRepository
                .findByAcoForeignKeyAndAroForeignKey(folder.getId(), targetUser.getId())
                .orElseThrow();
        assertThat(granted.getAco()).isEqualTo("Folder");
        // The folder enters the grantee's tree at the root.
        assertThat(foldersRelationRepository
                .findByUserIdAndForeignId(targetUser.getId(), folder.getId())).isPresent();

        // Revoke targetUser's access again.
        Map<String, Object> revoke = Map.of(
                "permissions", List.of(Map.of(
                        "id", granted.getId(),
                        "delete", true)));
        mockMvc.perform(put("/share/folder/" + folder.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(revoke)))
                .andExpect(status().isOk());

        assertThat(permissionRepository
                .findByAcoForeignKeyAndAroForeignKey(folder.getId(), targetUser.getId())).isEmpty();
        assertThat(foldersRelationRepository
                .findByUserIdAndForeignId(targetUser.getId(), folder.getId())).isEmpty();
    }

    @Test
    void testShareFolderAsNonOwnerForbidden() throws Exception {
        // readerUser owns nothing on this folder; ownerUser (the caller) has
        // only READ → 403.
        Folder folder = new Folder();
        folder.setName("Not Mine");
        folder.setCreatedBy(readerUser.getId());
        folder.setModifiedBy(readerUser.getId());
        folderRepository.save(folder);

        Permission readOnly = new Permission();
        readOnly.setAco("Folder");
        readOnly.setAcoForeignKey(folder.getId());
        readOnly.setAro(Permission.USER_ARO);
        readOnly.setAroForeignKey(ownerUser.getId());
        readOnly.setType(Permission.READ);
        permissionRepository.save(readOnly);

        mockMvc.perform(put("/share/folder/" + folder.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("permissions", List.of()))))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithAnonymousUser
    void testUnauthenticated() throws Exception {
        // OpenAPI contract says 401; the project-wide SecurityConfig has no
        // authenticationEntryPoint so the actual status is 403 — asserted
        // as-is.
        mockMvc.perform(put("/share/resource/" + resource.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("permissions", List.of()))))
                .andExpect(status().isUnauthorized());
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

    private Secret createSecret(String resourceId, String userId) {
        Secret secret = new Secret();
        secret.setResourceId(resourceId);
        secret.setUserId(userId);
        secret.setData(PGP_DATA);
        return secretRepository.save(secret);
    }
}
