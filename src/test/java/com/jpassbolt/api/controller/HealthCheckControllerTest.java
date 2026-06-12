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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the healthcheck endpoints:
 * GET /healthcheck/status.json (public) and GET /healthcheck.json (admin only).
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class HealthCheckControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private UserRepository userRepository;

        @Autowired
        private RoleRepository roleRepository;

        // Extra repositories required for FK-safe cleanup: resources/secrets/
        // permissions reference users via @ManyToOne (H2 create-drop generates
        // the FK constraints), so leftovers from other test classes must go first.
        @Autowired
        private PermissionRepository permissionRepository;

        @Autowired
        private SecretRepository secretRepository;

        @Autowired
        private ResourceRepository resourceRepository;

        private Role adminRole;
        private Role userRole;

        @BeforeEach
        void setUp() {
                permissionRepository.deleteAll();
                secretRepository.deleteAll();
                resourceRepository.deleteAll();
                userRepository.deleteAll();
                roleRepository.deleteAll();

                userRole = new Role();
                userRole.setName(Role.USER);
                userRole.setDescription("Logged in user");
                roleRepository.save(userRole);

                adminRole = new Role();
                adminRole.setName(Role.ADMIN);
                adminRole.setDescription("Organization administrator");
                roleRepository.save(adminRole);
        }

        private User createUser(String username, String roleId) {
                User user = new User();
                user.setUsername(username);
                user.setRoleId(roleId);
                user.setActive(true);
                user.setDeleted(false);
                return userRepository.save(user);
        }

        /**
         * The status endpoint must be fully public: method-level
         * {@code @WithAnonymousUser} overrides the class-level mock user so
         * the SecurityConfig permitAll rule is actually exercised.
         * Body must be the bare string "OK" (not an object) and header.message
         * must be "OK" (not the standard success message).
         */
        @Test
        @WithAnonymousUser
        void testStatusPublicAccess() throws Exception {
                mockMvc.perform(get("/healthcheck/status.json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.header.message").value("OK"))
                                .andExpect(jsonPath("$.body").value("OK"));
        }

        @Test
        @WithAnonymousUser
        void testStatusWithoutJsonExtension() throws Exception {
                mockMvc.perform(get("/healthcheck/status"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body").value("OK"));
        }

        /**
         * PHP routes declare setMethods(['GET','HEAD']) for /healthcheck/status:
         * monitoring probes often send HEAD. Spring MVC routes HEAD to the GET
         * handler automatically — this guards against that being broken.
         */
        @Test
        @WithAnonymousUser
        void testStatusHeadRequest() throws Exception {
                mockMvc.perform(head("/healthcheck/status.json"))
                                .andExpect(status().isOk());
        }

        @Test
        void testHealthcheckIndexAsAdmin() throws Exception {
                createUser("test@example.com", adminRole.getId());

                mockMvc.perform(get("/healthcheck.json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                // All eight required top-level domains (camelCase keys).
                                .andExpect(jsonPath("$.body.environment").exists())
                                .andExpect(jsonPath("$.body.configFile").exists())
                                .andExpect(jsonPath("$.body.core").exists())
                                .andExpect(jsonPath("$.body.ssl").exists())
                                .andExpect(jsonPath("$.body.smtpSettings").exists())
                                .andExpect(jsonPath("$.body.gpg").exists())
                                // "application" is singular (schema + PHP DOMAIN_APPLICATION);
                                // the spec example's "applications" is a known typo.
                                .andExpect(jsonPath("$.body.application").exists())
                                .andExpect(jsonPath("$.body.applications").doesNotExist())
                                .andExpect(jsonPath("$.body.database").exists())
                                // jwt domain is excluded (PHP getDomainsIgnore()).
                                .andExpect(jsonPath("$.body.jwt").doesNotExist())
                                // Real check values against the live H2 test database / GPG keys.
                                .andExpect(jsonPath("$.body.database.connect").value(true))
                                .andExpect(jsonPath("$.body.database.defaultContent").value(true))
                                .andExpect(jsonPath("$.body.database.info.tablesCount").value(greaterThan(0)))
                                .andExpect(jsonPath("$.body.gpg.canEncrypt").value(true))
                                .andExpect(jsonPath("$.body.gpg.canDecrypt").value(true))
                                .andExpect(jsonPath("$.body.gpg.gpgKeyNotDefault").value(true));
        }

        @Test
        void testHealthcheckIndexAsNonAdmin() throws Exception {
                createUser("test@example.com", userRole.getId());

                mockMvc.perform(get("/healthcheck.json"))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message",
                                                containsString("Access restricted to administrators")));
        }

        /**
         * Unauthenticated access yields 403 (not 401): the OpenAPI contract's
         * only error response for this endpoint is 403, which matches the
         * current Spring Security behaviour (no authenticationEntryPoint).
         */
        @Test
        @WithAnonymousUser
        void testHealthcheckIndexUnauthenticated() throws Exception {
                mockMvc.perform(get("/healthcheck.json"))
                                .andExpect(status().isForbidden());
        }

        /**
         * Principal exists but no matching user row in the database:
         * isCurrentUserAdmin() -> findByUsername misses -> PassboltApiException
         * (NOT_FOUND) handled by GlobalExceptionHandler.
         */
        @Test
        void testHealthcheckIndexUserNotFoundInDb() throws Exception {
                // Intentionally no createUser(...) for test@example.com.
                mockMvc.perform(get("/healthcheck.json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        /**
         * Regression guard: the old non-standard GET /health-check endpoint was
         * removed along with its SecurityConfig permitAll entry. Anonymous
         * requests must no longer succeed (403 once the permitAll entry is
         * dropped; 404 would equally indicate the handler is gone).
         */
        @Test
        @WithAnonymousUser
        void testOldHealthCheckPathRemoved() throws Exception {
                mockMvc.perform(get("/health-check"))
                                .andExpect(status().is4xxClientError());
        }
}

/**
 * Same endpoints with the index endpoint switch turned off
 * (jpassbolt.healthcheck.index-endpoint-enabled=false, mirroring
 * PASSBOLT_PLUGINS_HEALTHCHECK_SECURITY_INDEX_ENDPOINT_ENABLED).
 * The switch is checked before the admin check (PHP beforeFilter order),
 * so even an administrator receives 403. Separate top-level class because
 * the property override requires its own Spring context.
 */
@SpringBootTest(properties = "jpassbolt.healthcheck.index-endpoint-enabled=false")
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class HealthCheckControllerIndexDisabledTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private UserRepository userRepository;

        @Autowired
        private RoleRepository roleRepository;

        @Autowired
        private PermissionRepository permissionRepository;

        @Autowired
        private SecretRepository secretRepository;

        @Autowired
        private ResourceRepository resourceRepository;

        @BeforeEach
        void setUp() {
                permissionRepository.deleteAll();
                secretRepository.deleteAll();
                resourceRepository.deleteAll();
                userRepository.deleteAll();
                roleRepository.deleteAll();

                Role adminRole = new Role();
                adminRole.setName(Role.ADMIN);
                adminRole.setDescription("Organization administrator");
                roleRepository.save(adminRole);

                User adminUser = new User();
                adminUser.setUsername("test@example.com");
                adminUser.setRoleId(adminRole.getId());
                adminUser.setActive(true);
                adminUser.setDeleted(false);
                userRepository.save(adminUser);
        }

        @Test
        void testHealthcheckIndexEndpointDisabled() throws Exception {
                mockMvc.perform(get("/healthcheck.json"))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message",
                                                containsString("Healthcheck security index endpoint disabled")));
        }

        /** The public status endpoint must stay up even when index is disabled. */
        @Test
        @WithAnonymousUser
        void testStatusStillAvailableWhenIndexDisabled() throws Exception {
                mockMvc.perform(get("/healthcheck/status.json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body").value("OK"));
        }
}
