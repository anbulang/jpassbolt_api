package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.model.ResourceType;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.ResourceTypeRepository;
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
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for ResourceTypeController (read-only index/view).
 *
 * <p>Behavioral reference: PHP ResourceTypesIndexController /
 * ResourceTypesViewController (v4 branch, TotpResourceTypes enabled).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class ResourceTypeControllerTest {

        // Real v4 definition JSON strings, verbatim from the authoritative seed
        // (docs/ref_files/V1__Initial_Schema_Data_H2.sql).
        private static final String PASSWORD_STRING_DEFINITION = """
                        {"resource":{"type":"object","required":["name"],"properties":{"name":{"type":"string","maxLength":255},"username":{"anyOf":[{"type":"string","maxLength":255},{"type":"null"}]},"uri":{"anyOf":[{"type":"string","maxLength":1024},{"type":"null"}]},"description":{"anyOf":[{"type":"string","maxLength":10000},{"type":"null"}]}}},"secret":{"type":"string","maxLength":4096}}""";

        private static final String PASSWORD_AND_DESCRIPTION_DEFINITION = """
                        {"resource":{"type":"object","required":["name"],"properties":{"name":{"type":"string","maxLength":255},"username":{"anyOf":[{"type":"string","maxLength":255},{"type":"null"}]},"uri":{"anyOf":[{"type":"string","maxLength":1024},{"type":"null"}]}}},"secret":{"type":"object","required":["password"],"properties":{"password":{"type":"string","maxLength":4096},"description":{"anyOf":[{"type":"string","maxLength":10000},{"type":"null"}]}}}}""";

        private static final String TOTP_DEFINITION = """
                        {"resource":{"type":"object","required":["name"],"properties":{"name":{"type":"string","maxLength":255},"uri":{"anyOf":[{"type":"string","maxLength":1024},{"type":"null"}]}}},"secret":{"type":"object","required":["totp"],"properties":{"totp":{"type":"object","required":["secret_key","digits","algorithm"],"properties":{"algorithm":{"type":"string","minLength":4,"maxLength":6},"secret_key":{"type":"string","maxLength":1024},"digits":{"type":"number","minimum":6,"exclusiveMaximum":9},"period":{"type":"number"}}}}}}""";

        private static final String PASSWORD_DESCRIPTION_TOTP_DEFINITION = """
                        {"resource":{"type":"object","required":["name"],"properties":{"name":{"type":"string","maxLength":255},"username":{"anyOf":[{"type":"string","maxLength":255},{"type":"null"}]},"uri":{"anyOf":[{"type":"string","maxLength":1024},{"type":"null"}]}}},"secret":{"type":"object","required":["password","totp"],"properties":{"password":{"type":"string","maxLength":4096},"description":{"anyOf":[{"type":"string","maxLength":10000},{"type":"null"}]},"totp":{"type":"object","required":["secret_key","digits","algorithm"],"properties":{"algorithm":{"type":"string","minLength":4,"maxLength":6},"secret_key":{"type":"string","maxLength":1024},"digits":{"type":"number","minimum":6,"exclusiveMaximum":9},"period":{"type":"number"}}}}}}""";

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ResourceTypeRepository resourceTypeRepository;

        @Autowired
        private PermissionRepository permissionRepository;

        @Autowired
        private SecretRepository secretRepository;

        @Autowired
        private ResourceRepository resourceRepository;

        @Autowired
        private UserRepository userRepository;

        @Autowired
        private ObjectMapper objectMapper;

        @BeforeEach
        void setUp() {
                // Leftover rows from other test classes (resources/secrets/permissions
                // reference users via @ManyToOne FK constraints in H2), so clear
                // dependent tables before users. resource_types has no FK either way.
                permissionRepository.deleteAll();
                secretRepository.deleteAll();
                resourceRepository.deleteAll();
                resourceTypeRepository.deleteAll();
                userRepository.deleteAll();

                User testUser = new User();
                testUser.setUsername("test@example.com");
                testUser.setRoleId("user");
                testUser.setActive(true);
                testUser.setDeleted(false);
                userRepository.save(testUser);
        }

        /**
         * Helper to persist a resource type directly via the repository.
         */
        private ResourceType createResourceType(String slug, String name, String definitionJson,
                        LocalDateTime deleted) {
                ResourceType resourceType = new ResourceType();
                resourceType.setSlug(slug);
                resourceType.setName(name);
                resourceType.setDescription("Description of " + slug);
                resourceType.setDefinition(definitionJson);
                resourceType.setDeleted(deleted);
                return resourceTypeRepository.save(resourceType);
        }

        /**
         * Seed the 4 standard v4 resource types (TotpResourceTypes enabled).
         */
        private void seedV4Types() {
                createResourceType(ResourceType.SLUG_PASSWORD_STRING, "Simple password",
                                PASSWORD_STRING_DEFINITION, null);
                createResourceType(ResourceType.SLUG_PASSWORD_AND_DESCRIPTION, "Password with description",
                                PASSWORD_AND_DESCRIPTION_DEFINITION, null);
                createResourceType(ResourceType.SLUG_STANDALONE_TOTP, "Standalone TOTP",
                                TOTP_DEFINITION, null);
                createResourceType(ResourceType.SLUG_PASSWORD_DESCRIPTION_TOTP, "Password, Description and TOTP",
                                PASSWORD_DESCRIPTION_TOTP_DEFINITION, null);
        }

        @Test
        void testIndexReturnsActiveV4AndV5TypesExcludingDeleted() throws Exception {
                seedV4Types();
                // v5 type: MUST now appear. The PHP ResourceTypesIndexController with
                // passbolt.v5.enabled=true (the default) applies NO slug-version filter;
                // the client gates creation via /metadata/types/settings.
                createResourceType("v5-default", "Default resource type", "[]", null);
                // Soft-deleted type: still excluded by deleted IS NULL.
                createResourceType("legacy-type", "Legacy type", PASSWORD_STRING_DEFINITION,
                                LocalDateTime.now());

                MvcResult result = mockMvc.perform(get("/resource-types.json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body").isArray())
                                .andExpect(jsonPath("$.body.length()").value(5))
                                .andReturn();

                JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString()).get("body");
                List<String> slugs = new ArrayList<>();
                body.forEach(node -> slugs.add(node.get("slug").asText()));
                assertThat(slugs).containsExactlyInAnyOrder(
                                "password-string", "password-and-description", "totp",
                                "password-description-totp", "v5-default");
                // Soft-deleted rows are still hidden from the index.
                assertThat(slugs).doesNotContain("legacy-type");
        }

        @Test
        void testIndexDefinitionIsJsonObject() throws Exception {
                // Single type seeded so $.body[0] is deterministic.
                createResourceType(ResourceType.SLUG_PASSWORD_AND_DESCRIPTION, "Password with description",
                                PASSWORD_AND_DESCRIPTION_DEFINITION, null);

                // definition must be a deserialized JSON object, NOT a quoted string
                // (the browser extension parses it as a JSON Schema).
                mockMvc.perform(get("/resource-types.json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body[0].definition.resource.type").value("object"))
                                .andExpect(jsonPath("$.body[0].definition.secret").exists());
        }

        @Test
        void testIndexResponseFields() throws Exception {
                createResourceType(ResourceType.SLUG_PASSWORD_AND_DESCRIPTION, "Password with description",
                                PASSWORD_AND_DESCRIPTION_DEFINITION, null);

                MvcResult result = mockMvc.perform(get("/resource-types.json"))
                                .andExpect(status().isOk())
                                .andReturn();

                JsonNode element = objectMapper.readTree(result.getResponse().getContentAsString())
                                .get("body").get(0);
                List<String> keys = new ArrayList<>();
                element.fieldNames().forEachRemaining(keys::add);

                // Exactly the eight resourceType attributes (PHP
                // assertResourceTypeAttributes); in particular no "default" key:
                // the v4 PHP implementation never outputs it.
                assertThat(keys).containsExactlyInAnyOrder(
                                "id", "slug", "name", "description", "definition", "deleted", "created", "modified");
                assertThat(element.get("deleted").isNull()).isTrue();
                assertThat(element.has("default")).isFalse();
        }

        @Test
        void testIndexWithoutJsonExtension() throws Exception {
                seedV4Types();

                mockMvc.perform(get("/resource-types"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.length()").value(4));
        }

        @Test
        void testIndexEmptyTable() throws Exception {
                // No seed: empty table must yield 200 with an empty array, never 404.
                mockMvc.perform(get("/resource-types.json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body").isArray())
                                .andExpect(jsonPath("$.body").isEmpty());
        }

        @Test
        void testViewSuccess() throws Exception {
                ResourceType resourceType = createResourceType(ResourceType.SLUG_PASSWORD_AND_DESCRIPTION,
                                "Password with description", PASSWORD_AND_DESCRIPTION_DEFINITION, null);

                mockMvc.perform(get("/resource-types/" + resourceType.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.id").value(resourceType.getId()))
                                .andExpect(jsonPath("$.body.slug").value("password-and-description"))
                                .andExpect(jsonPath("$.body.definition.resource.type").value("object"));
        }

        @Test
        void testViewDeletedTypeStillReturned() throws Exception {
                // PHP view uses Table::get(): no deleted/v5 filtering, soft-deleted
                // rows are returned with 200. Intentionally different from index.
                ResourceType deletedType = createResourceType("legacy-type", "Legacy type",
                                PASSWORD_STRING_DEFINITION, LocalDateTime.now());

                mockMvc.perform(get("/resource-types/" + deletedType.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.id").value(deletedType.getId()))
                                .andExpect(jsonPath("$.body.deleted").isNotEmpty());
        }

        @Test
        void testViewInvalidUuid400() throws Exception {
                // Validation order ported from PHP: UUID check before DB lookup.
                mockMvc.perform(get("/resource-types/invalid-id.json"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message")
                                                .value("The resource identifier should be a valid UUID."));
        }

        @Test
        void testViewNotFound404() throws Exception {
                mockMvc.perform(get("/resource-types/" + UUID.randomUUID() + ".json"))
                                .andExpect(status().isNotFound())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message")
                                                .value("The resource type does not exist."));
        }

        @Test
        @WithAnonymousUser
        void testIndexUnauthenticated() throws Exception {
                // OpenAPI contract says 401, but the current global behavior is 403:
                // SecurityConfig has no authenticationEntryPoint, so the STATELESS
                // default Http403ForbiddenEntryPoint answers. Not fixed in this
                // cluster (cross-cutting concern).
                mockMvc.perform(get("/resource-types.json"))
                                .andExpect(status().isUnauthorized());
        }

        @Test
        @WithAnonymousUser
        void testViewUnauthenticated() throws Exception {
                // Unauthenticated requests return 401 (SecurityConfig authenticationEntryPoint).
                mockMvc.perform(get("/resource-types/" + UUID.randomUUID() + ".json"))
                                .andExpect(status().isUnauthorized());
        }
}
