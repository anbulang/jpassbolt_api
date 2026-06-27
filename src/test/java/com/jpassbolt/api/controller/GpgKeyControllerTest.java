package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.model.GpgKey;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for GpgKeyController (read-only GPG public key directory).
 *
 * Ported behavior reference: PHP GpgkeysIndexController / GpgkeysViewController.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class GpgKeyControllerTest {

    private static final String ARMORED_KEY = "-----BEGIN PGP PUBLIC KEY BLOCK-----\n"
            + "\n"
            + "mQINBGWtest1BEADTestKeyMaterialLine1\n"
            + "TestKeyMaterialLine2==\n"
            + "=AbCd\n"
            + "-----END PGP PUBLIC KEY BLOCK-----";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GpgKeyRepository gpgKeyRepository;

    @Autowired
    private UserRepository userRepository;

    // Cleared as well so userRepository.deleteAll() cannot hit FK constraints
    // from data left behind by other test classes (resources/secrets/permissions
    // reference users via @ManyToOne navigation tracks).
    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private SecretRepository secretRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private User testUser;

    @BeforeEach
    void setUp() {
        // Reverse FK order: children first, users last (gpgkeys.user_id -> users)
        permissionRepository.deleteAll();
        secretRepository.deleteAll();
        resourceRepository.deleteAll();
        gpgKeyRepository.deleteAll();
        userRepository.deleteAll();

        testUser = new User();
        testUser.setUsername("test@example.com");
        testUser.setRoleId("user");
        testUser.setActive(true);
        testUser.setDeleted(false);
        userRepository.save(testUser);
    }

    /**
     * Helper to create a GPG key row. Fingerprints must be unique per call
     * (40 hex chars); type is always RSA to match the contract enum.
     */
    private GpgKey createGpgKey(String userId, String fingerprint, boolean deleted) {
        GpgKey key = new GpgKey();
        key.setUserId(userId);
        key.setArmoredKey(ARMORED_KEY);
        key.setBits(3072);
        key.setUid("Test User <test@example.com>");
        key.setKeyId(fingerprint.substring(fingerprint.length() - 16));
        key.setFingerprint(fingerprint);
        key.setType("RSA");
        key.setDeleted(deleted);
        return gpgKeyRepository.save(key);
    }

    // ------------------------------------------------------------------
    // GET /gpgkeys.json (index)
    // ------------------------------------------------------------------

    @Test
    void testIndexReturnsOnlyNonDeletedKeysByDefault() throws Exception {
        GpgKey active1 = createGpgKey(testUser.getId(), "AAAA000000000000000000000000000000000001", false);
        GpgKey active2 = createGpgKey(testUser.getId(), "AAAA000000000000000000000000000000000002", false);
        GpgKey softDeleted = createGpgKey(testUser.getId(), "AAAA000000000000000000000000000000000003", true);

        MvcResult result = mockMvc.perform(get("/gpgkeys.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body").isArray())
                .andExpect(jsonPath("$.body.length()").value(2))
                .andReturn();

        String content = result.getResponse().getContentAsString();
        assertThat(content).contains(active1.getId()).contains(active2.getId());
        assertThat(content).doesNotContain(softDeleted.getId());
    }

    @Test
    void testIndexFieldsAreSnakeCaseAndComplete() throws Exception {
        createGpgKey(testUser.getId(), "BBBB000000000000000000000000000000000001", false);

        MvcResult result = mockMvc.perform(get("/gpgkeys.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.length()").value(1))
                .andReturn();

        JsonNode key = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("body").get(0);

        // All 13 keys of the gpgkey schema must be present (required by spec),
        // even when the value is null (expires / key_created here).
        String[] expectedFields = { "id", "user_id", "armored_key", "bits", "uid", "key_id",
                "fingerprint", "type", "expires", "key_created", "deleted", "created", "modified" };
        for (String field : expectedFields) {
            assertThat(key.has(field)).as("field '%s' must be present", field).isTrue();
        }

        // armored_key is returned verbatim, including line breaks
        assertThat(key.get("armored_key").asText()).isEqualTo(ARMORED_KEY);
        assertThat(key.get("user_id").asText()).isEqualTo(testUser.getId());
        assertThat(key.get("key_id").asText()).isEqualTo("0000000000000001");
        assertThat(key.get("type").asText()).isEqualTo("RSA");
    }

    @Test
    void testIndexModifiedAfterFilter() throws Exception {
        GpgKey key = createGpgKey(testUser.getId(), "CCCC000000000000000000000000000000000001", false);

        long now = System.currentTimeMillis() / 1000;

        // modified > (now - 3600) -> key included
        mockMvc.perform(get("/gpgkeys.json")
                .param("filter[modified-after]", String.valueOf(now - 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.length()").value(1))
                .andExpect(jsonPath("$.body[0].id").value(key.getId()));

        // modified > (now + 3600) -> empty (strict greater-than semantics)
        mockMvc.perform(get("/gpgkeys.json")
                .param("filter[modified-after]", String.valueOf(now + 3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").isArray())
                .andExpect(jsonPath("$.body").isEmpty());
    }

    @Test
    void testIndexIsDeletedFilter() throws Exception {
        GpgKey active = createGpgKey(testUser.getId(), "DDDD000000000000000000000000000000000001", false);
        GpgKey softDeleted = createGpgKey(testUser.getId(), "DDDD000000000000000000000000000000000002", true);

        // filter[is-deleted]=1 -> only soft-deleted keys
        mockMvc.perform(get("/gpgkeys.json")
                .param("filter[is-deleted]", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.length()").value(1))
                .andExpect(jsonPath("$.body[0].id").value(softDeleted.getId()))
                .andExpect(jsonPath("$.body[0].deleted").value(true));

        // filter[is-deleted]=0 -> only active keys
        mockMvc.perform(get("/gpgkeys.json")
                .param("filter[is-deleted]", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.length()").value(1))
                .andExpect(jsonPath("$.body[0].id").value(active.getId()))
                .andExpect(jsonPath("$.body[0].deleted").value(false));
    }

    @Test
    void testIndexInvalidModifiedAfterReturns400() throws Exception {
        mockMvc.perform(get("/gpgkeys.json")
                .param("filter[modified-after]", "not-a-timestamp"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testIndexInvalidIsDeletedReturns400() throws Exception {
        mockMvc.perform(get("/gpgkeys.json")
                .param("filter[is-deleted]", "banana"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testIndexPaginationHeader() throws Exception {
        createGpgKey(testUser.getId(), "EEEE000000000000000000000000000000000001", false);
        createGpgKey(testUser.getId(), "EEEE000000000000000000000000000000000002", false);

        MvcResult result = mockMvc.perform(get("/gpgkeys.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.pagination.count").value(2))
                .andExpect(jsonPath("$.header.pagination.page").value(1))
                .andReturn();

        // limit must be present and null (headerWithPagination: x-nullable)
        JsonNode pagination = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("header").get("pagination");
        assertThat(pagination.has("limit")).isTrue();
        assertThat(pagination.get("limit").isNull()).isTrue();
    }

    @Test
    void testIndexWithoutJsonExtension() throws Exception {
        createGpgKey(testUser.getId(), "FFFF000000000000000000000000000000000001", false);

        mockMvc.perform(get("/gpgkeys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.length()").value(1));
    }

    // ------------------------------------------------------------------
    // GET /gpgkeys/{id}.json (view)
    // ------------------------------------------------------------------

    @Test
    void testViewReturnsKey() throws Exception {
        GpgKey key = createGpgKey(testUser.getId(), "1111000000000000000000000000000000000001", false);

        mockMvc.perform(get("/gpgkeys/" + key.getId() + ".json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                // body is a single object, not an array
                .andExpect(jsonPath("$.body.id").value(key.getId()))
                .andExpect(jsonPath("$.body.fingerprint").value(key.getFingerprint()))
                .andExpect(jsonPath("$.body.armored_key").value(ARMORED_KEY));
    }

    @Test
    void testViewInvalidUuidReturns400() throws Exception {
        mockMvc.perform(get("/gpgkeys/not-a-uuid.json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.message")
                        .value("The OpenPGP key identifier should be a valid UUID."));
    }

    @Test
    void testViewUnknownIdReturns404() throws Exception {
        mockMvc.perform(get("/gpgkeys/" + UUID.randomUUID() + ".json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(jsonPath("$.header.message").value("The OpenPGP key does not exist."));
    }

    /**
     * Pins the PHP findView semantics: soft-deleted keys are still returned
     * with 200 and deleted=true. The PHP reference (GpgkeysTable::findView)
     * does NOT filter on deleted, even though its comment claims otherwise.
     * Do not "fix" this by applying the project's usual soft-delete filter —
     * the official plugin relies on this during keyring synchronization.
     */
    @Test
    void testViewSoftDeletedKeyStillReturned() throws Exception {
        GpgKey softDeleted = createGpgKey(testUser.getId(), "2222000000000000000000000000000000000001", true);

        mockMvc.perform(get("/gpgkeys/" + softDeleted.getId() + ".json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.id").value(softDeleted.getId()))
                .andExpect(jsonPath("$.body.deleted").value(true));
    }

    // ------------------------------------------------------------------
    // Authentication negative cases
    // ------------------------------------------------------------------

    /**
     * The OpenAPI contract specifies 401 for unauthenticated requests, but the
     * project's SecurityConfig has no authenticationEntryPoint configured, so
     * Spring Security returns 403 for anonymous access. This is existing
     * global behavior (same for all protected endpoints); aligning it with the
     * contract is a cross-cutting task, asserted as-is here.
     */
    @Test
    @WithAnonymousUser
    void testUnauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/gpgkeys.json"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/gpgkeys/" + UUID.randomUUID() + ".json"))
                .andExpect(status().isUnauthorized());
    }
}
