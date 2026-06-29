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

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for the admin self-registration settings endpoints
 * (GET/POST/DELETE /self-registration/settings.json).
 *
 * <p>{@code openApi().isValid(CONTRACT_VALIDATOR)} is deliberately NOT asserted on
 * the {@code /self-registration/*} responses: those paths have no entry in
 * plugin-redoc-0.yaml (the PHP CE SelfRegistration plugin routes are outside the
 * documented OpenAPI domain — the same situation as the email-notification settings
 * endpoints), so validating against an undeclared path would fail with
 * {@code validation.request.path.missing} rather than report a real deviation. The
 * standard {@link com.jpassbolt.api.util.ApiResponse} envelope is asserted via
 * jsonPath instead. The ONE spec-declared surface — the
 * {@code passbolt.plugins.selfRegistration.enabled} advertisement in
 * GET /settings.json — IS validated with {@code isValid}.</p>
 */
@WithMockUser(username = "selfreg-contract@example.com", roles = { "ADMIN" })
public class SelfRegistrationSettingsControllerContractTest extends OpenApiComplianceTest {

    private static final String URL = "/self-registration/settings.json";

    @Autowired private OrganizationSettingRepository organizationSettingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private CommentRepository commentRepository;
    @Autowired private FavoriteRepository favoriteRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private SecretRepository secretRepository;
    @Autowired private ResourceRepository resourceRepository;

    @BeforeEach
    void seedData() {
        organizationSettingRepository.deleteAll();
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
        User admin = new User();
        admin.setUsername("selfreg-contract@example.com");
        admin.setRoleId(adminRole.getId());
        admin.setActive(true);
        admin.setDeleted(false);
        userRepository.save(admin);

        Role userRole = new Role();
        userRole.setName(Role.USER);
        userRole = roleRepository.save(userRole);
        User regular = new User();
        regular.setUsername("selfreg-user@example.com");
        regular.setRoleId(userRole.getId());
        regular.setActive(true);
        regular.setDeleted(false);
        userRepository.save(regular);
    }

    @Test
    void getSettings_returnsStandardEnvelope() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.id").exists())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.code").value(200))
                .andExpect(jsonPath("$.header.action").exists())
                .andExpect(jsonPath("$.header.servertime").exists())
                .andExpect(jsonPath("$.header.url").value(URL))
                // default: disabled (no provider configured)
                .andExpect(jsonPath("$.body.provider").doesNotExist());
    }

    @Test
    void postSettings_persistsAndReturnsEnvelope() throws Exception {
        mockMvc.perform(post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"email_domains\",\"data\":{\"allowed_domains\":[\"passbolt.com\"]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.url").value(URL))
                .andExpect(jsonPath("$.body.provider").value("email_domains"))
                .andExpect(jsonPath("$.body.data.allowed_domains[0]").value("passbolt.com"));
    }

    @Test
    void deleteSettings_disablesSelfRegistration() throws Exception {
        // create a row first, read its id back, then delete it
        mockMvc.perform(post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"email_domains\",\"data\":{\"allowed_domains\":[\"passbolt.com\"]}}"))
                .andExpect(status().isOk());
        String id = organizationSettingRepository.findByProperty("selfRegistration").orElseThrow().getId();

        mockMvc.perform(delete("/self-registration/settings/" + id + ".json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.provider").doesNotExist());
    }

    @Test
    @WithMockUser(username = "selfreg-user@example.com", roles = { "USER" })
    void getSettings_nonAdmin_returns403() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.code").value(403));
    }

    @Test
    void settingsJson_advertisesSelfRegistrationPlugin() throws Exception {
        mockMvc.perform(get("/settings.json"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR))
                .andExpect(jsonPath("$.body.passbolt.plugins.selfRegistration.enabled").value(true));
    }
}
