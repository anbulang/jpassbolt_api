package com.jpassbolt.api.controller;

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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for UsersController.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin@example.com", roles = { "USER" })
class UsersControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    private User adminUser;
    private User regularUser;

    @BeforeEach
    void setUp() {
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();

        adminUser = new User();
        adminUser.setUsername("admin@example.com");
        adminUser.setRoleId("admin");
        adminUser.setActive(true);
        adminUser.setDeleted(false);
        userRepository.save(adminUser);

        regularUser = new User();
        regularUser.setUsername("alice@example.com");
        regularUser.setRoleId("user");
        regularUser.setActive(true);
        regularUser.setDeleted(false);
        userRepository.save(regularUser);
    }

    @Test
    void testGetAllUsers_ReturnsActiveUsers() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body").isArray())
                .andExpect(jsonPath("$.body.length()").value(2));
    }

    @Test
    void testGetAllUsers_ExcludesDeletedUsers() throws Exception {
        User deletedUser = new User();
        deletedUser.setUsername("deleted@example.com");
        deletedUser.setRoleId("user");
        deletedUser.setActive(true);
        deletedUser.setDeleted(true);
        userRepository.save(deletedUser);

        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.length()").value(2)); // Only admin + regular
    }

    @Test
    void testGetUser_Found() throws Exception {
        mockMvc.perform(get("/users/" + regularUser.getId() + ".json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.username").value("alice@example.com"));
    }

    @Test
    void testGetUser_NotFound() throws Exception {
        mockMvc.perform(get("/users/00000000-0000-0000-0000-000000000000.json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.status").value("error"));
    }
}
