package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.ResourceDto;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for ResourceController with permission enforcement.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class ResourceControllerTest {

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
        }

        /**
         * Helper to create a resource with OWNER permission for the test user.
         */
        private Resource createResourceWithPermission(String name, String username, String uri, int permType) {
                Resource resource = new Resource();
                resource.setName(name);
                resource.setUsername(username);
                resource.setUri(uri);
                resource.setCreatedBy(testUser.getId());
                resource.setModifiedBy(testUser.getId());
                resource.setDeleted(false);
                resourceRepository.save(resource);

                Permission perm = new Permission();
                perm.setAco(Permission.RESOURCE_ACO);
                perm.setAcoForeignKey(resource.getId());
                perm.setAro(Permission.USER_ARO);
                perm.setAroForeignKey(testUser.getId());
                perm.setType(permType);
                permissionRepository.save(perm);

                return resource;
        }

        @Test
        void testGetAllResources_ReturnsEmptyList() throws Exception {
                mockMvc.perform(get("/resources"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body").isArray())
                                .andExpect(jsonPath("$.body").isEmpty());
        }

        @Test
        void testGetAllResources_OnlyShowsPermittedResources() throws Exception {
                // Resource with permission
                createResourceWithPermission("Visible", "admin", "https://visible.com", Permission.READ);

                // Resource without permission (created by another user, no permission)
                Resource hidden = new Resource();
                hidden.setName("Hidden");
                hidden.setCreatedBy(testUser.getId());
                hidden.setModifiedBy(testUser.getId());
                hidden.setDeleted(false);
                resourceRepository.save(hidden);

                mockMvc.perform(get("/resources"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body").isArray())
                                .andExpect(jsonPath("$.body.length()").value(1))
                                .andExpect(jsonPath("$.body[0].name").value("Visible"));
        }

        @Test
        void testGetResource_WithPermission() throws Exception {
                Resource resource = createResourceWithPermission("My Password", "user1", "https://mysite.com",
                                Permission.OWNER);

                Secret secret = new Secret();
                secret.setResourceId(resource.getId());
                secret.setUserId(testUser.getId());
                secret.setData("-----BEGIN PGP MESSAGE-----\nEncrypted\n-----END PGP MESSAGE-----");
                secretRepository.save(secret);

                mockMvc.perform(get("/resources/" + resource.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.name").value("My Password"))
                                .andExpect(jsonPath("$.body.secrets").isArray());
        }

        @Test
        void testGetResource_WithoutPermission() throws Exception {
                // Resource with no permission for test user
                Resource resource = new Resource();
                resource.setName("Forbidden");
                resource.setCreatedBy(testUser.getId());
                resource.setModifiedBy(testUser.getId());
                resource.setDeleted(false);
                resourceRepository.save(resource);

                mockMvc.perform(get("/resources/" + resource.getId() + ".json"))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testCreateResource_AutoCreatesOwnerPermission() throws Exception {
                ResourceDto.CreateRequest request = ResourceDto.CreateRequest.builder()
                                .name("New Password")
                                .username("newuser")
                                .uri("https://newsite.com")
                                .description("A new password entry")
                                .secrets(List.of(
                                                ResourceDto.CreateRequest.SecretData.builder()
                                                                .data("-----BEGIN PGP MESSAGE-----\nNew secret\n-----END PGP MESSAGE-----")
                                                                .build()))
                                .build();

                mockMvc.perform(post("/resources")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.body.name").value("New Password"));

                // Verify OWNER permission was auto-created
                List<Resource> resources = resourceRepository.findByDeletedFalse();
                assertThat(resources).hasSize(1);
                assertThat(permissionRepository.userHasAccess(
                                resources.get(0).getId(), testUser.getId(), Permission.OWNER)).isTrue();
        }

        @Test
        void testUpdateResource_WithUpdatePermission() throws Exception {
                Resource resource = createResourceWithPermission("Original", "original", null, Permission.UPDATE);

                ResourceDto.UpdateRequest request = ResourceDto.UpdateRequest.builder()
                                .name("Updated Name")
                                .build();

                mockMvc.perform(put("/resources/" + resource.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.name").value("Updated Name"));
        }

        @Test
        void testDeleteResource_WithOwnerPermission() throws Exception {
                Resource resource = createResourceWithPermission("To Delete", null, null, Permission.OWNER);

                mockMvc.perform(delete("/resources/" + resource.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"));

                Resource deleted = resourceRepository.findById(resource.getId()).orElseThrow();
                assertThat(deleted.getDeleted()).isTrue();
        }

        @Test
        void testDeleteResource_WithReadOnlyPermission_Forbidden() throws Exception {
                Resource resource = createResourceWithPermission("Cannot Delete", null, null, Permission.READ);

                mockMvc.perform(delete("/resources/" + resource.getId() + ".json"))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }
}
