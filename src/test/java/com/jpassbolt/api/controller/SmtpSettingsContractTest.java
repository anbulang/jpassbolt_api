package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for the admin SMTP settings endpoints (GET/POST /smtp/settings.json)
 * and the {@code passbolt.plugins.smtpSettings} advertisement in GET /settings.json.
 *
 * <p>{@code openApi().isValid(CONTRACT_VALIDATOR)} is deliberately NOT asserted on
 * the {@code /smtp/*} responses: plugin-redoc-0.yaml declares no {@code /smtp*}
 * path (the PHP CE SmtpSettings plugin routes are outside the documented OpenAPI
 * domain — only a {@code healthcheck.smtpSettings} sub-schema exists), so validating
 * against an undeclared path would fail with {@code validation.request.path.missing}
 * rather than report a real deviation. The standard {@link com.jpassbolt.api.util.ApiResponse}
 * envelope is asserted via jsonPath instead. The ONE spec-declared surface — the
 * {@code passbolt.plugins.smtpSettings.enabled} advertisement in GET /settings.json —
 * IS validated with {@code isValid}.</p>
 */
@WithMockUser(username = "smtp-contract@example.com", roles = { "ADMIN" })
public class SmtpSettingsContractTest extends OpenApiComplianceTest {

    private static final String URL = "/smtp/settings.json";

    @Autowired private OrganizationSettingRepository organizationSettingRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    @BeforeEach
    void seedData() {
        organizationSettingRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role adminRole = new Role();
        adminRole.setName(Role.ADMIN);
        adminRole = roleRepository.save(adminRole);
        User admin = new User();
        admin.setUsername("smtp-contract@example.com");
        admin.setRoleId(adminRole.getId());
        admin.setActive(true);
        admin.setDeleted(false);
        userRepository.save(admin);

        Role userRole = new Role();
        userRole.setName(Role.USER);
        userRole = roleRepository.save(userRole);
        User regular = new User();
        regular.setUsername("smtp-user@example.com");
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
                .andExpect(jsonPath("$.body.source").exists());
    }

    @Test
    void postSettings_persistsAndReturnsEnvelope() throws Exception {
        mockMvc.perform(post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sender_name\":\"JPassbolt\",\"sender_email\":\"no-reply@passbolt.com\","
                        + "\"host\":\"smtp.passbolt.com\",\"tls\":true,\"port\":587,"
                        + "\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.url").value(URL))
                .andExpect(jsonPath("$.body.source").value("db"))
                .andExpect(jsonPath("$.body.host").value("smtp.passbolt.com"));
    }

    @Test
    @WithMockUser(username = "smtp-user@example.com", roles = { "USER" })
    void getSettings_nonAdmin_returns403() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.code").value(403));
    }

    @Test
    void settingsJson_advertisesSmtpSettingsPlugin() throws Exception {
        mockMvc.perform(get("/settings.json"))
                .andExpect(status().isOk())
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR))
                .andExpect(jsonPath("$.body.passbolt.plugins.smtpSettings.enabled").value(true));
    }
}
