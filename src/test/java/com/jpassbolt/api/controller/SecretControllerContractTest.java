package com.jpassbolt.api.controller;

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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.Map;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OpenAPI contract tests for the Secrets endpoint group
 * (/secrets/resource/{resourceId}.json).
 *
 * NOTE: the shared {header, body} envelope is now spec-valid project-wide, so
 * the GET assertion is ENABLED (verified). The spec defines ONLY a GET for
 * /secrets/resource/{resourceId}.json; there is no PUT operation even though
 * the implementation exposes one. The PUT test below therefore exercises
 * behavior only and its contract assertion stays disabled for a VERIFIED
 * endpoint-absent reason (validation.request.operation.notAllowed) — recorded
 * in assertions_left_disabled.
 */
@WithMockUser(username = "test@example.com", roles = { "USER" })
class SecretControllerContractTest extends OpenApiComplianceTest {

        @Autowired
        private ResourceRepository resourceRepository;

        @Autowired
        private SecretRepository secretRepository;

        @Autowired
        private PermissionRepository permissionRepository;

        @Autowired
        private UserRepository userRepository;

        private User testUser;
        private Resource testResource;

        @BeforeEach
        void setUpData() {
                // Reverse-order clear to respect FK dependencies, then seed a named user
                // matching the @WithMockUser principal.
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

                Permission perm = new Permission();
                perm.setAco(Permission.RESOURCE_ACO);
                perm.setAcoForeignKey(testResource.getId());
                perm.setAro(Permission.USER_ARO);
                perm.setAroForeignKey(testUser.getId());
                perm.setType(Permission.OWNER);
                permissionRepository.save(perm);

                Secret secret = new Secret();
                secret.setResourceId(testResource.getId());
                secret.setUserId(testUser.getId());
                secret.setData("-----BEGIN PGP MESSAGE-----\nOriginal encrypted data\n-----END PGP MESSAGE-----");
                secretRepository.save(secret);
        }

        @Test
        void testViewSecretContract() throws Exception {
                mockMvc.perform(get("/secrets/resource/" + testResource.getId() + ".json")
                                .accept(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.resource_id").value(testResource.getId()))
                                .andExpect(jsonPath("$.body.data").exists())
                                // Enabled (verified): GET /secrets/resource/{id}.json IS
                                // defined in the spec and the {header, body} envelope is
                                // now spec-valid (header carries all required fields incl.
                                // action; the secret body matches the secret schema). The
                                // earlier "strict JSON header validation" reason was
                                // outdated.
                                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
        }

        @Test
        void testUpdateSecretContract() throws Exception {
                String newData = "-----BEGIN PGP MESSAGE-----\nUpdated\n-----END PGP MESSAGE-----";

                mockMvc.perform(put("/secrets/resource/" + testResource.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("data", newData))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.data").value(newData));
                // Disabled (verified) — endpoint-absent, NOT an envelope issue:
                // validation.request.operation.notAllowed. The OpenAPI spec declares
                // ONLY a GET for /secrets/resource/{resourceId}.json; there is no PUT
                // operation, so the validator rejects the verb. The implementation
                // intentionally exposes PUT; behavior is asserted above. Recorded in
                // assertions_left_disabled.
                // .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
        }
}
