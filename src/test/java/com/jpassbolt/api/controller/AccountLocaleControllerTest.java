package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.AccountSetting;
import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AccountSettingRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AccountLocaleController}
 * (POST/GET /account/settings/locales.json): happy-path upsert, invalid
 * locale (400), and the authenticated-only contract (anonymous → 401).
 *
 * <p>
 * The endpoint is per-user and not in permitAll, so an anonymous request is
 * stopped by the security chain (HttpStatusEntryPoint → 401) before the
 * controller runs.
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "locale-user@example.com", roles = { "USER" })
class AccountLocaleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountSettingRepository accountSettingRepository;

    @Autowired
    private OrganizationSettingRepository organizationSettingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        accountSettingRepository.deleteAll();
        organizationSettingRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role userRole = new Role();
        userRole.setName("user");
        userRole = roleRepository.save(userRole);

        testUser = new User();
        testUser.setUsername("locale-user@example.com");
        testUser.setRoleId(userRole.getId());
        testUser.setActive(true);
        testUser.setDeleted(false);
        testUser = userRepository.save(testUser);
    }

    private void seedOrgLocale(String value) {
        OrganizationSetting setting = new OrganizationSetting();
        setting.setProperty("locale");
        setting.setPropertyId(UUID.randomUUID().toString());
        setting.setValue(value);
        setting.setCreatedBy(testUser.getId());
        setting.setModifiedBy(testUser.getId());
        organizationSettingRepository.save(setting);
    }

    // ------------------------------------------------------------------
    // POST /account/settings/locales.json
    // ------------------------------------------------------------------

    @Test
    void testSelect_ValidLocale_PersistsAndReturnsSuccess() throws Exception {
        mockMvc.perform(post("/account/settings/locales.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"zh-CN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.message").value("The operation was successful."))
                .andExpect(jsonPath("$.body.value").value("zh-CN"))
                .andExpect(jsonPath("$.body.property").value("locale"))
                .andExpect(jsonPath("$.body.user_id").value(testUser.getId()));

        AccountSetting saved = accountSettingRepository
                .findFirstByUserIdAndProperty(testUser.getId(), "locale").orElseThrow();
        assertThat(saved.getValue()).isEqualTo("zh-CN");
    }

    @Test
    void testSelect_ExistingLocale_IsUpdated() throws Exception {
        // first write
        mockMvc.perform(post("/account/settings/locales.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"en-UK\"}"))
                .andExpect(status().isOk());

        // overwrite with a different locale
        mockMvc.perform(post("/account/settings/locales.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"fr-FR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.value").value("fr-FR"));

        // exactly one row remains for this user/property
        assertThat(accountSettingRepository.findAll().stream()
                .filter(s -> "locale".equals(s.getProperty())
                        && testUser.getId().equals(s.getUserId()))
                .count()).isEqualTo(1);
    }

    @Test
    void testSelect_InvalidLocale_BadRequest() throws Exception {
        mockMvc.perform(post("/account/settings/locales.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"xx-XX\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.message").value("This is not a valid locale."));

        assertThat(accountSettingRepository
                .findFirstByUserIdAndProperty(testUser.getId(), "locale")).isEmpty();
    }

    @Test
    void testSelect_MissingValue_BadRequest() throws Exception {
        mockMvc.perform(post("/account/settings/locales.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("This is not a valid locale."));
    }

    @Test
    @WithAnonymousUser
    void testSelect_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(post("/account/settings/locales.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"zh-CN\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // GET /account/settings/locales.json
    // ------------------------------------------------------------------

    @Test
    void testView_NoSetting_FallsBackToOrgThenDefault() throws Exception {
        // nothing set anywhere → default
        mockMvc.perform(get("/account/settings/locales.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.value").value("en-UK"));

        // org-level set, still no user setting → org value
        seedOrgLocale("de-DE");
        mockMvc.perform(get("/account/settings/locales.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.value").value("de-DE"));
    }

    @Test
    void testView_UserSetting_TakesPrecedence() throws Exception {
        seedOrgLocale("de-DE");
        mockMvc.perform(post("/account/settings/locales.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"zh-CN\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/account/settings/locales.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.value").value("zh-CN"));
    }

    @Test
    @WithAnonymousUser
    void testView_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/account/settings/locales.json"))
                .andExpect(status().isUnauthorized());
    }
}
