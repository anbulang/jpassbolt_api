package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.ResourceType;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.ResourceTypeRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.LocalDateTime;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI contract tests for the resource type WRITE endpoints
 * ({@code PUT|DELETE /resource-types/{resourceTypeId}.json}), the only gap that
 * existed in plugin-redoc-0.yaml. Ported from PHP
 * {@code ResourceTypesUpdateController} / {@code ResourceTypesDeleteController}.
 *
 * <p>Both write endpoints are admin-gated, so the mock principal maps to a real
 * {@code admin} Role row (same approach as
 * {@link MetadataSettingsControllerContractTest}); {@code userService.isAdmin()}
 * resolves the role through the roles table. The spec declares 200/400/401/403/404
 * for both, so {@code openApi().isValid(CONTRACT_VALIDATOR)} is enabled on the
 * success (PUT emptyStringBody / DELETE nullBody) and the 403 paths.</p>
 */
@WithMockUser(username = "admin@passbolt.com", roles = { "USER" })
class ResourceTypeWriteControllerContractTest extends OpenApiComplianceTest {

    @Autowired
    private ResourceTypeRepository resourceTypeRepository;
    @Autowired
    private ResourceRepository resourceRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private OrganizationSettingRepository organizationSettingRepository;

    private Role userRole;

    @BeforeEach
    void seedData() {
        // resource_types has no enforced FK from resources (Resource.resourceTypeId
        // is a plain String column), so clearing order is not significant.
        resourceRepository.deleteAll();
        resourceTypeRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();
        // No org-setting row → metadata types settings default to v4, so the
        // "last of the default (v4) version" guard counts the v4 family below.
        organizationSettingRepository.deleteAll();

        Role adminRole = new Role();
        adminRole.setName(Role.ADMIN);
        adminRole.setDescription("Organization administrator");
        roleRepository.save(adminRole);

        userRole = new Role();
        userRole.setName(Role.USER);
        userRole.setDescription("Logged in user");
        roleRepository.save(userRole);

        User admin = new User();
        admin.setUsername("admin@passbolt.com");
        admin.setRoleId(adminRole.getId());
        admin.setActive(true);
        admin.setDeleted(false);
        userRepository.save(admin);

        // Seed the 4 v4 types so neither the "only one left" nor the
        // "last of the default version" guard trips when deleting one.
        createType(ResourceType.SLUG_PASSWORD_STRING);
        createType(ResourceType.SLUG_PASSWORD_AND_DESCRIPTION);
        createType(ResourceType.SLUG_STANDALONE_TOTP);
        createType(ResourceType.SLUG_PASSWORD_DESCRIPTION_TOTP);
    }

    private ResourceType createType(String slug) {
        ResourceType rt = new ResourceType();
        rt.setSlug(slug);
        rt.setName(slug);
        rt.setDescription("seed");
        rt.setDefinition("{}");
        rt.setDeleted(null);
        return resourceTypeRepository.save(rt);
    }

    // ------------------------------------------------------------------
    // DELETE — soft-delete
    // ------------------------------------------------------------------

    @Test
    void testDeleteResourceTypeContract() throws Exception {
        ResourceType target = resourceTypeRepository.findBySlug(ResourceType.SLUG_STANDALONE_TOTP).orElseThrow();

        mockMvc.perform(delete("/resource-types/" + target.getId() + ".json")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));

        // Soft-deleted (deleted timestamp set), not physically removed.
        ResourceType reloaded = resourceTypeRepository.findById(target.getId()).orElseThrow();
        Assertions.assertNotNull(reloaded.getDeleted());
    }

    @Test
    void testDeleteInvalidUuidReturns400() throws Exception {
        mockMvc.perform(delete("/resource-types/not-a-uuid.json")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testDeleteNonExistentReturns404() throws Exception {
        mockMvc.perform(delete("/resource-types/00000000-0000-4000-8000-000000000000.json")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void testDeleteAlreadyDeletedReturns400() throws Exception {
        ResourceType target = resourceTypeRepository.findBySlug(ResourceType.SLUG_STANDALONE_TOTP).orElseThrow();
        target.setDeleted(LocalDateTime.now());
        resourceTypeRepository.save(target);

        mockMvc.perform(delete("/resource-types/" + target.getId() + ".json")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "nonadmin@passbolt.com", roles = { "USER" })
    void testDeleteNonAdminForbidden() throws Exception {
        User plain = new User();
        plain.setUsername("nonadmin@passbolt.com");
        plain.setRoleId(userRole.getId());
        plain.setActive(true);
        plain.setDeleted(false);
        userRepository.save(plain);

        ResourceType target = resourceTypeRepository.findBySlug(ResourceType.SLUG_STANDALONE_TOTP).orElseThrow();

        mockMvc.perform(delete("/resource-types/" + target.getId() + ".json")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    // ------------------------------------------------------------------
    // PUT — restore (undo soft-delete); body must be exactly {"deleted": null}
    // ------------------------------------------------------------------

    @Test
    void testRestoreResourceTypeContract() throws Exception {
        ResourceType target = resourceTypeRepository.findBySlug(ResourceType.SLUG_STANDALONE_TOTP).orElseThrow();
        target.setDeleted(LocalDateTime.now());
        resourceTypeRepository.save(target);

        mockMvc.perform(put("/resource-types/" + target.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("{\"deleted\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));

        ResourceType reloaded = resourceTypeRepository.findById(target.getId()).orElseThrow();
        Assertions.assertNull(reloaded.getDeleted());
    }

    @Test
    void testUpdateRejectsNonDeletedProperty() throws Exception {
        ResourceType target = resourceTypeRepository.findBySlug(ResourceType.SLUG_STANDALONE_TOTP).orElseThrow();

        mockMvc.perform(put("/resource-types/" + target.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"hacked\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testUpdateOnActiveTypeReturns400() throws Exception {
        // {"deleted": null} on a type that is NOT currently deleted → 400.
        ResourceType target = resourceTypeRepository.findBySlug(ResourceType.SLUG_STANDALONE_TOTP).orElseThrow();

        mockMvc.perform(put("/resource-types/" + target.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content("{\"deleted\":null}"))
                .andExpect(status().isBadRequest());
    }
}
