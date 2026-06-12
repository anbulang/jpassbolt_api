package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for SettingsController (GET /settings.json).
 *
 * <p>
 * Negative-case note: this endpoint is public, so there is no 403 path. The
 * PHP 404 behavior only exists for non-.json requests (assertJson), which the
 * Java port does not replicate — both /settings and /settings.json are
 * registered, per the project-wide dual-registration convention. Negative
 * coverage for this cluster is therefore: anonymous reduced view (no
 * privileged data leak), invalid contain key (400) and garbage Bearer token
 * (silent guest fallback, never 401/500).
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class SettingsControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private OrganizationSettingRepository organizationSettingRepository;

        @Autowired
        private UserRepository userRepository;

        @Autowired
        private RoleRepository roleRepository;

        @Autowired
        private ObjectMapper objectMapper;

        private User testUser;

        @BeforeEach
        void setUp() {
                // organization_settings has no FK dependencies — order is free.
                organizationSettingRepository.deleteAll();
                userRepository.deleteAll();

                testUser = new User();
                testUser.setUsername("test@example.com");
                testUser.setRoleId("user");
                testUser.setActive(true);
                testUser.setDeleted(false);
                userRepository.save(testUser);
        }

        /**
         * Helper to insert an organization setting. property_id, created_by and
         * modified_by are all NOT NULL; property_id is just a random UUID here
         * (the Java implementation queries by property name, never by the PHP
         * UUIDv5-derived property_id).
         */
        private OrganizationSetting createOrgSetting(String property, String value) {
                OrganizationSetting setting = new OrganizationSetting();
                setting.setPropertyId(UUID.randomUUID().toString());
                setting.setProperty(property);
                setting.setValue(value);
                setting.setCreatedBy(UUID.randomUUID().toString());
                setting.setModifiedBy(UUID.randomUUID().toString());
                return organizationSettingRepository.save(setting);
        }

        @Test
        void testIndexAuthenticated() throws Exception {
                mockMvc.perform(get("/settings.json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.app.url").exists())
                                // No locale row seeded -> default fallback.
                                .andExpect(jsonPath("$.body.app.locale").value("en-UK"))
                                .andExpect(jsonPath("$.body.app.version.number").value("5.7.2"))
                                .andExpect(jsonPath("$.body.app.server_timezone").exists())
                                .andExpect(jsonPath("$.body.app.session_timeout").exists())
                                .andExpect(jsonPath("$.body.passbolt.edition").value("ce"))
                                .andExpect(jsonPath("$.body.passbolt.legal.terms.url").exists())
                                .andExpect(jsonPath("$.body.passbolt.plugins.jwtAuthentication.enabled").value(true))
                                .andExpect(jsonPath("$.body.passbolt.plugins.locale.options").isArray())
                                .andExpect(jsonPath("$.body.passbolt.plugins.rememberMe.options['300']").exists());
        }

        /**
         * Anonymous callers are legitimate (the browser extension probes this
         * endpoint before login): expect 200 with the reduced guest view, NOT
         * 401/403 — and no leak of version/capability information.
         */
        @Test
        @WithAnonymousUser
        void testIndexAnonymousReturnsReducedView() throws Exception {
                mockMvc.perform(get("/settings.json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.app.url").exists())
                                .andExpect(jsonPath("$.body.passbolt.edition").value("ce"))
                                .andExpect(jsonPath("$.body.passbolt.legal.terms.url").exists())
                                .andExpect(jsonPath("$.body.passbolt.plugins.locale.options").isArray())
                                .andExpect(jsonPath("$.body.passbolt.plugins.rememberMe.options['300']").exists())
                                // Authenticated-only keys must NOT leak to guests.
                                .andExpect(jsonPath("$.body.app.version").doesNotExist())
                                .andExpect(jsonPath("$.body.app.server_timezone").doesNotExist())
                                .andExpect(jsonPath("$.body.app.session_timeout").doesNotExist())
                                .andExpect(jsonPath("$.body.passbolt.plugins.jwtAuthentication").doesNotExist());
        }

        @Test
        void testIndexWithoutJsonExtension() throws Exception {
                mockMvc.perform(get("/settings"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.app.url").exists());
        }

        /**
         * The extension often calls /settings.json with an expired or invalid
         * Bearer token (capability probing after token expiry). The JWT filter
         * must swallow the parse error and the endpoint must degrade to the
         * guest view — never 401 or 500.
         */
        @Test
        @WithAnonymousUser
        void testIndexWithInvalidBearerFallsBackToGuest() throws Exception {
                mockMvc.perform(get("/settings.json")
                                .header("Authorization", "Bearer garbage"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.app.url").exists())
                                .andExpect(jsonPath("$.body.app.version").doesNotExist())
                                .andExpect(jsonPath("$.body.passbolt.plugins.jwtAuthentication").doesNotExist());
        }

        /**
         * contain[header]=0 — PHP $withHeader=false branch: no {header, body}
         * envelope, settings keys at the top level.
         */
        @Test
        void testContainHeaderZeroOmitsEnvelope() throws Exception {
                mockMvc.perform(get("/settings.json")
                                .param("contain[header]", "0"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header").doesNotExist())
                                .andExpect(jsonPath("$.app.url").exists())
                                .andExpect(jsonPath("$.passbolt.edition").value("ce"));
        }

        @Test
        void testContainHeaderOneKeepsEnvelope() throws Exception {
                mockMvc.perform(get("/settings.json")
                                .param("contain[header]", "1"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body.app.url").exists());
        }

        /**
         * Any contain key other than header is rejected, like PHP's
         * QueryStringComponent (BadRequestException "Invalid contain.").
         */
        @Test
        void testInvalidContainKeyReturns400() throws Exception {
                mockMvc.perform(get("/settings.json")
                                .param("contain[foo]", "1"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message").value("Invalid contain."));
        }

        @Test
        void testLocaleFromOrganizationSettings() throws Exception {
                createOrgSetting("locale", "fr-FR");

                mockMvc.perform(get("/settings.json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.app.locale").value("fr-FR"));
        }

        @Test
        void testLocaleFallsBackToDefaultWhenAbsent() throws Exception {
                // Table cleared in setUp() — no locale row present.
                mockMvc.perform(get("/settings.json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.app.locale").value("en-UK"));
        }
}
