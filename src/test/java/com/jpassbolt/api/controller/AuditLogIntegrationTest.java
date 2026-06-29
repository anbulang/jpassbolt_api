package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.Action;
import com.jpassbolt.api.model.ActionLog;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.model.SecretAccess;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.ActionLogRepository;
import com.jpassbolt.api.repository.ActionRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.SecretAccessRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end wiring tests for the two audit features against a live Spring context:
 * <ul>
 *   <li><b>secret_accesses</b> — reading a secret (single-secret or resource view) records
 *       an access; a forbidden read records nothing.</li>
 *   <li><b>action_logs</b> — every controller request is recorded by the
 *       {@link com.jpassbolt.api.config.ActionLogInterceptor} with the right action id,
 *       context, status and user; a blacklisted endpoint is not.</li>
 * </ul>
 *
 * <p>Not {@code @Transactional}: the audit writes happen in their own short transactions at
 * (or after) request completion, so the test commits and explicitly clears all tables in
 * {@link #setUp()} — mirroring the project's contract-test convention.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "audit@example.com", roles = { "USER" })
class AuditLogIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ResourceRepository resourceRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private SecretRepository secretRepository;
    @Autowired private SecretAccessRepository secretAccessRepository;
    @Autowired private ActionLogRepository actionLogRepository;
    @Autowired private ActionRepository actionRepository;

    private User user;
    private Resource resource;
    private Secret secret;

    @BeforeEach
    void setUp() {
        clearAll();

        user = new User();
        user.setUsername("audit@example.com");
        user.setRoleId("user");
        user.setActive(true);
        user.setDeleted(false);
        userRepository.save(user);

        resource = new Resource();
        resource.setName("Test Password");
        resource.setUsername("admin");
        resource.setUri("https://example.com");
        resource.setCreatedBy(user.getId());
        resource.setModifiedBy(user.getId());
        resource.setDeleted(false);
        resourceRepository.save(resource);

        Permission perm = new Permission();
        perm.setAco(Permission.RESOURCE_ACO);
        perm.setAcoForeignKey(resource.getId());
        perm.setAro(Permission.USER_ARO);
        perm.setAroForeignKey(user.getId());
        perm.setType(Permission.OWNER);
        permissionRepository.save(perm);

        secret = new Secret();
        secret.setResourceId(resource.getId());
        secret.setUserId(user.getId());
        secret.setData("-----BEGIN PGP MESSAGE-----\nx\n-----END PGP MESSAGE-----");
        secretRepository.save(secret);
    }

    @AfterEach
    void tearDown() {
        // This test is non-transactional, so its committed rows would otherwise leak into
        // later test classes whose deleteAll() ordering can't remove resources/secrets they
        // didn't create (FK RESOURCES->USERS, SECRETS->RESOURCES). Clean up after ourselves.
        clearAll();
    }

    /**
     * Clears every table this test writes — EXCEPT {@code actions}. The {@code actions}
     * dimension is append-only and globally cached by the singleton {@link com.jpassbolt.api.service.ActionLogService}
     * (production never deletes it); wiping it here would desync that cache from the DB and
     * make a later find-or-create skip an insert it should perform.
     */
    private void clearAll() {
        secretAccessRepository.deleteAll();
        actionLogRepository.deleteAll();
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---------- secret_accesses ----------

    @Test
    void viewingASecretRecordsAnAccess() throws Exception {
        mockMvc.perform(get("/secrets/resource/" + resource.getId() + ".json"))
                .andExpect(status().isOk());

        List<SecretAccess> accesses = secretAccessRepository.findByUserIdAndResourceId(user.getId(), resource.getId());
        assertThat(accesses).hasSize(1);
        assertThat(accesses.get(0).getSecretId()).isEqualTo(secret.getId());
        assertThat(accesses.get(0).getCreated()).isNotNull();
    }

    @Test
    void forbiddenSecretViewRecordsNoAccess() throws Exception {
        // A resource the user has no permission on.
        mockMvc.perform(get("/secrets/resource/" + UUID.randomUUID() + ".json"))
                .andExpect(status().isForbidden());
        assertThat(secretAccessRepository.count()).isZero();
    }

    @Test
    void viewingAResourceRecordsTheCallersSecretAccess() throws Exception {
        mockMvc.perform(get("/resources/" + resource.getId() + ".json"))
                .andExpect(status().isOk());

        List<SecretAccess> accesses = secretAccessRepository.findBySecretId(secret.getId());
        assertThat(accesses).hasSize(1);
        assertThat(accesses.get(0).getUserId()).isEqualTo(user.getId());
    }

    // ---------- action_logs ----------

    @Test
    void everyRequestIsRecordedInTheActionLog() throws Exception {
        mockMvc.perform(get("/resources.json")).andExpect(status().isOk());

        String actionId = Action.idForName("ResourceController.getAllResources");
        List<ActionLog> logs = actionLogRepository.findByActionId(actionId);
        assertThat(logs).hasSize(1);
        ActionLog log = logs.get(0);
        assertThat(log.getContext()).isEqualTo("GET /resources.json");
        assertThat(log.getStatus()).isEqualTo(ActionLog.STATUS_SUCCESS);
        assertThat(log.getUserId()).isEqualTo(user.getId());
        // The actions dimension row was find-or-created.
        assertThat(actionRepository.findById(actionId))
                .get()
                .extracting(Action::getName)
                .isEqualTo("ResourceController.getAllResources");
    }

    @Test
    void blacklistedEndpointIsNotRecorded() throws Exception {
        mockMvc.perform(get("/healthcheck/status.json")).andExpect(status().isOk());

        assertThat(actionLogRepository.findByActionId(Action.idForName("HealthcheckController.status"))).isEmpty();
        // Blacklist short-circuits before the actions dimension is touched, too.
        assertThat(actionRepository.findById(Action.idForName("HealthcheckController.status"))).isEmpty();
    }
}
