package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.OrganizationSetting;
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
 * Integration tests for {@link EmailNotificationSettingsController}
 * (GET/POST /settings/emails/notifications.json): admin GET returns all 25
 * default keys; admin POST persists a toggle and a subsequent GET reflects it
 * while other keys keep their defaults; unknown keys are ignored; a non-admin
 * (role "user") gets 403 on both verbs; anonymous gets 401.
 *
 * <p>
 * The class-level {@code @WithMockUser} is the admin; the non-admin cases use a
 * method-level {@code @WithMockUser} for the seeded "user" account. Admin
 * resolution goes through {@code UserService.isAdmin}, which compares the role
 * name to {@code Role.ADMIN} ("admin"), so the admin user is seeded with a role
 * named "admin".
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "email-notif-admin@example.com", roles = { "ADMIN" })
class EmailNotificationSettingsControllerTest {

    private static final String URL = "/settings/emails/notifications.json";

    @Autowired
    private MockMvc mockMvc;

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

    private User adminUser;

    @BeforeEach
    void setUp() {
        organizationSettingRepository.deleteAll();
        // Clear rows that reference USERS via FK (left behind by other
        // resource-creating tests sharing this in-memory DB) before deleting
        // users — same FK-safe ordering as CommentControllerTest /
        // FavoriteControllerTest. Without this, orphaned RESOURCES.created_by
        // rows make "delete from users" fail with H2 23503.
        favoriteRepository.deleteAll();
        commentRepository.deleteAll();
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role adminRole = new Role();
        adminRole.setName(Role.ADMIN); // "admin" — what UserService.isAdmin expects
        adminRole = roleRepository.save(adminRole);

        Role userRole = new Role();
        userRole.setName(Role.USER); // "user"
        userRole = roleRepository.save(userRole);

        adminUser = new User();
        adminUser.setUsername("email-notif-admin@example.com");
        adminUser.setRoleId(adminRole.getId());
        adminUser.setActive(true);
        adminUser.setDeleted(false);
        adminUser = userRepository.save(adminUser);

        User regularUser = new User();
        regularUser.setUsername("email-notif-user@example.com");
        regularUser.setRoleId(userRole.getId());
        regularUser.setActive(true);
        regularUser.setDeleted(false);
        userRepository.save(regularUser);
    }

    // ------------------------------------------------------------------
    // GET /settings/emails/notifications.json — admin
    // ------------------------------------------------------------------

