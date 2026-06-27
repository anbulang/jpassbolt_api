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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link PasswordPoliciesController}
 * (GET /password-policies/settings.json): the CE defaults projection and the
 * authenticated-only contract (anonymous → 401). In CE the settings are always
 * the built-in defaults with {@code source = "default"} (no storage, no admin
 * gate), so the GET asserts the constant default values straight from
 * {@link com.jpassbolt.api.service.PasswordPoliciesService}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "policies-user@example.com", roles = { "USER" })
class PasswordPoliciesControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
    void setUp() {
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

        User testUser = new User();
        testUser.setUsername("policies-user@example.com");
        testUser.setRoleId(userRole.getId());
        testUser.setActive(true);
        testUser.setDeleted(false);
        userRepository.save(testUser);
    }

    @Test
    void testGet_ReturnsCeDefaults() throws Exception {
        mockMvc.perform(get("/password-policies/settings.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.message").value("The operation was successful."))
                .andExpect(jsonPath("$.body.default_generator").value("password"))
                .andExpect(jsonPath("$.body.external_dictionary_check").value(true))
                .andExpect(jsonPath("$.body.source").value("default"))
                // password generator defaults
                .andExpect(jsonPath("$.body.password_generator_settings.length").value(18))
                .andExpect(jsonPath("$.body.password_generator_settings.mask_upper").value(true))
                .andExpect(jsonPath("$.body.password_generator_settings.mask_lower").value(true))
                .andExpect(jsonPath("$.body.password_generator_settings.mask_digit").value(true))
                .andExpect(jsonPath("$.body.password_generator_settings.mask_parenthesis").value(true))
                .andExpect(jsonPath("$.body.password_generator_settings.mask_emoji").value(false))
                .andExpect(jsonPath("$.body.password_generator_settings.mask_char1").value(true))
                .andExpect(jsonPath("$.body.password_generator_settings.mask_char5").value(true))
                .andExpect(jsonPath("$.body.password_generator_settings.exclude_look_alike_chars").value(true))
                // passphrase generator defaults
                .andExpect(jsonPath("$.body.passphrase_generator_settings.words").value(9))
                .andExpect(jsonPath("$.body.passphrase_generator_settings.word_separator").value(" "))
                .andExpect(jsonPath("$.body.passphrase_generator_settings.word_case").value("lowercase"));
    }

    @Test
    void testGet_BareTrailing_AlsoWorks() throws Exception {
        mockMvc.perform(get("/password-policies/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.default_generator").value("password"));
    }

    @Test
    @WithAnonymousUser
    void testGet_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/password-policies/settings.json"))
                .andExpect(status().isUnauthorized());
    }
}
