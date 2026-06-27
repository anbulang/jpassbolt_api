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
import org.springframework.security.test.context.support.WithMockUser;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for the account-settings index endpoint
 * (GET /account/settings.json).
 *
 * <p>
 * The {@code openApi().isValid(CONTRACT_VALIDATOR)} assertion is deliberately
 * NOT used here: {@code /account/settings} has no path entry in
 * plugin-redoc-0.yaml (the only {@code /settings.json} path in the spec is the
 * org-wide settings index at the API root, not the per-user AccountSettings
 * plugin route — same situation as {@link AccountLocaleControllerContractTest}
 * and {@link AccountThemeControllerContractTest}). Running the validator
 * against an undeclared path would fail with
 * {@code validation.request.path.missing} rather than report a real envelope
 * deviation. The response shape is still asserted to be the standard
 * {@link com.jpassbolt.api.util.ApiResponse} envelope (header.id/status/code/
 * action/servertime/url + an array body) via jsonPath, which is the meaningful
 * compatibility check for this endpoint.
 * </p>
 */
@WithMockUser(username = "settings-contract@example.com", roles = { "USER" })
public class AccountSettingsIndexControllerContractTest extends OpenApiComplianceTest {

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

    private User user;

    @BeforeEach
    void seedData() {
        accountSettingRepository.deleteAll();
        // FK-safe cleanup of rows referencing USERS left behind by other
        // resource-creating tests in this shared in-memory DB (otherwise
        // "delete from users" fails with H2 23503 on RESOURCES.created_by).
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

        user = new User();
        user.setUsername("settings-contract@example.com");
        user.setRoleId(userRole.getId());
        user.setActive(true);
        user.setDeleted(false);
        user = userRepository.save(user);

        seedSetting(user.getId(), "theme", "midgar");
        seedSetting(user.getId(), "locale", "fr-FR");
    }

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
    void getSettings_returnsStandardEnvelope() throws Exception {
        mockMvc.perform(get("/account/settings.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.id").exists())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.code").value(200))
                .andExpect(jsonPath("$.header.action").exists())
                .andExpect(jsonPath("$.header.servertime").exists())
                .andExpect(jsonPath("$.header.url").value("/account/settings.json"))
                .andExpect(jsonPath("$.body").isArray())
                .andExpect(jsonPath("$.body.length()").value(2))
                .andExpect(jsonPath("$.body[0].id").exists())
                .andExpect(jsonPath("$.body[0].user_id").value(user.getId()))
                .andExpect(jsonPath("$.body[0].property").exists())
                .andExpect(jsonPath("$.body[0].value").exists());
    }
}
