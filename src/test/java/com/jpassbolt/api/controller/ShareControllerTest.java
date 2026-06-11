package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.model.User;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for ShareController.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "owner@example.com", roles = { "USER" })
class ShareControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private User ownerUser;
    private User targetUser;
    private Resource testResource;

    @BeforeEach
    void setUp() {
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();

        // Create owner user
        ownerUser = new User();
        ownerUser.setUsername("owner@example.com");
        ownerUser.setRoleId("user");
        ownerUser.setActive(true);
        ownerUser.setDeleted(false);
        userRepository.save(ownerUser);

        // Create target user to share with
        targetUser = new User();
        targetUser.setUsername("reader@example.com");
        targetUser.setRoleId("user");
        targetUser.setActive(true);
        targetUser.setDeleted(false);
        userRepository.save(targetUser);

        // Create test resource
        testResource = new Resource();
        testResource.setName("Shared Password");
        testResource.setUsername("admin");
        testResource.setUri("https://shared.example.com");
        testResource.setCreatedBy(ownerUser.getId());
        testResource.setModifiedBy(ownerUser.getId());
        testResource.setDeleted(false);
        resourceRepository.save(testResource);

        // Create owner secret
        Secret ownerSecret = new Secret();
        ownerSecret.setResourceId(testResource.getId());
        ownerSecret.setUserId(ownerUser.getId());
        ownerSecret.setData("-----BEGIN PGP MESSAGE-----\nOwner secret\n-----END PGP MESSAGE-----");
        secretRepository.save(ownerSecret);

        // Create OWNER permission for ownerUser
        Permission ownerPerm = new Permission();
        ownerPerm.setAco(Permission.RESOURCE_ACO);
        ownerPerm.setAcoForeignKey(testResource.getId());
        ownerPerm.setAro(Permission.USER_ARO);
        ownerPerm.setAroForeignKey(ownerUser.getId());
        ownerPerm.setType(Permission.OWNER);
        permissionRepository.save(ownerPerm);
    }

    @Test
    void testGetPermissions_ReturnsOwnerPermission() throws Exception {
        mockMvc.perform(get("/share/resource/" + testResource.getId() + "/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body").isArray())
                .andExpect(jsonPath("$.body[0].type").value(Permission.OWNER))
                .andExpect(jsonPath("$.body[0].aro_foreign_key").value(ownerUser.getId()));
    }

    @Test
    void testShareResource_GrantRead() throws Exception {
        Map<String, Object> request = Map.of(
                "permissions", List.of(
                        Map.of(
                                "aro", Permission.USER_ARO,
                                "aro_foreign_key", targetUser.getId(),
                                "type", Permission.READ)),
                "secrets", List.of(
                        Map.of(
                                "user_id", targetUser.getId(),
                                "data", "-----BEGIN PGP MESSAGE-----\nTarget secret\n-----END PGP MESSAGE-----")));

        mockMvc.perform(put("/share/resource/" + testResource.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"));

        // Verify permission was created
        List<Permission> permissions = permissionRepository.findByResourceId(testResource.getId());
        assertThat(permissions).hasSize(2);

        // Verify secret was created for target user
        assertThat(secretRepository.findByResourceIdAndUserId(
                testResource.getId(), targetUser.getId())).isPresent();
    }

    @Test
    void testShareResource_UpdatePermissionType() throws Exception {
        // First grant READ
        Permission readPerm = new Permission();
        readPerm.setAco(Permission.RESOURCE_ACO);
        readPerm.setAcoForeignKey(testResource.getId());
        readPerm.setAro(Permission.USER_ARO);
        readPerm.setAroForeignKey(targetUser.getId());
        readPerm.setType(Permission.READ);
        permissionRepository.save(readPerm);

        // Now update to UPDATE
        Map<String, Object> request = Map.of(
                "permissions", List.of(
                        Map.of(
                                "aro", Permission.USER_ARO,
                                "aro_foreign_key", targetUser.getId(),
                                "type", Permission.UPDATE)));

        mockMvc.perform(put("/share/resource/" + testResource.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"));

        // Verify permission was updated
        Permission updated = permissionRepository
                .findByAcoForeignKeyAndAroForeignKey(testResource.getId(), targetUser.getId())
                .orElseThrow();
        assertThat(updated.getType()).isEqualTo(Permission.UPDATE);
    }

    @Test
    void testShareResource_RevokeAccess() throws Exception {
        // First grant READ permission
        Permission readPerm = new Permission();
        readPerm.setAco(Permission.RESOURCE_ACO);
        readPerm.setAcoForeignKey(testResource.getId());
        readPerm.setAro(Permission.USER_ARO);
        readPerm.setAroForeignKey(targetUser.getId());
        readPerm.setType(Permission.READ);
        permissionRepository.save(readPerm);

        // Create target user secret
        Secret targetSecret = new Secret();
        targetSecret.setResourceId(testResource.getId());
        targetSecret.setUserId(targetUser.getId());
        targetSecret.setData("-----BEGIN PGP MESSAGE-----\nTarget secret\n-----END PGP MESSAGE-----");
        secretRepository.save(targetSecret);

        // Revoke access
        Map<String, Object> request = Map.of(
                "permissions", List.of(
                        Map.of(
                                "aro_foreign_key", targetUser.getId(),
                                "delete", true)));

        mockMvc.perform(put("/share/resource/" + testResource.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"));

        // Verify permission was removed
        assertThat(permissionRepository.findByAcoForeignKeyAndAroForeignKey(
                testResource.getId(), targetUser.getId())).isEmpty();
        // Verify secret was removed
        assertThat(secretRepository.findByResourceIdAndUserId(
                testResource.getId(), targetUser.getId())).isEmpty();
    }

    @Test
    void testSimulateShare_ShowsAddedUsers() throws Exception {
        Map<String, Object> request = Map.of(
                "permissions", List.of(
                        Map.of(
                                "aro_foreign_key", targetUser.getId(),
                                "type", Permission.READ)));

        mockMvc.perform(post("/share/simulate/" + testResource.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.changes.added").isArray())
                .andExpect(jsonPath("$.body.changes.added[0].User.id").value(targetUser.getId()))
                .andExpect(jsonPath("$.body.changes.removed").isEmpty());
    }

    @Test
    @WithMockUser(username = "reader@example.com", roles = { "USER" })
    void testShareResource_Unauthorized() throws Exception {
        // Target user (reader) tries to share - should fail (not owner)
        Map<String, Object> request = Map.of(
                "permissions", List.of(
                        Map.of(
                                "aro_foreign_key", ownerUser.getId(),
                                "type", Permission.READ)));

        mockMvc.perform(put("/share/resource/" + testResource.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.status").value("error"));
    }
}
