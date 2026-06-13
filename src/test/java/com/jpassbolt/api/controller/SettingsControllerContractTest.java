package com.jpassbolt.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI contract tests for GET /settings.json.
 *
 * <p>
 * The spec copy in src/test/resources/plugin-redoc-0.yaml defines the
 * /settings.json operation (L5589, operationId indexSettings) and the
 * settings_index response (L11209) with body schema "index" (required keys:
 * app, passbolt; everything else optional and no additionalProperties:false,
 * so both the guest reduced view and the richer authenticated view satisfy
 * the contract).
 * </p>
 *
 * <p>
 * The openApi().isValid(...) assertions are enabled: the shared
 * {@link com.jpassbolt.api.util.ApiResponse} envelope emits the required header
 * "action" (uuid) and integer "servertime" fields, so the response satisfies
 * the spec's header schema.
 * </p>
 */
public class SettingsControllerContractTest extends OpenApiComplianceTest {

    @Test
    @WithMockUser(username = "test@example.com", roles = { "USER" })
    public void testSettingsIndexContract() throws Exception {
        mockMvc.perform(get("/settings.json")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.app.url").exists())
                .andExpect(jsonPath("$.body.passbolt.edition").exists())
                .andExpect(jsonPath("$.body.passbolt.plugins.jwtAuthentication.enabled").value(true))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    /**
     * Guest reduced view still satisfies the index schema: only app and
     * passbolt are required, all nested properties are optional.
     */
    @Test
    @WithAnonymousUser
    public void testSettingsIndexAnonymousContract() throws Exception {
        mockMvc.perform(get("/settings.json")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.app.url").exists())
                .andExpect(jsonPath("$.body.passbolt.edition").exists())
                .andExpect(jsonPath("$.body.app.version").doesNotExist())
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }
}
