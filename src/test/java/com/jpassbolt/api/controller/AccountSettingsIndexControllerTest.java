package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.AccountSetting;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AccountSettingRepository;
import com.jpassbolt.api.repository.CommentRepository;
import com.jpassbolt.api.repository.FavoriteRepository;
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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AccountSettingsIndexController}
 * (GET /account/settings.json): the caller's own {@code theme} + {@code locale}
 * rows are returned as an array, a non-whitelisted property is excluded, and an
 * anonymous call is rejected with 401. Port-of behaviour mirror of PHP
 * {@code AccountSettingsIndexController::index} with whitelist
 * {@code ['theme','locale']}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "settings-user@example.com", roles = { "USER" })
class AccountSettingsIndexControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountSettingRepository accountSettingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private FavoriteRepository favoriteRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        accountSettingRepository.deleteAll();
        // Clear rows that reference USERS via FK (left behind by other
        // resource-creating tests sharing this in-memory DB) before deleting
        // users — same FK-safe ordering as AccountSettingsIndexControllerContractTest.
        // Without this, orphaned RESOURCES.created_by rows make
        // "delete from users" fail with H2 23503.
        favoriteRepository.deleteAll();
        commentRepository.deleteAll();
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role userRole = new Role();
        userRole.setName("user");
        userRole = roleRepository.save(userRole);

        testUser = new User();
        testUser.setUsername("settings-user@example.com");
        testUser.setRoleId(userRole.getId());
        testUser.setActive(true);
        testUser.setDeleted(false);
        testUser = userRepository.save(testUser);
    }

    /** Seed an account_settings row (property_id is non-null, so derive it). */
    private void seedSetting(String userId, String property, String value) {
        AccountSetting setting = new AccountSetting();
        setting.setUserId(userId);
        setting.setProperty(property);
        setting.setPropertyId(UUID.nameUUIDFromBytes(
                ("account.setting." + property).getBytes(StandardCharsets.UTF_8)).toString());
        setting.setValue(value);
        accountSettingRepository.save(setting);
    }

    @Test
    void testIndex_ReturnsWhitelistedSettingsOnly() throws Exception {
        seedSetting(testUser.getId(), "theme", "midgar");
        seedSetting(testUser.getId(), "locale", "fr-FR");
        // A non-whitelisted property that must NOT appear in the response.
        seedSetting(testUser.getId(), "mfa", "{\"providers\":[\"totp\"]}");

        mockMvc.perform(get("/account/settings.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.message").value("The operation was successful."))
                .andExpect(jsonPath("$.body").isArray())
                .andExpect(jsonPath("$.body.length()").value(2))
                // both whitelisted rows present
                .andExpect(jsonPath("$.body[?(@.property=='theme')].value").value("midgar"))
                .andExpect(jsonPath("$.body[?(@.property=='locale')].value").value("fr-FR"))
                .andExpect(jsonPath("$.body[?(@.property=='theme')].user_id").value(testUser.getId()))
                // the foreign property is excluded
                .andExpect(jsonPath("$.body[?(@.property=='mfa')]").isEmpty());
    }

    @Test
    void testIndex_NoSettings_ReturnsEmptyArray() throws Exception {
        mockMvc.perform(get("/account/settings.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body").isArray())
                .andExpect(jsonPath("$.body.length()").value(0));
    }

    @Test
    void testIndex_OnlyOwnSettings_OtherUsersExcluded() throws Exception {
        User other = new User();
        other.setUsername("other-user@example.com");
        other.setRoleId(testUser.getRoleId());
        other.setActive(true);
        other.setDeleted(false);
        other = userRepository.save(other);

        seedSetting(testUser.getId(), "theme", "default");
        seedSetting(other.getId(), "locale", "de-DE");

        mockMvc.perform(get("/account/settings.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.length()").value(1))
                .andExpect(jsonPath("$.body[0].property").value("theme"))
                .andExpect(jsonPath("$.body[0].user_id").value(testUser.getId()));
    }

    @Test
    void testIndex_BareNoJsonSuffix_AlsoWorks() throws Exception {
        seedSetting(testUser.getId(), "theme", "midgar");

        mockMvc.perform(get("/account/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.length()").value(1))
                .andExpect(jsonPath("$.body[0].property").value("theme"));
    }

    @Test
    @WithAnonymousUser
    void testIndex_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/account/settings.json"))
                .andExpect(status().isUnauthorized());
    }
}
