package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.AccountSetting;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AccountSettingRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AccountThemeController}
 * (POST/GET /account/settings/themes.json): happy-path upsert, invalid theme
 * (400), and the authenticated-only contract (anonymous → 401). The twin of
 * {@link AccountLocaleControllerTest}; there is no organization-level theme, so
 * the GET fallback is simply the default.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "theme-user@example.com", roles = { "USER" })
class AccountThemeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountSettingRepository accountSettingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        accountSettingRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role userRole = new Role();
        userRole.setName("user");
        userRole = roleRepository.save(userRole);

        testUser = new User();
        testUser.setUsername("theme-user@example.com");
        testUser.setRoleId(userRole.getId());
        testUser.setActive(true);
        testUser.setDeleted(false);
        testUser = userRepository.save(testUser);
    }

    // ------------------------------------------------------------------
    // POST /account/settings/themes.json
    // ------------------------------------------------------------------

    @Test
    void testSelect_ValidTheme_PersistsAndReturnsSuccess() throws Exception {
        mockMvc.perform(post("/account/settings/themes.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"midgar\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.message").value("The operation was successful."))
                .andExpect(jsonPath("$.body.value").value("midgar"))
                .andExpect(jsonPath("$.body.property").value("theme"))
                .andExpect(jsonPath("$.body.user_id").value(testUser.getId()));

        AccountSetting saved = accountSettingRepository
                .findFirstByUserIdAndProperty(testUser.getId(), "theme").orElseThrow();
        assertThat(saved.getValue()).isEqualTo("midgar");
    }

    @Test
    void testSelect_ExistingTheme_IsUpdated() throws Exception {
        mockMvc.perform(post("/account/settings/themes.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"default\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/account/settings/themes.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"midgar\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.value").value("midgar"));

        // exactly one row remains for this user/property
        assertThat(accountSettingRepository.findAll().stream()
                .filter(s -> "theme".equals(s.getProperty())
                        && testUser.getId().equals(s.getUserId()))
                .count()).isEqualTo(1);
    }

    @Test
    void testSelect_InvalidTheme_BadRequest() throws Exception {
        mockMvc.perform(post("/account/settings/themes.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"neon\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.message").value("This theme is not supported."));

        assertThat(accountSettingRepository
                .findFirstByUserIdAndProperty(testUser.getId(), "theme")).isEmpty();
    }

    @Test
    void testSelect_MissingValue_BadRequest() throws Exception {
        mockMvc.perform(post("/account/settings/themes.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value("This theme is not supported."));
    }

    @Test
    @WithAnonymousUser
    void testSelect_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(post("/account/settings/themes.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"midgar\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // GET /account/settings/themes.json
    // ------------------------------------------------------------------

    @Test
    void testView_NoSetting_FallsBackToDefault() throws Exception {
        mockMvc.perform(get("/account/settings/themes.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.value").value("default"))
                .andExpect(jsonPath("$.body.options[0]").value("default"))
                .andExpect(jsonPath("$.body.options[1]").value("midgar"));
    }

    @Test
    void testView_UserSetting_TakesPrecedence() throws Exception {
        mockMvc.perform(post("/account/settings/themes.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"midgar\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/account/settings/themes.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.value").value("midgar"));
    }

    @Test
    @WithAnonymousUser
    void testView_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/account/settings/themes.json"))
                .andExpect(status().isUnauthorized());
    }
}
