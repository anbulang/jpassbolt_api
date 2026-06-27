package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for the CE password-policies settings endpoint
 * (GET /password-policies/settings.json).
 *
 * <p>
 * The {@code openApi().isValid(CONTRACT_VALIDATOR)} assertion is deliberately
 * NOT used here: {@code /password-policies/settings} has no path entry in
 * plugin-redoc-0.yaml (the PHP PasswordPolicies plugin route is outside the
 * OpenAPI domain that the spec describes — same situation as the
 * AccountLocaleController / AccountThemeController endpoints, whose contract
 * tests also cover the envelope without isValid). Running the validator against
 * an undeclared path would fail with {@code validation.request.path.missing}
 * rather than report a real envelope deviation. The response shape is still
 * asserted to be the standard {@link com.jpassbolt.api.util.ApiResponse}
 * envelope (all 7 header fields + body) via jsonPath, which is the meaningful
 * compatibility check for this endpoint.
 * </p>
 */
@WithMockUser(username = "policies-contract@example.com", roles = { "USER" })
public class PasswordPoliciesControllerContractTest extends OpenApiComplianceTest {

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

    @BeforeEach
    void seedData() {
        // Clear rows that reference USERS via FK (left behind by other
        // resource-creating tests sharing this in-memory DB) before deleting
        // users — same FK-safe ordering as EmailNotificationSettingsControllerTest.
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

        User user = new User();
        user.setUsername("policies-contract@example.com");
        user.setRoleId(userRole.getId());
        user.setActive(true);
        user.setDeleted(false);
        userRepository.save(user);
    }

    @Test
    void getSettings_returnsStandardEnvelope() throws Exception {
        mockMvc.perform(get("/password-policies/settings.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.id").exists())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.code").value(200))
                .andExpect(jsonPath("$.header.action").exists())
                .andExpect(jsonPath("$.header.servertime").exists())
                .andExpect(jsonPath("$.header.message").value("The operation was successful."))
                .andExpect(jsonPath("$.header.url").value("/password-policies/settings.json"))
                .andExpect(jsonPath("$.body.default_generator").value("password"))
                .andExpect(jsonPath("$.body.source").value("default"))
                .andExpect(jsonPath("$.body.password_generator_settings.length").value(18))
                .andExpect(jsonPath("$.body.passphrase_generator_settings.word_case").value("lowercase"));
    }
}
