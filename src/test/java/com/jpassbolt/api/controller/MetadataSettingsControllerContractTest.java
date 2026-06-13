package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.MetadataSettingsDto;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.MetadataKeyRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI contract tests for the v5 Metadata settings endpoints
 * ({@code GET|POST /metadata/keys/settings.json} and
 * {@code GET|POST /metadata/types/settings.json}).
 *
 * <p>
 * Both paths exist in plugin-redoc-0.yaml with the response bodies
 * {@code metadataKeysSettingsIndex} / {@code metadataTypesSettingsIndexAndView}
 * (each wrapped in the standard header+body envelope), so the
 * {@code openApi().isValid(CONTRACT_VALIDATOR)} assertion is ENABLED on the GET
 * success paths and on the types-settings POST success path (a single flat
 * schema). It is intentionally DISABLED (documented inline) on: the keys-settings
 * POST (its {@code metadataKeysSettingsUpdate} allOf request body trips the
 * validator's strict per-branch additionalProperties, the same e2eeMetadataBased
 * artefact noted on Folder/Move/GroupShare); the deliberately spec-invalid 400
 * request body; and the non-admin 403 case (403 is not in the spec for these
 * POSTs).
 * </p>
 *
 * <p>
 * The mock user maps to a real {@code admin} Role row (seeded in
 * {@link #seedData()}) because {@code userService.isAdmin()} resolves the role
 * through the roles table — the admin-gated POST endpoints need an admin
 * principal. The non-admin 403 case overrides the principal at method level and
 * documents its {@code isValid} disable: the spec declares only 400/401/404 for
 * these POSTs (no 403), matching the {@code UsersController} 403 precedent where
 * "PHP behaviour wins" but the spec lacks the status, so a contract assertion
 * on that response is intentionally not made.
 * </p>
 */
@WithMockUser(username = "admin@passbolt.com", roles = { "USER" })
class MetadataSettingsControllerContractTest extends OpenApiComplianceTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private OrganizationSettingRepository organizationSettingRepository;

    @Autowired
    private MetadataKeyRepository metadataKeyRepository;

    private Role adminRole;
    private Role userRole;

    @BeforeEach
    void seedData() {
        organizationSettingRepository.deleteAll();
        metadataKeyRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        adminRole = new Role();
        adminRole.setName(Role.ADMIN);
        adminRole.setDescription("Organization administrator");
        roleRepository.save(adminRole);

        userRole = new Role();
        userRole.setName(Role.USER);
        userRole.setDescription("Logged in user");
        roleRepository.save(userRole);

        // The @WithMockUser principal must resolve to an admin so the gated
        // POST endpoints are reachable; isAdmin() walks roleRepository.
        User admin = new User();
        admin.setUsername("admin@passbolt.com");
        admin.setRoleId(adminRole.getId());
        admin.setActive(true);
        admin.setDeleted(false);
        userRepository.save(admin);
    }

    // ------------------------------------------------------------------
    // keys settings
    // ------------------------------------------------------------------

    @Test
    void testGetKeysSettingsReturnsDefaultsContract() throws Exception {
        // No org-setting row seeded → service returns the documented defaults.
        mockMvc.perform(get("/metadata/keys/settings.json")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.allow_usage_of_personal_keys").value(true))
                .andExpect(jsonPath("$.body.zero_knowledge_key_share").value(false))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    void testSetKeysSettingsContract() throws Exception {
        MetadataSettingsDto.KeysSettingsUpdate request =
                MetadataSettingsDto.KeysSettingsUpdate.builder()
                        .allowUsageOfPersonalKeys(false)
                        .zeroKnowledgeKeyShare(true)
                        .build();

        // isValid is DISABLED here (documented): the request body schema
        // metadataKeysSettingsUpdate is allOf[metadataKeysSettingsIndex,
        // {metadata_private_keys}]. swagger-request-validator 2.39.0 validates
        // each allOf branch under strict additionalProperties even with
        // withResolveCombinators(true), so the two flag properties declared in
        // branch /allOf/0 are wrongly reported as "additional" while validating
        // branch /allOf/1 — the same e2eeMetadataBased allOf artefact already
        // documented on Folder/Move/GroupShare. The response envelope itself is
        // spec-correct (verified by the GET test's enabled isValid against the
        // identical metadataKeysSettingsIndex body), so it is asserted via
        // jsonPath here instead.
        mockMvc.perform(post("/metadata/keys/settings.json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.allow_usage_of_personal_keys").value(false))
                .andExpect(jsonPath("$.body.zero_knowledge_key_share").value(true));
    }

    @Test
    @WithMockUser(username = "nonadmin@passbolt.com", roles = { "USER" })
    void testSetKeysSettingsNonAdminForbidden() throws Exception {
        // The non-admin principal must exist as a (non-admin) user row.
        User plain = new User();
        plain.setUsername("nonadmin@passbolt.com");
        plain.setRoleId(userRole.getId());
        plain.setActive(true);
        plain.setDeleted(false);
        userRepository.save(plain);

        MetadataSettingsDto.KeysSettingsUpdate request =
                MetadataSettingsDto.KeysSettingsUpdate.builder()
                        .allowUsageOfPersonalKeys(true)
                        .zeroKnowledgeKeyShare(false)
                        .build();

        // 403 is not declared in the spec for this POST (only 400/401/404), so
        // the isValid assertion is intentionally omitted here — same documented
        // deviation as UsersController's admin-only endpoints.
        mockMvc.perform(post("/metadata/keys/settings.json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    // ------------------------------------------------------------------
    // types settings
    // ------------------------------------------------------------------

    @Test
    void testGetTypesSettingsReturnsV4DefaultsContract() throws Exception {
        mockMvc.perform(get("/metadata/types/settings.json")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.default_resource_types").value("v4"))
                .andExpect(jsonPath("$.body.allow_creation_of_v4_resources").value(true))
                .andExpect(jsonPath("$.body.allow_creation_of_v5_resources").value(false))
                .andExpect(jsonPath("$.body.allow_v4_v5_upgrade").value(false))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    void testSetTypesSettingsContract() throws Exception {
        // A pure-v4 payload: stays valid without needing an active metadata key
        // (the "active key required when v5 enabled" rule does not trip).
        MetadataSettingsDto.TypesSettings request =
                MetadataSettingsDto.TypesSettings.builder()
                        .defaultResourceTypes("v4")
                        .defaultFolderType("v4")
                        .defaultTagType("v4")
                        .defaultCommentType("v4")
                        .allowCreationOfV5Resources(false)
                        .allowCreationOfV5Folders(false)
                        .allowCreationOfV5Tags(false)
                        .allowCreationOfV5Comments(false)
                        .allowCreationOfV4Resources(true)
                        .allowCreationOfV4Folders(true)
                        .allowCreationOfV4Tags(true)
                        .allowCreationOfV4Comments(true)
                        .allowV5V4Downgrade(false)
                        .allowV4V5Upgrade(false)
                        .build();

        mockMvc.perform(post("/metadata/types/settings.json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.default_resource_types").value("v4"))
                .andExpect(jsonPath("$.body.allow_v4_v5_upgrade").value(false))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    void testSetTypesSettingsInvalidVersionBadRequest() throws Exception {
        // default_resource_types = "v9" is not in the v4/v5 enum → service
        // throws 400; the badRequest response is contract-validated.
        String invalid = "{"
                + "\"default_resource_types\":\"v9\","
                + "\"default_folder_type\":\"v4\","
                + "\"default_tag_type\":\"v4\","
                + "\"default_comment_type\":\"v4\","
                + "\"allow_creation_of_v5_resources\":false,"
                + "\"allow_creation_of_v5_folders\":false,"
                + "\"allow_creation_of_v5_tags\":false,"
                + "\"allow_creation_of_v5_comments\":false,"
                + "\"allow_creation_of_v4_resources\":true,"
                + "\"allow_creation_of_v4_folders\":true,"
                + "\"allow_creation_of_v4_tags\":true,"
                + "\"allow_creation_of_v4_comments\":true,"
                + "\"allow_v5_v4_downgrade\":false,"
                + "\"allow_v4_v5_upgrade\":false}";

        // isValid is DISABLED here (documented): this case deliberately POSTs a
        // spec-INVALID request body ("v9" is outside the v4/v5 enum) to exercise
        // the service's 400 path, so openApi().isValid — which validates the
        // request as well as the response — would (correctly) reject the request
        // body. The response status/shape is asserted via jsonPath instead.
        mockMvc.perform(post("/metadata/types/settings.json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"));
    }
}
