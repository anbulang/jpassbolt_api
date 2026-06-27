package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.CommentRepository;
import com.jpassbolt.api.repository.FavoriteRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for the organization email notification settings endpoints
 * (GET/POST /settings/emails/notifications.json).
 *
 * <p>
 * The {@code openApi().isValid(CONTRACT_VALIDATOR)} assertion is deliberately
 * NOT used here: {@code /settings/emails/notifications} has no path entry in
 * plugin-redoc-0.yaml (the PHP CE {@code EmailNotificationSettings} plugin
 * route is outside the OpenAPI domain that the spec describes — the same
 * situation as the locale/theme endpoints, which
 * {@code AccountLocaleControllerContractTest} /
 * {@code AccountThemeControllerContractTest} also cover without isValid).
 * Running the validator against an undeclared path would fail with
 * {@code validation.request.path.missing} rather than report a real envelope
 * deviation. The response shape is still asserted to be the standard
 * {@link com.jpassbolt.api.util.ApiResponse} envelope (header.id / status /
 * code / action / servertime / url + body) via jsonPath, which is the
 * meaningful compatibility check for this endpoint.
 * </p>
 *
 * <p>
 * Both verbs are admin-only, so the seeded contract user is given the
 * {@code "admin"} role (the name {@code UserService.isAdmin} resolves against).
 * </p>
 */
@WithMockUser(username = "email-notif-contract@example.com", roles = { "ADMIN" })
public class EmailNotificationSettingsControllerContractTest extends OpenApiComplianceTest {

    private static final String URL = "/settings/emails/notifications.json";

    @Autowired
    private OrganizationSettingRepository organizationSettingRepository;

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
        organizationSettingRepository.deleteAll();
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

        Role adminRole = new Role();
        adminRole.setName(Role.ADMIN);
        adminRole = roleRepository.save(adminRole);

        User user = new User();
        user.setUsername("email-notif-contract@example.com");
        user.setRoleId(adminRole.getId());
        user.setActive(true);
        user.setDeleted(false);
        userRepository.save(user);
    }

    @Test
    void getNotifications_returnsStandardEnvelope() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.id").exists())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.code").value(200))
                .andExpect(jsonPath("$.header.action").exists())
                .andExpect(jsonPath("$.header.servertime").exists())
                .andExpect(jsonPath("$.header.url").value(URL))
                .andExpect(jsonPath("$.body.purify_subject").value(false))
                .andExpect(jsonPath("$.body.length()").value(25));
    }

    @Test
    void postNotifications_returnsStandardEnvelope() throws Exception {
        mockMvc.perform(post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"send_password_create\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.id").exists())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.code").value(200))
                .andExpect(jsonPath("$.header.action").exists())
                .andExpect(jsonPath("$.header.servertime").exists())
                .andExpect(jsonPath("$.header.url").value(URL))
                .andExpect(jsonPath("$.header.message")
                        .value("The notification settings for the organization were updated."))
                .andExpect(jsonPath("$.body.send_password_create").value(true))
                .andExpect(jsonPath("$.body.length()").value(25));
    }
}