    @Test
    void testGet_Admin_ReturnsAll25DefaultKeys() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.message").value("The operation was successful."))
                // false-by-default keys
                .andExpect(jsonPath("$.body.purify_subject").value(false))
                .andExpect(jsonPath("$.body.show_comment").value(false))
                .andExpect(jsonPath("$.body.show_description").value(false))
                .andExpect(jsonPath("$.body.show_secret").value(false))
                .andExpect(jsonPath("$.body.show_uri").value(false))
                .andExpect(jsonPath("$.body.show_username").value(false))
                .andExpect(jsonPath("$.body.send_password_create").value(false))
                // true-by-default keys (a representative spread)
                .andExpect(jsonPath("$.body.send_admin_user_setup_completed").value(true))
                .andExpect(jsonPath("$.body.send_admin_user_recover_abort").value(true))
                .andExpect(jsonPath("$.body.send_admin_user_recover_complete").value(true))
                .andExpect(jsonPath("$.body.send_admin_user_disable_user").value(true))
                .andExpect(jsonPath("$.body.send_admin_user_disable_admin").value(true))
                .andExpect(jsonPath("$.body.send_comment_add").value(true))
                .andExpect(jsonPath("$.body.send_group_delete").value(true))
                .andExpect(jsonPath("$.body.send_group_user_add").value(true))
                .andExpect(jsonPath("$.body.send_group_user_delete").value(true))
                .andExpect(jsonPath("$.body.send_group_user_update").value(true))
                .andExpect(jsonPath("$.body.send_group_manager_update").value(true))
                .andExpect(jsonPath("$.body.send_group_manager_requestAddUser").value(true))
                .andExpect(jsonPath("$.body.send_password_share").value(true))
                .andExpect(jsonPath("$.body.send_password_update").value(true))
                .andExpect(jsonPath("$.body.send_password_delete").value(true))
                .andExpect(jsonPath("$.body.send_user_create").value(true))
                .andExpect(jsonPath("$.body.send_user_recover").value(true))
                .andExpect(jsonPath("$.body.send_user_recoverComplete").value(true))
                // exactly the 25 declared keys, no more, no less
                .andExpect(jsonPath("$.body.length()").value(25));
    }

    // ------------------------------------------------------------------
    // POST /settings/emails/notifications.json — admin
    // ------------------------------------------------------------------

    @Test
    void testPost_Admin_PersistsToggle_AndGetReflectsIt() throws Exception {
        mockMvc.perform(post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"send_password_create\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.message")
                        .value("The notification settings for the organization were updated."))
                // the toggled key reflects the new value
                .andExpect(jsonPath("$.body.send_password_create").value(true))
                // other keys keep their defaults
                .andExpect(jsonPath("$.body.purify_subject").value(false))
                .andExpect(jsonPath("$.body.send_password_share").value(true))
                .andExpect(jsonPath("$.body.length()").value(25));

        // a single organization_settings row was written under the right property
        OrganizationSetting row = organizationSettingRepository
                .findByProperty("emailNotification").orElseThrow();
        assertThat(row.getValue()).contains("\"send_password_create\":true");
        assertThat(row.getCreatedBy()).isEqualTo(adminUser.getId());
        assertThat(row.getModifiedBy()).isEqualTo(adminUser.getId());

        // a fresh GET reflects the persisted change while other keys keep defaults
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.send_password_create").value(true))
                .andExpect(jsonPath("$.body.show_comment").value(false))
                .andExpect(jsonPath("$.body.send_user_create").value(true))
                .andExpect(jsonPath("$.body.length()").value(25));
    }

    @Test
    void testPost_Admin_UnknownKeyIsIgnored() throws Exception {
        mockMvc.perform(post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"not_a_real_setting\":true,\"send_password_create\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.send_password_create").value(true))
                // the unknown key is dropped from the effective settings
                .andExpect(jsonPath("$.body.not_a_real_setting").doesNotExist())
                .andExpect(jsonPath("$.body.length()").value(25));

        // and is not persisted either
        OrganizationSetting row = organizationSettingRepository
                .findByProperty("emailNotification").orElseThrow();
        assertThat(row.getValue()).doesNotContain("not_a_real_setting");
    }

    @Test
    void testPost_Admin_MergesOverExistingSettings() throws Exception {
        // first write: flip send_password_create on
        mockMvc.perform(post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"send_password_create\":true}"))
                .andExpect(status().isOk());

        // second write: flip purify_subject on; the earlier change must survive
        mockMvc.perform(post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"purify_subject\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.purify_subject").value(true))
                .andExpect(jsonPath("$.body.send_password_create").value(true));

        // exactly one row remains for this property
        assertThat(organizationSettingRepository.findAll().stream()
                .filter(s -> "emailNotification".equals(s.getProperty()))
                .count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Non-admin (role "user") — 403 on both verbs
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "email-notif-user@example.com", roles = { "USER" })
    void testGet_NonAdmin_Forbidden() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.message")
                        .value("You are not allowed to access this location."));
    }

    @Test
    @WithMockUser(username = "email-notif-user@example.com", roles = { "USER" })
    void testPost_NonAdmin_Forbidden() throws Exception {
        mockMvc.perform(post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"send_password_create\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.message")
                        .value("You are not allowed to access this location."));

        // nothing was persisted
        assertThat(organizationSettingRepository.findByProperty("emailNotification")).isEmpty();
    }

    // ------------------------------------------------------------------
    // Anonymous — 401 on both verbs
    // ------------------------------------------------------------------

    @Test
    @WithAnonymousUser
    void testGet_Anonymous_Unauthorized() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithAnonymousUser
    void testPost_Anonymous_Unauthorized() throws Exception {
        mockMvc.perform(post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"send_password_create\":true}"))
                .andExpect(status().isUnauthorized());
    }
}
