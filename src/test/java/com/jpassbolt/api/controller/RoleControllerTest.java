package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for RoleController (read-only roles index).
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class RoleControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private RoleRepository roleRepository;

        @Autowired
        private UserRepository userRepository;

        @Autowired
        private PermissionRepository permissionRepository;

        @Autowired
        private SecretRepository secretRepository;

        @Autowired
        private ResourceRepository resourceRepository;

        @BeforeEach
        void setUp() {
                // Clear in reverse FK-dependency order. Permissions/secrets/resources
                // reference users (leftovers from other test classes sharing the same
                // H2 database would otherwise block userRepository.deleteAll()),
                // and users.role_id references roles.
                permissionRepository.deleteAll();
                secretRepository.deleteAll();
                resourceRepository.deleteAll();
                userRepository.deleteAll();
                roleRepository.deleteAll();

                // Seed the three official roles (root was removed in Passbolt v4.1.0)
                Role userRole = createRole("user", "Logged in user");
                createRole("admin", "Organization administrator");
                createRole("guest", "Non logged in user");

                // Seed a user matching @WithMockUser, consistent with other tests
                User testUser = new User();
                testUser.setUsername("test@example.com");
                testUser.setRoleId(userRole.getId());
                testUser.setActive(true);
                testUser.setDeleted(false);
                userRepository.save(testUser);
        }

        private Role createRole(String name, String description) {
                Role role = new Role();
                role.setName(name);
                role.setDescription(description);
                return roleRepository.save(role);
        }

        @Test
        void testGetRolesReturnsAllRoles() throws Exception {
                mockMvc.perform(get("/roles.json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.header.code").value(200))
                                .andExpect(jsonPath("$.body").isArray())
                                .andExpect(jsonPath("$.body.length()").value(3))
                                .andExpect(jsonPath("$.body[?(@.name=='admin')].description")
                                                .value(hasItem("Organization administrator")))
                                .andExpect(jsonPath("$.body[?(@.name=='user')].description")
                                                .value(hasItem("Logged in user")))
                                .andExpect(jsonPath("$.body[?(@.name=='guest')].description")
                                                .value(hasItem("Non logged in user")));
        }

        @Test
        void testGetRolesWithoutJsonExtension() throws Exception {
                // Verifies the @GetMapping({"", ".json"}) double registration
                mockMvc.perform(get("/roles"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body").isArray())
                                .andExpect(jsonPath("$.body.length()").value(3));
        }

        @Test
        void testGetRolesEmptyTable() throws Exception {
                // PHP find('all') semantics: an empty table is still a success
                // envelope with an empty array, never a 404.
                userRepository.deleteAll();
                roleRepository.deleteAll();

                mockMvc.perform(get("/roles.json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body").isArray())
                                .andExpect(jsonPath("$.body").isEmpty());
        }

        @Test
        void testGetRolesResponseFieldsAreLowercaseWords() throws Exception {
                // Guards against accidental @JsonProperty snake_case drift:
                // all five keys are single lowercase words per the OpenAPI role schema.
                mockMvc.perform(get("/roles.json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body[0].id").exists())
                                .andExpect(jsonPath("$.body[0].name").exists())
                                .andExpect(jsonPath("$.body[0].description").exists())
                                .andExpect(jsonPath("$.body[0].created").exists())
                                .andExpect(jsonPath("$.body[0].modified").exists());
        }

        @Test
        @WithAnonymousUser
        void testGetRolesUnauthenticated() throws Exception {
                // OpenAPI contract specifies 401 authenticationRequired, but
                // SecurityConfig has no authenticationEntryPoint configured, so
                // Spring Security's default Http403ForbiddenEntryPoint returns 403.
                // This matches every protected endpoint in the project (see the
                // isForbidden() convention in ResourceControllerTest); the global
                // unauthenticated requests now return 401 (SecurityConfig authenticationEntryPoint).
                mockMvc.perform(get("/roles.json"))
                                .andExpect(status().isUnauthorized());
        }
}
