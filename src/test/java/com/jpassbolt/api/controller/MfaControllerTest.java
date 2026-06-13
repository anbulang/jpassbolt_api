package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jpassbolt.api.model.AccountSetting;
import com.jpassbolt.api.model.AuthenticationToken;
import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AccountSettingRepository;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.TotpService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the MFA endpoints (MfaController) and the
 * enforcement gate (MfaEnforcementFilter).
 *
 * <p>
 * The gate is exercised through the regular MockMvc chain
 * (@AutoConfigureMockMvc registers the servlet filters, and the
 * SecurityContext populated by @WithMockUser is visible inside the filter).
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class MfaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TotpService totpService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AccountSettingRepository accountSettingRepository;

    @Autowired
    private OrganizationSettingRepository organizationSettingRepository;

    @Autowired
    private AuthenticationTokenRepository authenticationTokenRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private GpgKeyRepository gpgKeyRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private Role userRole;
    private Role adminRole;
    private User testUser;
    private User adminUser;

    @BeforeEach
    void setUp() {
        // Reverse FK order cleanup (account/org settings have no FK but must
        // not leak between tests of this class)
        accountSettingRepository.deleteAll();
        organizationSettingRepository.deleteAll();
        authenticationTokenRepository.deleteAll();
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        profileRepository.deleteAll();
        gpgKeyRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        userRole = createRole("user");
        adminRole = createRole("admin");
        testUser = createUser("test@example.com", userRole.getId());
        adminUser = createUser("admin@example.com", adminRole.getId());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Role createRole(String name) {
        Role role = new Role();
        role.setName(name);
        return roleRepository.save(role);
    }

    private User createUser(String username, String roleId) {
        User user = new User();
        user.setUsername(username);
        user.setRoleId(roleId);
        user.setActive(true);
        user.setDeleted(false);
        return userRepository.save(user);
    }

    private void seedOrgMfa(String... providers) {
        OrganizationSetting setting = new OrganizationSetting();
        setting.setProperty("mfa");
        setting.setPropertyId(UUID.randomUUID().toString());
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode array = root.putArray("providers");
        for (String provider : providers) {
            array.add(provider);
        }
        setting.setValue(root.toString());
        setting.setCreatedBy(adminUser.getId());
        setting.setModifiedBy(adminUser.getId());
        organizationSettingRepository.save(setting);
    }

    /** Seed a complete (ready) TOTP account setting; returns the uri. */
    private String seedUserTotp(User user) {
        String secret = totpService.generateSecret();
        String uri = totpService.buildProvisioningUri("test-issuer", user.getUsername(), secret);
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("providers").add("totp");
        ObjectNode totp = root.putObject("totp");
        totp.put("otpProvisioningUri", uri);
        totp.put("verified", "2026-01-01T00:00:00");

        AccountSetting setting = new AccountSetting();
        setting.setUserId(user.getId());
        setting.setProperty("mfa");
        setting.setPropertyId(UUID.randomUUID().toString());
        setting.setValue(root.toString());
        accountSettingRepository.save(setting);
        return uri;
    }

    /** Create an mfa verified token row and return it as a request cookie. */
    private Cookie mfaCookie(User user, boolean active) {
        AuthenticationToken token = new AuthenticationToken();
        token.setUserId(user.getId());
        token.setToken(UUID.randomUUID().toString());
        token.setType("mfa");
        token.setActive(active);
        token.setData("{\"provider\":\"totp\",\"user_agent\":\"test\",\"remember\":false}");
        authenticationTokenRepository.save(token);
        return new Cookie("passbolt_mfa", token.getToken());
    }

    private List<AuthenticationToken> mfaTokensOf(User user) {
        return authenticationTokenRepository.findAll().stream()
                .filter(t -> "mfa".equals(t.getType()) && t.getUserId().equals(user.getId()))
                .toList();
    }

    // ------------------------------------------------------------------
    // GET /mfa/verify/{provider}.json
    // ------------------------------------------------------------------

    @Test
    void testVerifyGet_TotpPending_ReturnsNullBody() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);

        mockMvc.perform(get("/mfa/verify/totp.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.message").value("Please provide the one-time password."))
                .andExpect(jsonPath("$.body").value(nullValue()));
    }

    @Test
    void testVerifyGet_NoUserSettings_BadRequest() throws Exception {
        seedOrgMfa("totp");

        mockMvc.perform(get("/mfa/verify/totp.json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.message")
                        .value("No valid multi-factor authentication settings found."));
    }

    @Test
    void testVerifyGet_OrgDisabled_BadRequest() throws Exception {
        // no organization_settings row at all
        seedUserTotp(testUser);

        mockMvc.perform(get("/mfa/verify/totp.json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testVerifyGet_WithValidCookie_NotRequired() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);

        mockMvc.perform(get("/mfa/verify/totp.json").cookie(mfaCookie(testUser, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message")
                        .value("The multi-factor authentication is not required."));
    }

    @Test
    void testVerifyGet_YubikeyNotReady_BadRequest() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);

        mockMvc.perform(get("/mfa/verify/yubikey.json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message").value(
                        "No valid multi-factor authentication settings found for this provider."));
    }

    @Test
    void testVerifyGet_UnknownProvider_BadRequest() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);

        mockMvc.perform(get("/mfa/verify/garbage.json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    // ------------------------------------------------------------------
    // POST /mfa/verify/{provider}.json
    // ------------------------------------------------------------------

    @Test
    void testVerifyPost_ValidCode_SetsCookieAndToken() throws Exception {
        seedOrgMfa("totp");
        String uri = seedUserTotp(testUser);
        String code = totpService.generateCurrentCode(uri);

        mockMvc.perform(post("/mfa/verify/totp.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totp\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.message")
                        .value("The multi-factor authentication was a success."))
                .andExpect(jsonPath("$.body").value(nullValue()))
                .andExpect(cookie().exists("passbolt_mfa"))
                .andExpect(cookie().httpOnly("passbolt_mfa", true))
                // no remember: session cookie, no Max-Age
                .andExpect(cookie().maxAge("passbolt_mfa", -1));

        List<AuthenticationToken> tokens = mfaTokensOf(testUser);
        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0).getActive()).isTrue();
        JsonNode data = objectMapper.readTree(tokens.get(0).getData());
        assertThat(data.path("provider").asText()).isEqualTo("totp");
        assertThat(data.path("remember").asBoolean()).isFalse();
    }

    @Test
    void testVerifyPost_Remember_SetsThirtyDayCookie() throws Exception {
        seedOrgMfa("totp");
        String uri = seedUserTotp(testUser);
        String code = totpService.generateCurrentCode(uri);

        mockMvc.perform(post("/mfa/verify/totp.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totp\":\"" + code + "\",\"remember\":1}"))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("passbolt_mfa", 30 * 24 * 3600));

        JsonNode data = objectMapper.readTree(mfaTokensOf(testUser).get(0).getData());
        assertThat(data.path("remember").asBoolean()).isTrue();
    }

    @Test
    void testVerifyPost_WrongCode_IsValidOtpError() throws Exception {
        seedOrgMfa("totp");
        String uri = seedUserTotp(testUser);
        String code = totpService.generateCurrentCode(uri);
        String wrong = code.equals("111111") ? "222222" : "111111";

        mockMvc.perform(post("/mfa/verify/totp.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totp\":\"" + wrong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message")
                        .value("Something went wrong when validating the one-time password."))
                .andExpect(jsonPath("$.body.totp.isValidOtp").value("This OTP is not valid."));
    }

    @Test
    void testVerifyPost_NonNumeric_NumericError() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);

        mockMvc.perform(post("/mfa/verify/totp.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totp\":\"abc123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.totp.numeric")
                        .value("The OTP should be composed of numbers only."));
    }

    @Test
    void testVerifyPost_TooShort_MinLengthError() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);

        mockMvc.perform(post("/mfa/verify/totp.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totp\":\"12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.totp.minLength")
                        .value("The OTP should be at least 6 characters long."));
    }

    @Test
    void testVerifyPost_MissingTotp_RequiredError() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);

        mockMvc.perform(post("/mfa/verify/totp.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.totp._required").value("An OTP is required."));
    }

    @Test
    void testVerifyPost_EmptyTotp_EmptyError() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);

        mockMvc.perform(post("/mfa/verify/totp.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totp\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.totp._empty").value("The OTP should not be empty."));
    }

    @Test
    void testVerifyPost_UserNotConfigured_NoTokenWritten() throws Exception {
        seedOrgMfa("totp");
        // no account settings for the user

        mockMvc.perform(post("/mfa/verify/totp.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totp\":\"123456\"}"))
                .andExpect(status().isBadRequest());

        assertThat(mfaTokensOf(testUser)).isEmpty();
    }

    @Test
    void testVerifyPost_WithValidCookie_NotRequired() throws Exception {
        seedOrgMfa("totp");
        String uri = seedUserTotp(testUser);
        String code = totpService.generateCurrentCode(uri);

        mockMvc.perform(post("/mfa/verify/totp.json")
                .cookie(mfaCookie(testUser, true))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"totp\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message")
                        .value("The multi-factor authentication is not required."));
    }

    // ------------------------------------------------------------------
    // /mfa/verify/error.json
    // ------------------------------------------------------------------

    @Test
    void testVerifyError_ConfiguredUser_403WithProviders() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);

        mockMvc.perform(get("/mfa/verify/error.json"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.code").value(403))
                .andExpect(jsonPath("$.header.message").value("MFA authentication is required."))
                .andExpect(jsonPath("$.body.mfa_providers[0]").value("totp"))
                // stale cookie is cleared
                .andExpect(cookie().maxAge("passbolt_mfa", 0));
    }

    @Test
    void testVerifyError_AllFourMethodsMapped() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);

        mockMvc.perform(post("/mfa/verify/error.json")).andExpect(status().isForbidden());
        mockMvc.perform(put("/mfa/verify/error.json")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/mfa/verify/error.json")).andExpect(status().isForbidden());
    }

    @Test
    void testVerifyError_UnconfiguredUser_EmptyProviders() throws Exception {
        mockMvc.perform(get("/mfa/verify/error.json"))
                .andExpect(status().isForbidden())
                // not swallowed by the {mfaProviderName} template (which
                // answers 400 with a different message)
                .andExpect(jsonPath("$.header.message").value("MFA authentication is required."))
                .andExpect(jsonPath("$.body.mfa_providers").isArray())
                .andExpect(jsonPath("$.body.mfa_providers").isEmpty());
    }

    // ------------------------------------------------------------------
    // Enforcement gate (MfaEnforcementFilter)
    // ------------------------------------------------------------------

    @Test
    void testGate_MfaPending_RedirectsToErrorEndpoint() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);

        mockMvc.perform(get("/resources.json"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", endsWith("/mfa/verify/error.json")));
    }

    @Test
    void testGate_ValidCookie_Passes() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);

        mockMvc.perform(get("/resources.json").cookie(mfaCookie(testUser, true)))
                .andExpect(status().isOk());
    }

    @Test
    void testGate_InactiveToken_StillBlocked() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);
        Cookie cookie = mfaCookie(testUser, false);

        mockMvc.perform(get("/resources.json").cookie(cookie))
                .andExpect(status().isFound());

        AuthenticationToken token = authenticationTokenRepository
                .findByToken(cookie.getValue()).orElseThrow();
        assertThat(token.getActive()).isFalse();
    }

    @Test
    void testGate_ExpiredToken_BlockedAndDeactivated() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);
        Cookie cookie = mfaCookie(testUser, true);
        // created is updatable=false and overwritten by @PrePersist — age
        // the row directly in SQL
        jdbcTemplate.update("UPDATE authentication_tokens SET created = ? WHERE token = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(31)), cookie.getValue());

        mockMvc.perform(get("/resources.json").cookie(cookie))
                .andExpect(status().isFound());

        AuthenticationToken token = authenticationTokenRepository
                .findByToken(cookie.getValue()).orElseThrow();
        assertThat(token.getActive()).isFalse();
    }

    @Test
    void testGate_VerifyEndpointWhitelisted() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);

        mockMvc.perform(get("/mfa/verify/totp.json"))
                .andExpect(status().isOk());
    }

    @Test
    void testGate_SetupNotWhitelisted() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);

        mockMvc.perform(get("/mfa/setup/totp.json"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", endsWith("/mfa/verify/error.json")));
    }

    @Test
    void testGate_NoMfaConfigured_NeverBlocks() throws Exception {
        mockMvc.perform(get("/resources.json"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // /mfa/setup/totp.json
    // ------------------------------------------------------------------

    @Test
    void testSetupGet_Unconfigured_ReturnsProvisioningUri() throws Exception {
        seedOrgMfa("totp");

        MvcResult result = mockMvc.perform(get("/mfa/setup/totp.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.message").value("Please setup the TOTP application."))
                .andExpect(jsonPath("$.body.otpQrCodeSvg").value(""))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString()).path("body");
        String uri = body.path("otpProvisioningUri").asText();
        assertThat(uri).startsWith("otpauth://totp/");
        // must be parseable; nothing is persisted by the GET
        totpService.parseProvisioningUri(uri);
        assertThat(accountSettingRepository
                .findFirstByUserIdAndProperty(testUser.getId(), "mfa")).isEmpty();
    }

    @Test
    void testSetupGet_AlreadyConfigured_ReturnsVerified() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);

        mockMvc.perform(get("/mfa/setup/totp.json").cookie(mfaCookie(testUser, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.message")
                        .value("Multi Factor Authentication is configured!"))
                .andExpect(jsonPath("$.body.verified").isNotEmpty());
    }

    @Test
    void testSetupGet_OrgDisabled_BadRequest() throws Exception {
        mockMvc.perform(get("/mfa/setup/totp.json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message")
                        .value("This authentication provider is not enabled for your organization."));
    }

    @Test
    void testSetupStart_Unconfigured() throws Exception {
        seedOrgMfa("totp");

        mockMvc.perform(get("/mfa/setup/totp/start.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.message").value("Please setup the TOTP application."))
                .andExpect(jsonPath("$.body").value(nullValue()));
    }

    @Test
    void testSetupStart_Configured() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);

        mockMvc.perform(get("/mfa/setup/totp/start.json").cookie(mfaCookie(testUser, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.verified").isNotEmpty());
    }

    @Test
    void testSetupPost_ValidUriAndCode_PersistsSettings() throws Exception {
        seedOrgMfa("totp");
        String secret = totpService.generateSecret();
        String uri = totpService.buildProvisioningUri("test-issuer", "test@example.com", secret);
        String code = totpService.generateCurrentCode(uri);

        mockMvc.perform(post("/mfa/setup/totp.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        java.util.Map.of("otpProvisioningUri", uri, "totp", code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.message")
                        .value("Multi Factor Authentication is configured!"))
                .andExpect(jsonPath("$.body.verified").isNotEmpty())
                .andExpect(cookie().exists("passbolt_mfa"))
                // setup cookie is session scoped
                .andExpect(cookie().maxAge("passbolt_mfa", -1));

        AccountSetting setting = accountSettingRepository
                .findFirstByUserIdAndProperty(testUser.getId(), "mfa").orElseThrow();
        JsonNode json = objectMapper.readTree(setting.getValue());
        assertThat(json.path("providers").get(0).asText()).isEqualTo("totp");
        assertThat(json.path("totp").path("otpProvisioningUri").asText()).isEqualTo(uri);
        assertThat(json.path("totp").path("verified").asText()).isNotEmpty();
    }

    @Test
    void testSetupPost_WrongCode_IsValidOtpError() throws Exception {
        seedOrgMfa("totp");
        String secret = totpService.generateSecret();
        String uri = totpService.buildProvisioningUri("test-issuer", "test@example.com", secret);
        String code = totpService.generateCurrentCode(uri);
        String wrong = code.equals("111111") ? "222222" : "111111";

        mockMvc.perform(post("/mfa/setup/totp.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        java.util.Map.of("otpProvisioningUri", uri, "totp", wrong))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.totp.isValidOtp").value("This OTP is not valid."));

        assertThat(accountSettingRepository
                .findFirstByUserIdAndProperty(testUser.getId(), "mfa")).isEmpty();
    }

    @Test
    void testSetupPost_InvalidUri_UriError() throws Exception {
        seedOrgMfa("totp");

        mockMvc.perform(post("/mfa/setup/totp.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        java.util.Map.of("otpProvisioningUri", "http://not-otpauth", "totp", "123456"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message")
                        .value("Something went wrong when validating the one-time password."))
                .andExpect(jsonPath("$.body.otpProvisioningUri.isValidOtpProvisioningUri")
                        .value("This OTP provision uri is not valid."));
    }

    @Test
    void testSetupPost_AlreadyConfigured_BadRequest() throws Exception {
        seedOrgMfa("totp");
        String uri = seedUserTotp(testUser);
        String code = totpService.generateCurrentCode(uri);

        mockMvc.perform(post("/mfa/setup/totp.json")
                .cookie(mfaCookie(testUser, true))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        java.util.Map.of("otpProvisioningUri", uri, "totp", code))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message")
                        .value("This authentication provider is already setup. Disable it first"));
    }

    @Test
    void testSetupDelete_Configured_DeletesRowAndTokens() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(testUser);
        Cookie cookie = mfaCookie(testUser, true);

        mockMvc.perform(delete("/mfa/setup/totp.json").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.message").value("The configuration was deleted."))
                .andExpect(jsonPath("$.body").value(nullValue()));

        assertThat(accountSettingRepository
                .findFirstByUserIdAndProperty(testUser.getId(), "mfa")).isEmpty();
        assertThat(mfaTokensOf(testUser)).isNotEmpty()
                .allMatch(token -> !token.getActive());
    }

    @Test
    void testSetupDelete_NothingToDelete_Idempotent() throws Exception {
        mockMvc.perform(delete("/mfa/setup/totp.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.message")
                        .value("No configuration found for this provider. Nothing to delete."));
    }

    // ------------------------------------------------------------------
    // /mfa/settings.json (admins only)
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "admin@example.com", roles = { "USER" })
    void testOrgSettingsGet_Admin_Defaults() throws Exception {
        mockMvc.perform(get("/mfa/settings.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.providers").isArray())
                .andExpect(jsonPath("$.body.providers").isEmpty());
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = { "USER" })
    void testOrgSettingsPost_Admin_EnablesTotp() throws Exception {
        mockMvc.perform(post("/mfa/settings.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"providers\":[\"totp\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.message").value(
                        "The multi factor authentication settings for the organization were updated."))
                .andExpect(jsonPath("$.body.providers[0]").value("totp"));

        OrganizationSetting setting = organizationSettingRepository.findByProperty("mfa").orElseThrow();
        assertThat(setting.getValue()).contains("totp");
        assertThat(setting.getCreatedBy()).isEqualTo(adminUser.getId());
        assertThat(setting.getModifiedBy()).isEqualTo(adminUser.getId());
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = { "USER" })
    void testOrgSettingsPut_DisableAll_ReleasesGate() throws Exception {
        seedOrgMfa("totp");
        seedUserTotp(adminUser);

        // gate is on for the admin while MFA verification is pending
        mockMvc.perform(get("/resources.json"))
                .andExpect(status().isFound());

        // /mfa/settings is NOT whitelisted (PHP parity): a valid mfa cookie
        // is needed to reach it
        mockMvc.perform(put("/mfa/settings.json")
                .cookie(mfaCookie(adminUser, true))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"providers\":[]}"))
                .andExpect(status().isOk());

        // org-wide MFA is now off: the gate no longer blocks
        mockMvc.perform(get("/resources.json"))
                .andExpect(status().isOk());
    }

    @Test
    void testOrgSettingsGet_NonAdmin_Forbidden() throws Exception {
        mockMvc.perform(get("/mfa/settings.json"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testOrgSettingsPost_NonAdmin_Forbidden() throws Exception {
        mockMvc.perform(post("/mfa/settings.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"providers\":[\"totp\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = { "USER" })
    void testOrgSettingsPost_UnsupportedProvider_BadRequest() throws Exception {
        mockMvc.perform(post("/mfa/settings.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"providers\":[\"duo\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.message")
                        .value("This authentication provider is not supported."));
    }

    @Test
    @WithMockUser(username = "admin@example.com", roles = { "USER" })
    void testOrgSettingsPost_MissingProviders_BadRequest() throws Exception {
        mockMvc.perform(post("/mfa/settings.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
