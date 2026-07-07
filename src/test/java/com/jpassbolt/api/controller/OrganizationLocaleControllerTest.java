package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link OrganizationLocaleController}
 * (POST /locale/settings.json): admin happy-path upsert, invalid locale
 * (400), non-admin (403 with empty-string body), and anonymous (401).
 *
 * <p>Per-method {@code @WithMockUser} (no class-level) so the
 * unauthenticated boundary test can run anonymously.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrganizationLocaleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationSettingRepository organizationSettingRepository;

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

    private User admin;

    @BeforeEach
    void setUp() {
        organizationSettingRepository.deleteAll();
        // Clear resource-graph rows a sibling test may have left in the
        // shared in-memory DB before deleting users: resources.created_by
        // -> users.id is an enforced FK, so orphan resources would block
        // userRepository.deleteAll().
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role adminRole = new Role();
        adminRole.setName(Role.ADMIN);
        adminRole = roleRepository.save(adminRole);
        admin = new User();
        admin.setUsername("admin@passbolt.com");
        admin.setRoleId(adminRole.getId());
        admin.setActive(true);
        admin.setDeleted(false);
        admin = userRepository.save(admin);

        Role userRole = new Role();
        userRole.setName(Role.USER);
        userRole = roleRepository.save(userRole);
        User regular = new User();
        regular.setUsername("user@passbolt.com");
        regular.setRoleId(userRole.getId());
        regular.setActive(true);
        regular.setDeleted(false);
        userRepository.save(regular);
    }

    @Test
    @WithMockUser(username = "admin@passbolt.com", roles = { "ADMIN" })
    void testSelect_Admin_ValidLocale_PersistsAndReturnsSuccess() throws Exception {
        mockMvc.perform(post("/locale/settings.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"fr-FR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.code").value(200))
                .andExpect(jsonPath("$.header.url").value("/locale/settings.json"))
                .andExpect(jsonPath("$.header.message").value("The operation was successful."))
                .andExpect(jsonPath("$.body.property_id").isNotEmpty())
                .andExpect(jsonPath("$.body.property").value("locale"))
                .andExpect(jsonPath("$.body.value").value("fr-FR"))
                .andExpect(jsonPath("$.body.created").isNotEmpty())
                .andExpect(jsonPath("$.body.modified").isNotEmpty())
                .andExpect(jsonPath("$.body.created_by").value(admin.getId()))
                .andExpect(jsonPath("$.body.modified_by").value(admin.getId()));

        OrganizationSetting saved = organizationSettingRepository
                .findByProperty("locale").orElseThrow();
        assertThat(saved.getValue()).isEqualTo("fr-FR");

        // Closes the loop: GET /settings.json app.locale reads the same row.
        mockMvc.perform(get("/settings.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.app.locale").value("fr-FR"));
    }

    @Test
    @WithMockUser(username = "admin@passbolt.com", roles = { "ADMIN" })
    void testSelect_ExistingSetting_IsUpdatedNotDuplicated() throws Exception {
        mockMvc.perform(post("/locale/settings.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"en-UK\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/locale/settings.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"zh-CN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.value").value("zh-CN"));

        assertThat(organizationSettingRepository.findAll().stream()
                .filter(s -> "locale".equals(s.getProperty()))
                .count()).isEqualTo(1);
        assertThat(organizationSettingRepository.findByProperty("locale")
                .orElseThrow().getValue()).isEqualTo("zh-CN");
    }

    @Test
    @WithMockUser(username = "admin@passbolt.com", roles = { "ADMIN" })
    void testSelect_InvalidLocale_BadRequest() throws Exception {
        mockMvc.perform(post("/locale/settings.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"xx-XX\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.message").value("This is not a valid locale."));

        assertThat(organizationSettingRepository.findByProperty("locale")).isEmpty();
    }

    @Test
    @WithMockUser(username = "admin@passbolt.com", roles = { "ADMIN" })
    void testSelect_MissingValue_BadRequest() throws Exception {
        mockMvc.perform(post("/locale/settings.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("This is not a valid locale."));
    }

    @Test
    @WithMockUser(username = "user@passbolt.com", roles = { "USER" })
    void testSelect_NonAdmin_Forbidden() throws Exception {
        mockMvc.perform(post("/locale/settings.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"fr-FR\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.code").value(403))
                .andExpect(jsonPath("$.header.message")
                        .value("Access restricted to administrators."))
                .andExpect(jsonPath("$.body").value(""));

        assertThat(organizationSettingRepository.findByProperty("locale")).isEmpty();
    }

    @Test
    void testSelect_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(post("/locale/settings.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"fr-FR\"}"))
                .andExpect(status().isUnauthorized());
    }
}
