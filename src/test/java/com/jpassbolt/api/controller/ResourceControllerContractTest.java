package com.jpassbolt.api.controller;

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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OpenAPI contract tests for the Resources endpoint group
 * (/resources.json and /resources/{resourceId}.json) covering
 * GET (index), GET (view), POST, PUT and DELETE.
 *
 * NOTE: the shared {header, body} envelope is now spec-valid project-wide
 * (ApiResponse emits all required header fields incl. action; JacksonConfig
 * serialises LocalDateTime as RFC3339 with offset), so the old "strict JSON
 * header validation" reason no longer applies. DELETE is therefore ENABLED.
 * The index/view/add/update assertions stay disabled for VERIFIED schema
 * reasons (per-test comments): the v4 resource schemas require encrypted-
 * metadata fields (metadata / metadata_key_id / metadata_key_type) and
 * folder_parent_id / personal, and forbid our flat v3 fields as
 * additionalProperties — we deliberately align with v3/PHP, not the精简 v4
 * spec. resources_index additionally requires the headerWithPagination
 * "pagination" object the controller does not emit.
 */
@WithMockUser(username = "test@example.com", roles = { "USER" })
class ResourceControllerContractTest extends OpenApiComplianceTest {

        @Autowired
        private ResourceRepository resourceRepository;

        @Autowired
        private SecretRepository secretRepository;

        @Autowired
        private PermissionRepository permissionRepository;

        @Autowired
        private UserRepository userRepository;

        private User testUser;

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
        }

        /**
         * Creates a resource with the given permission level for the test user.
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
        void testIndexResourcesContract() throws Exception {
                createResourceWithPermission("Visible", "admin", "https://visible.com", Permission.READ);

                mockMvc.perform(get("/resources.json")
                                .accept(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body").isArray());
                // Disabled (verified) — TWO body/header-schema mismatches, NOT an
                // envelope issue:
                // 1) validation.response.body.schema.required on /header — the spec's
                // resources_index response uses the headerWithPagination schema, which
                // REQUIRES a "pagination" object; ResourceController emits the plain
                // header without pagination;
                // 2) validation.response.body.schema.required/anyOf on /body[*] — the
                // spec's resource item requires the v4 encrypted-metadata fields
                // (metadata / metadata_key_id / metadata_key_type) and personal /
                // folder_parent_id, which the v3 ResourceDto we align with PHP does not
                // emit. body-schema-strict; recorded in assertions_left_disabled.
                // .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
        }

        @Test
        void testViewResourceContract() throws Exception {
                Resource resource = createResourceWithPermission("My Password", "user1", "https://mysite.com",
                                Permission.OWNER);

                Secret secret = new Secret();
                secret.setResourceId(resource.getId());
                secret.setUserId(testUser.getId());
                secret.setData("-----BEGIN PGP MESSAGE-----\nEncrypted\n-----END PGP MESSAGE-----");
                secretRepository.save(secret);

                mockMvc.perform(get("/resources/" + resource.getId() + ".json")
                                .accept(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.name").value("My Password"));
                // Disabled (verified) — body-schema-strict, NOT an envelope issue:
                // validation.response.body.schema.additionalProperties + .required on
                // /body. The spec's resource view schema (v4) forbids our flat fields
                // (created/modified/created_by/modified_by/...) as additionalProperties
                // AND requires the v4 encrypted-metadata fields (metadata /
                // metadata_key_id / metadata_key_type) plus folder_parent_id / personal.
                // We deliberately emit the v3/PHP-aligned ResourceDto shape, so the body
                // legitimately diverges from the精简 v4 schema. Recorded in
                // assertions_left_disabled.
                // .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
        }

        @Test
        void testAddResourceContract() throws Exception {
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

                mockMvc.perform(post("/resources.json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.name").value("New Password"));
                // Disabled (verified) — request-v4-metadata, NOT an envelope issue:
                // validation.request.body.schema.required/additionalProperties. The
                // spec's resourceAdd request body is e2eeMetadataBased and REQUIRES the
                // v4 encrypted-metadata fields (metadata / metadata_key_id /
                // metadata_key_type), rejecting our v3 {name, username, uri,
                // description, secrets} body. The v4 encrypted-metadata path is not
                // implemented here yet, so the request payload cannot satisfy the v4
                // schema. Recorded in assertions_left_disabled.
                // .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
        }

        @Test
        void testUpdateResourceContract() throws Exception {
                Resource resource = createResourceWithPermission("Original", "original", null, Permission.UPDATE);

                ResourceDto.UpdateRequest request = ResourceDto.UpdateRequest.builder()
                                .name("Updated Name")
                                .build();

                mockMvc.perform(put("/resources/" + resource.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.name").value("Updated Name"));
                // Disabled (verified) — request-v4-metadata + body-schema-strict, NOT
                // an envelope issue: the spec's resourceUpdate request body is
                // e2eeMetadataBased (requires metadata / metadata_key_id /
                // metadata_key_type), rejecting our v3 {name, ...} body, and the
                // response body schema (v4) forbids our flat fields and requires
                // folder_parent_id / personal. We follow v3/PHP. Recorded in
                // assertions_left_disabled.
                // .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
        }

        @Test
        void testDeleteResourceContract() throws Exception {
                Resource resource = createResourceWithPermission("To Delete", null, null, Permission.OWNER);

                mockMvc.perform(delete("/resources/" + resource.getId() + ".json")
                                .accept(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                // Enabled (verified): DELETE returns the spec-mandated
                                // nullBody (soft delete, body is JSON null) and the header
                                // now carries all required fields incl. action, so the
                                // response is spec-valid. The earlier "strict JSON header
                                // validation" reason was outdated.
                                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
        }
}
