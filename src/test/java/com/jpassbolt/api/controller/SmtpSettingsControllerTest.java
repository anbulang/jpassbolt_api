package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the admin SMTP settings endpoints
 * (GET/POST/PUT /smtp/settings.json, POST /smtp/email.json).
 *
 * <p>Per-method {@code @WithMockUser} (no class-level) so the unauthenticated
 * boundary test can run anonymously. Not {@code @Transactional}: POST/save commits
 * the encrypted org-setting row, which {@code @BeforeEach} clears before each test.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SmtpSettingsControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private OrganizationSettingRepository organizationSettingRepository;

    @BeforeEach
    void setUp() {
        organizationSettingRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role adminRole = new Role();
        adminRole.setName(Role.ADMIN);
        adminRole = roleRepository.save(adminRole);
        User admin = new User();
        admin.setUsername("admin@passbolt.com");
        admin.setRoleId(adminRole.getId());
        admin.setActive(true);
        admin.setDeleted(false);
        userRepository.save(admin);

        Role userRole = new Role();
        userRole.setName(Role.USER);
        userRole = roleRepository.save(userRole);
        User regular = new User();
        regular.setUsername("user@passbolt.com");
        regular.setRoleId(userRole.getId());
        regular.setActive(true);
        regular.setDeleted(false);
        userRepository.save(regular);
    }

    private Map<String, Object> validSettings() {
        Map<String, Object> m = new HashMap<>();
        m.put("sender_name", "JPassbolt");
        m.put("sender_email", "no-reply@passbolt.com");
        m.put("host", "smtp.passbolt.com");
        m.put("tls", true);
        m.put("port", 587);
        m.put("username", "smtp-user");
        m.put("password", "s3cr3t-smtp-pw");
        return m;
    }

    // ---- GET ----

    @Test
    @WithMockUser(username = "admin@passbolt.com", roles = { "ADMIN" })
    void getSettings_returnsEnvelopeWithSource() throws Exception {
        mockMvc.perform(get("/smtp/settings.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.code").value(200))
                .andExpect(jsonPath("$.header.url").value("/smtp/settings.json"))
                .andExpect(jsonPath("$.body.source").value("undefined"));
    }

    // ---- POST / PUT ----

    @Test
    @WithMockUser(username = "admin@passbolt.com", roles = { "ADMIN" })
    void postSettings_persistsEncryptedAndReturnsDbSource() throws Exception {
        mockMvc.perform(post("/smtp/settings.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validSettings())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.source").value("db"))
                .andExpect(jsonPath("$.body.host").value("smtp.passbolt.com"))
                .andExpect(jsonPath("$.body.password").value("s3cr3t-smtp-pw"))
                .andExpect(jsonPath("$.body.id").exists());

        // Secret is encrypted at rest, not stored in cleartext.
        String stored = organizationSettingRepository.findByProperty("smtp").orElseThrow().getValue();
        assertThat(stored).startsWith("-----BEGIN PGP MESSAGE-----");
        assertThat(stored).doesNotContain("s3cr3t-smtp-pw");
    }

    @Test
    @WithMockUser(username = "admin@passbolt.com", roles = { "ADMIN" })
    void putSettings_alsoPersists() throws Exception {
        mockMvc.perform(put("/smtp/settings.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(validSettings())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.source").value("db"));
    }

    @Test
    @WithMockUser(username = "admin@passbolt.com", roles = { "ADMIN" })
    void postSettings_invalid_returns400WithFieldErrors() throws Exception {
        Map<String, Object> bad = new HashMap<>();
        bad.put("sender_name", "JPassbolt");
        // missing sender_email, host, port
        mockMvc.perform(post("/smtp/settings.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(400))
                .andExpect(jsonPath("$.body.host").exists())
                .andExpect(jsonPath("$.body.port").exists());
    }

    // ---- test email ----

    @Test
    @WithMockUser(username = "admin@passbolt.com", roles = { "ADMIN" })
    void testEmail_missingRecipient_returns400() throws Exception {
        Map<String, Object> req = validSettings();
        // no email_test_to
        mockMvc.perform(post("/smtp/email.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.body.email_test_to").exists());
    }

    @Test
    @WithMockUser(username = "admin@passbolt.com", roles = { "ADMIN" })
    void testEmail_unreachableHost_returns400WithDebug() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("sender_name", "JPassbolt");
        req.put("sender_email", "no-reply@passbolt.com");
        req.put("host", "127.0.0.1");
        req.put("port", 9); // discard port — connection refused
        req.put("tls", false);
        req.put("email_test_to", "admin@passbolt.com");
        mockMvc.perform(post("/smtp/email.json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.code").value(400))
                .andExpect(jsonPath("$.body.debug").exists());
    }

    // ---- authorization boundary ----

    @Test
    @WithMockUser(username = "user@passbolt.com", roles = { "USER" })
    void getSettings_nonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/smtp/settings.json"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.code").value(403));
    }

    @Test
    void getSettings_unauthenticated_returns401() throws Exception {
        // Must stay behind anyRequest().authenticated() — never whitelisted.
        mockMvc.perform(get("/smtp/settings.json"))
                .andExpect(status().isUnauthorized());
    }
}
