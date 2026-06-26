package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AccountSettingRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.repository.RoleRepository;
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
 * Contract tests for the account-locale endpoints
 * (POST/GET /account/settings/locales.json).
 *
 * <p>
 * The {@code openApi().isValid(CONTRACT_VALIDATOR)} assertion is deliberately
 * NOT used here: {@code /account/settings/locales} has no path entry in
 * plugin-redoc-0.yaml (the PHP Locale plugin route is outside the OpenAPI
 * domain that the spec describes — same situation as MfaController's
 * setup/totp and settings.json endpoints, which MfaControllerContractTest
 * also covers without isValid). Running the validator against an undeclared
 * path would fail with {@code validation.request.path.missing} rather than
 * report a real envelope deviation. The response shape is still asserted to
 * be the standard {@link com.jpassbolt.api.util.ApiResponse} envelope
 * (header.status/message/code + body) via jsonPath, which is the meaningful
 * compatibility check for this endpoint.
 * </p>
 */
@WithMockUser(username = "locale-contract@example.com", roles = { "USER" })
public class AccountLocaleControllerContractTest extends OpenApiComplianceTest {

    @Autowired
    private AccountSettingRepository accountSettingRepository;

    @Autowired
    private OrganizationSettingRepository organizationSettingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @BeforeEach
    void seedData() {
        accountSettingRepository.deleteAll();
        organizationSettingRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role userRole = new Role();
        userRole.setName("user");
        userRole = roleRepository.save(userRole);

        User user = new User();
        user.setUsername("locale-contract@example.com");
        user.setRoleId(userRole.getId());
        user.setActive(true);
        user.setDeleted(false);
        userRepository.save(user);
    }

    @Test
    void postLocale_returnsStandardEnvelope() throws Exception {
        mockMvc.perform(post("/account/settings/locales.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"zh-CN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.id").exists())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.code").value(200))
                .andExpect(jsonPath("$.header.action").exists())
                .andExpect(jsonPath("$.header.servertime").exists())
                .andExpect(jsonPath("$.header.url").value("/account/settings/locales.json"))
                .andExpect(jsonPath("$.body.value").value("zh-CN"));
    }

    @Test
    void postInvalidLocale_returnsErrorEnvelope() throws Exception {
        mockMvc.perform(post("/account/settings/locales.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"xx-XX\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.code").value(400))
                .andExpect(jsonPath("$.header.message").value("This is not a valid locale."));
    }

    @Test
    void getLocale_returnsStandardEnvelope() throws Exception {
        mockMvc.perform(get("/account/settings/locales.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.code").value(200))
                .andExpect(jsonPath("$.body.value").exists());
    }
}
