package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI contract tests for the healthcheck endpoints.
 * Both paths exist in src/test/resources/plugin-redoc-0.yaml
 * (/healthcheck.json and /healthcheck/status.json).
 *
 * <p>
 * Both paths exist in the spec, so the strict {@code openApi().isValid(...)}
 * assertions are ENABLED on every request.
 * </p>
 */
public class HealthCheckControllerContractTest extends OpenApiComplianceTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @BeforeEach
    void setUpData() {
        // FK-safe cleanup order: children referencing users first, then users,
        // then roles (users.role_id references roles).
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role adminRole = new Role();
        adminRole.setName(Role.ADMIN);
        adminRole.setDescription("Organization administrator");
        roleRepository.save(adminRole);

        User adminUser = new User();
        adminUser.setUsername("admin@passbolt.com");
        adminUser.setRoleId(adminRole.getId());
        adminUser.setActive(true);
        adminUser.setDeleted(false);
        userRepository.save(adminUser);
    }

    /**
     * GET /healthcheck/status.json — anonymous, public (OpenAPI security: []).
     * Body must be the bare string "OK" per components/responses/status.
     */
    @Test
    public void testStatusContract() throws Exception {
        mockMvc.perform(get("/healthcheck/status.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("OK"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    /**
     * GET /healthcheck.json — as administrator, 200 with the eight-domain
     * report per components/responses/healthcheck.
     */
    @Test
    @WithMockUser(username = "admin@passbolt.com", roles = { "USER" })
    public void testHealthcheckIndexContract() throws Exception {
        mockMvc.perform(get("/healthcheck.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.database.connect").value(true))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }
}
