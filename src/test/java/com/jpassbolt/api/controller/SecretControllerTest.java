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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for SecretController with permission enforcement.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class SecretControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private User testUser;
    private Resource testResource;
    private Secret testSecret;

    @BeforeEach
    void setUp() {
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();

        testUser = new User();
        testUser.setUsername("test@example.com");
        testUser.setRoleId("user");
        testUser.setActive(true);
        testUser.setDeleted(false);
        userRepository.save(testUser);

        testResource = new Resource();
        testResource.setName("Test Password");
        testResource.setUsername("admin");
        testResource.setUri("https://example.com");
        testResource.setCreatedBy(testUser.getId());
        testResource.setModifiedBy(testUser.getId());
        testResource.setDeleted(false);
        resourceRepository.save(testResource);

        // Grant OWNER permission
        Permission perm = new Permission();
        perm.setAco(Permission.RESOURCE_ACO);
        perm.setAcoForeignKey(testResource.getId());
        perm.setAro(Permission.USER_ARO);
        perm.setAroForeignKey(testUser.getId());
        perm.setType(Permission.OWNER);
        permissionRepository.save(perm);

        testSecret = new Secret();
        testSecret.setResourceId(testResource.getId());
        testSecret.setUserId(testUser.getId());
        testSecret.setData("-----BEGIN PGP MESSAGE-----\nOriginal encrypted data\n-----END PGP MESSAGE-----");
        secretRepository.save(testSecret);
    }

    @Test
    void testGetSecret_WithPermission() throws Exception {
        mockMvc.perform(get("/secrets/resource/" + testResource.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.resource_id").value(testResource.getId()))
                .andExpect(jsonPath("$.body.data").exists());
    }

    @Test
    void testGetSecret_WithoutPermission() throws Exception {
        // Remove permission
        permissionRepository.deleteAll();

        mockMvc.perform(get("/secrets/resource/" + testResource.getId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testGetSecret_NotFound() throws Exception {
        mockMvc.perform(get("/secrets/resource/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isForbidden()); // No permission = 403
    }

    @Test
    void testUpdateSecret_WithPermission() throws Exception {
        String newData = "-----BEGIN PGP MESSAGE-----\nUpdated\n-----END PGP MESSAGE-----";

        mockMvc.perform(put("/secrets/resource/" + testResource.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("data", newData))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.data").value(newData));

        Secret updated = secretRepository.findByResourceIdAndUserId(
                testResource.getId(), testUser.getId()).orElseThrow();
        assertThat(updated.getData()).isEqualTo(newData);
    }

    @Test
    void testUpdateSecret_ReadOnly_Forbidden() throws Exception {
        // Downgrade to READ
        Permission perm = permissionRepository.findByAcoForeignKeyAndAroForeignKey(
                testResource.getId(), testUser.getId()).orElseThrow();
        perm.setType(Permission.READ);
        permissionRepository.save(perm);

        mockMvc.perform(put("/secrets/resource/" + testResource.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("data", "new data"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void testUpdateSecret_MissingData() throws Exception {
        mockMvc.perform(put("/secrets/resource/" + testResource.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
