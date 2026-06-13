package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.Role;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI contract test for GET /roles.json.
 * The /roles.json path and roles_index response are defined in
 * src/test/resources/plugin-redoc-0.yaml (L5456 / L11130).
 *
 * <p>The strict {@code openApi().isValid(...)} assertion is enabled: the shared
 * {@link com.jpassbolt.api.util.ApiResponse} envelope emits the required header
 * {@code action} (uuid) and integer {@code servertime}, and the global
 * {@link com.jpassbolt.api.config.JacksonConfig} serializes LocalDateTime as
 * RFC3339 with a UTC offset, so the response satisfies the contract.</p>
 */
@WithMockUser(username = "test@example.com", roles = { "USER" })
public class RoleControllerContractTest extends OpenApiComplianceTest {

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @BeforeEach
    void setUpData() {
        // Clear in reverse FK-dependency order (see RoleControllerTest)
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        createRole("user", "Logged in user");
        createRole("admin", "Organization administrator");
        createRole("guest", "Non logged in user");
    }

    private void createRole(String name, String description) {
        Role role = new Role();
        role.setName(name);
        role.setDescription(description);
        roleRepository.save(role);
    }

    @Test
    public void testRolesIndexContract() throws Exception {
        mockMvc.perform(get("/roles.json")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body").isArray())
                .andExpect(jsonPath("$.body.length()").value(3))
                .andExpect(jsonPath("$.body[0].id").exists())
                .andExpect(jsonPath("$.body[0].name").exists())
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }
}
