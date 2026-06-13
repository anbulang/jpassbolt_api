package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.AuthenticationToken;
import com.jpassbolt.api.model.Profile;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.AuthenticationTokenRepository;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ProfileRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.GpgService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests for the public account-setup endpoints
 * (GET /setup/start/{userId}/{tokenId}.json and
 * PUT|POST /setup/complete/{userId}.json).
 *
 * <p>
 * These endpoints exercise the main positive cases (guest start + guest
 * complete), but the {@code openApi().isValid(CONTRACT_VALIDATOR)} assertions
 * are intentionally NOT applied: the authoritative plugin-redoc-0.yaml does
 * not define any {@code /setup/...} path (the SetupController javadoc records
 * it as "outside the OpenAPI spec domain"; behaviour is aligned with the PHP
 * SetupStartController / SetupCompleteController instead). Validating these
 * responses against the spec would fail with a no-matching-operation error,
 * which is a spec-coverage gap rather than a response defect. The gap is
 * recorded in assertions_left_disabled; functional coverage of these
 * endpoints lives in SetupControllerTest.
 * </p>
 *
 * <p>
 * No class-level {@code @WithMockUser}: setup is guest-only and must be
 * reachable anonymously (SecurityConfig whitelists "/setup/**").
 * </p>
 */
public class SetupControllerContractTest extends OpenApiComplianceTest {

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

    @Autowired
    private GpgService gpgService;

    private User pendingUser;
    private AuthenticationToken registerToken;

    @BeforeEach
    void seedData() {
        authenticationTokenRepository.deleteAll();
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        profileRepository.deleteAll();
        gpgKeyRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role userRole = new Role();
        userRole.setName("user");
        roleRepository.save(userRole);

        pendingUser = new User();
        pendingUser.setUsername("betty@example.com");
        pendingUser.setRoleId(userRole.getId());
        pendingUser.setActive(false);
        pendingUser.setDeleted(false);
        userRepository.save(pendingUser);

        Profile profile = new Profile();
        profile.setUserId(pendingUser.getId());
        profile.setFirstName("Betty");
        profile.setLastName("Holberton");
        profileRepository.save(profile);

        registerToken = new AuthenticationToken();
        registerToken.setUserId(pendingUser.getId());
        registerToken.setToken(UUID.randomUUID().toString());
        registerToken.setType("register");
        registerToken.setActive(true);
        authenticationTokenRepository.save(registerToken);
    }

    @Test
    public void testSetupStartContract() throws Exception {
        mockMvc.perform(get("/setup/start/" + pendingUser.getId()
                + "/" + registerToken.getToken() + ".json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.user.username").value("betty@example.com"));
        // Disabled (verified) — endpoint-absent, NOT an envelope issue:
        // validation.request.path.missing ("No API path found that matches
        // '/setup/start/...'."). The spec defines no /setup/... path (these
        // endpoints are outside the OpenAPI spec domain; behaviour follows the PHP
        // SetupStartController). Recorded in assertions_left_disabled.
        // .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    public void testSetupCompleteContract() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "authentication_token", Map.of("token", registerToken.getToken()),
                "gpgkey", Map.of("armored_key", gpgService.getServerPublicKey())));

        mockMvc.perform(put("/setup/complete/" + pendingUser.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.message").value("The setup was completed successfully."))
                .andExpect(jsonPath("$.body").value(nullValue()));
        // Disabled (verified) — endpoint-absent, NOT an envelope issue:
        // validation.request.path.missing ("No API path found that matches
        // '/setup/complete/...'."). The spec defines no /setup/... path (outside
        // the OpenAPI spec domain; behaviour follows the PHP SetupCompleteController).
        // Recorded in assertions_left_disabled.
        // .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }
}
