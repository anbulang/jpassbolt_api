package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.Folder;
import com.jpassbolt.api.model.MetadataKey;
import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.Tag;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.FolderRepository;
import com.jpassbolt.api.repository.MetadataKeyRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.TagRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI contract tests for the v5 Metadata <b>upgrade</b> and
 * <b>rotate-key</b> endpoints
 * ({@code GET|POST /metadata/upgrade/{resources,folders,tags}.json} and
 * {@code GET|POST /metadata/rotate-key/{resources,folders,tags}.json}).
 *
 * <p>
 * Both index families respond with the {@code headerWithPagination} envelope, so
 * the GET assertions check the {@code header.pagination} block in addition to the
 * standard envelope. The {@code openApi().isValid(CONTRACT_VALIDATOR)} assertion
 * is ENABLED on the index (GET) success paths and on the admin 403 path
 * ({@code accessRestrictedToAdministrators} is a declared response for the GET
 * endpoints).
 * </p>
 *
 * <p>
 * The POST apply success paths and the 409 conflict path carry the v3-compatible
 * {@code e2eeMetadataBased}-shaped request/response bodies (the armored
 * {@code metadata} blob plus the optimistic-lock pair). Consistent with the
 * existing Folder/Move/GroupShare precedent, a strict {@code isValid} assertion on
 * those e2ee-shaped POST exchanges is intentionally NOT made — the assertions
 * verify the envelope/status/effect instead. See the per-test comments.
 * </p>
 *
 * <p>
 * The mock user maps to a real {@code admin} Role row (seeded in
 * {@link #seedData()}) because {@code userService.isAdmin()} resolves the role
 * through the roles table; the upgrade/rotate endpoints are admin-gated.
 * </p>
 */
@WithMockUser(username = "admin@passbolt.com", roles = { "USER" })
class MetadataUpgradeRotateControllerContractTest extends OpenApiComplianceTest {

    private static final String TYPES_SETTING_PROPERTY = "metadataTypes";
    private static final String SHARED_KEY = "shared_key";
    // Single-line placeholder: the server is zero-knowledge and never parses the
    // blob here, and a newline-free value keeps the hand-built JSON request bodies
    // valid (matches the spec examples' '-----BEGIN PGP MESSAGE-----').
    private static final String ARMORED = "-----BEGIN PGP MESSAGE-----";

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private OrganizationSettingRepository organizationSettingRepository;
    @Autowired
    private ResourceRepository resourceRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private MetadataKeyRepository metadataKeyRepository;

    private Role adminRole;
    private Role userRole;
    private String adminId;

    @BeforeEach
    void seedData() {
        resourceRepository.deleteAll();
        folderRepository.deleteAll();
        tagRepository.deleteAll();
        metadataKeyRepository.deleteAll();
        organizationSettingRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();

        adminRole = new Role();
        adminRole.setName(Role.ADMIN);
        adminRole.setDescription("Organization administrator");
        roleRepository.save(adminRole);

        userRole = new Role();
        userRole.setName(Role.USER);
        userRole.setDescription("Logged in user");
        roleRepository.save(userRole);

        User admin = new User();
        admin.setUsername("admin@passbolt.com");
        admin.setRoleId(adminRole.getId());
        admin.setActive(true);
        admin.setDeleted(false);
        adminId = userRepository.save(admin).getId();
    }

    // ------------------------------------------------------------------
    // upgrade — GET index (contract-validated)
    // ------------------------------------------------------------------

    @Test
    void testUpgradeResourcesIndexContract() throws Exception {
        seedV4Resource();

        mockMvc.perform(get("/metadata/upgrade/resources.json")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.pagination.count").value(1))
                .andExpect(jsonPath("$.body[0].name").value("password v4 format"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    void testUpgradeFoldersIndexContract() throws Exception {
        seedV4Folder();

        mockMvc.perform(get("/metadata/upgrade/folders.json")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.pagination.count").value(1))
                .andExpect(jsonPath("$.body[0].name").value("folder v4 format"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    void testUpgradeTagsIndexContract() throws Exception {
        seedV4Tag();

        mockMvc.perform(get("/metadata/upgrade/tags.json")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.pagination.count").value(1))
                .andExpect(jsonPath("$.body[0].slug").value("important tag in v4 format"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    // ------------------------------------------------------------------
    // upgrade — POST apply
    // ------------------------------------------------------------------

    @Test
    void testUpgradeResourcesApply() throws Exception {
        enableV4ToV5Upgrade();
        Resource resource = seedV4Resource();

        String body = "[{"
                + "\"id\":\"" + resource.getId() + "\","
                + "\"metadata\":\"" + ARMORED + "\","
                + "\"metadata_key_id\":\"" + UUID.randomUUID() + "\","
                + "\"metadata_key_type\":\"" + SHARED_KEY + "\","
                + "\"modified\":\"" + rfc3339(resource.getModified()) + "\","
                + "\"modified_by\":\"" + resource.getModifiedBy() + "\"}]";

        // The POST request/response bodies are the v3-compatible e2eeMetadataBased
        // shape; isValid is intentionally not asserted here (Folder/Move precedent).
        // The applied row leaves the v4 index, so the recomputed body is empty.
        mockMvc.perform(post("/metadata/upgrade/resources.json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.length()").value(0));
    }

    @Test
    void testUpgradeForbiddenWhenSettingDisabled() throws Exception {
        // allow_v4_v5_upgrade defaults to false (no org-setting row) → 403.
        Resource resource = seedV4Resource();

        String body = "[{"
                + "\"id\":\"" + resource.getId() + "\","
                + "\"metadata\":\"" + ARMORED + "\","
                + "\"metadata_key_id\":\"" + UUID.randomUUID() + "\","
                + "\"metadata_key_type\":\"" + SHARED_KEY + "\","
                + "\"modified\":\"" + rfc3339(resource.getModified()) + "\","
                + "\"modified_by\":\"" + resource.getModifiedBy() + "\"}]";

        mockMvc.perform(post("/metadata/upgrade/resources.json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    @Test
    void testUpgradeConflictOnStaleModified() throws Exception {
        enableV4ToV5Upgrade();
        Resource resource = seedV4Resource();

        // A modified timestamp that does not match the stored row → 409.
        String body = "[{"
                + "\"id\":\"" + resource.getId() + "\","
                + "\"metadata\":\"" + ARMORED + "\","
                + "\"metadata_key_id\":\"" + UUID.randomUUID() + "\","
                + "\"metadata_key_type\":\"" + SHARED_KEY + "\","
                + "\"modified\":\"2000-01-01T00:00:00+00:00\","
                + "\"modified_by\":\"" + resource.getModifiedBy() + "\"}]";

        mockMvc.perform(post("/metadata/upgrade/resources.json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    // ------------------------------------------------------------------
    // upgrade / rotate — admin gate (contract-validated 403)
    // ------------------------------------------------------------------

    @Test
    @WithMockUser(username = "nonadmin@passbolt.com", roles = { "USER" })
    void testUpgradeIndexNonAdminForbiddenContract() throws Exception {
        User plain = new User();
        plain.setUsername("nonadmin@passbolt.com");
        plain.setRoleId(userRole.getId());
        plain.setActive(true);
        plain.setDeleted(false);
        userRepository.save(plain);

        // accessRestrictedToAdministrators (403) is a declared response for the
        // upgrade GET, so the 403 is contract-validated.
        mockMvc.perform(get("/metadata/upgrade/resources.json")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    @WithMockUser(username = "nonadmin@passbolt.com", roles = { "USER" })
    void testRotateIndexNonAdminForbiddenContract() throws Exception {
        User plain = new User();
        plain.setUsername("nonadmin@passbolt.com");
        plain.setRoleId(userRole.getId());
        plain.setActive(true);
        plain.setDeleted(false);
        userRepository.save(plain);

        mockMvc.perform(get("/metadata/rotate-key/resources.json")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    // ------------------------------------------------------------------
    // rotate-key — GET index (contract-validated)
    // ------------------------------------------------------------------

    @Test
    void testRotateResourcesIndexContract() throws Exception {
        MetadataKey expiredKey = seedExpiredKey();
        seedV5ResourceWithKey(expiredKey.getId());

        mockMvc.perform(get("/metadata/rotate-key/resources.json")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.pagination.count").value(1))
                .andExpect(jsonPath("$.body[0].metadata_key_type").value(SHARED_KEY))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    void testRotateFoldersIndexContract() throws Exception {
        MetadataKey expiredKey = seedExpiredKey();
        seedV5FolderWithKey(expiredKey.getId());

        mockMvc.perform(get("/metadata/rotate-key/folders.json")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.pagination.count").value(1))
                .andExpect(jsonPath("$.body[0].metadata_key_type").value(SHARED_KEY))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    void testRotateTagsIndexContract() throws Exception {
        MetadataKey expiredKey = seedExpiredKey();
        seedV5TagWithKey(expiredKey.getId());

        mockMvc.perform(get("/metadata/rotate-key/tags.json")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.header.pagination.count").value(1))
                .andExpect(jsonPath("$.body[0].metadata_key_type").value(SHARED_KEY))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    // ------------------------------------------------------------------
    // rotate-key — POST apply + 409
    // ------------------------------------------------------------------

    @Test
    void testRotateResourcesApply() throws Exception {
        MetadataKey expiredKey = seedExpiredKey();
        MetadataKey activeKey = seedActiveKey();
        Resource resource = seedV5ResourceWithKey(expiredKey.getId());

        String body = "[{"
                + "\"id\":\"" + resource.getId() + "\","
                + "\"metadata\":\"" + ARMORED + "\","
                + "\"metadata_key_id\":\"" + activeKey.getId() + "\","
                + "\"metadata_key_type\":\"" + SHARED_KEY + "\"}]";

        // e2eeMetadataBased POST exchange — isValid intentionally not asserted.
        // After re-keying to an active key the row leaves the rotate index.
        mockMvc.perform(post("/metadata/rotate-key/resources.json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.length()").value(0));
    }

    @Test
    void testRotateConflictWhenNewKeyNotActive() throws Exception {
        MetadataKey expiredKey = seedExpiredKey();
        Resource resource = seedV5ResourceWithKey(expiredKey.getId());

        // Re-keying onto a still-expired key → tooManyUpdatedEntities (409).
        String body = "[{"
                + "\"id\":\"" + resource.getId() + "\","
                + "\"metadata\":\"" + ARMORED + "\","
                + "\"metadata_key_id\":\"" + expiredKey.getId() + "\","
                + "\"metadata_key_type\":\"" + SHARED_KEY + "\"}]";

        mockMvc.perform(post("/metadata/rotate-key/resources.json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    // ------------------------------------------------------------------
    // seed helpers
    // ------------------------------------------------------------------

    private void enableV4ToV5Upgrade() {
        OrganizationSetting setting = new OrganizationSetting();
        setting.setProperty(TYPES_SETTING_PROPERTY);
        setting.setPropertyId(UUID.randomUUID().toString());
        setting.setValue("{\"allow_v4_v5_upgrade\":true}");
        setting.setCreatedBy(adminId);
        setting.setModifiedBy(adminId);
        organizationSettingRepository.save(setting);
    }

    private Resource seedV4Resource() {
        Resource resource = new Resource();
        resource.setName("password v4 format");
        resource.setUsername("anakin");
        resource.setUri("https://passbolt.com");
        resource.setDeleted(false);
        resource.setCreatedBy(adminId);
        resource.setModifiedBy(adminId);
        resource.setResourceTypeId(UUID.randomUUID().toString());
        return resourceRepository.save(resource);
    }

    private Folder seedV4Folder() {
        Folder folder = new Folder();
        folder.setName("folder v4 format");
        folder.setCreatedBy(adminId);
        folder.setModifiedBy(adminId);
        return folderRepository.save(folder);
    }

    private Tag seedV4Tag() {
        Tag tag = new Tag();
        tag.setSlug("important tag in v4 format");
        tag.setIsShared(false);
        return tagRepository.save(tag);
    }

    private MetadataKey seedExpiredKey() {
        MetadataKey key = new MetadataKey();
        key.setFingerprint(randomFingerprint());
        key.setArmoredKey("-----BEGIN PGP PUBLIC KEY BLOCK-----");
        key.setExpired(LocalDateTime.now().minusDays(1));
        key.setCreatedBy(adminId);
        key.setModifiedBy(adminId);
        return metadataKeyRepository.save(key);
    }

    private MetadataKey seedActiveKey() {
        MetadataKey key = new MetadataKey();
        key.setFingerprint(randomFingerprint());
        key.setArmoredKey("-----BEGIN PGP PUBLIC KEY BLOCK-----");
        key.setCreatedBy(adminId);
        key.setModifiedBy(adminId);
        return metadataKeyRepository.save(key);
    }

    private Resource seedV5ResourceWithKey(String metadataKeyId) {
        Resource resource = new Resource();
        resource.setName("v5 resource");
        resource.setDeleted(false);
        resource.setCreatedBy(adminId);
        resource.setModifiedBy(adminId);
        resource.setResourceTypeId(UUID.randomUUID().toString());
        resource.setMetadata(ARMORED);
        resource.setMetadataKeyId(metadataKeyId);
        resource.setMetadataKeyType(SHARED_KEY);
        return resourceRepository.save(resource);
    }

    private Folder seedV5FolderWithKey(String metadataKeyId) {
        Folder folder = new Folder();
        folder.setCreatedBy(adminId);
        folder.setModifiedBy(adminId);
        folder.setMetadata(ARMORED);
        folder.setMetadataKeyId(metadataKeyId);
        folder.setMetadataKeyType(SHARED_KEY);
        return folderRepository.save(folder);
    }

    private Tag seedV5TagWithKey(String metadataKeyId) {
        Tag tag = new Tag();
        tag.setSlug("#shared-v5-tag");
        tag.setIsShared(true);
        tag.setMetadata(ARMORED);
        tag.setMetadataKeyId(metadataKeyId);
        tag.setMetadataKeyType(SHARED_KEY);
        return tagRepository.save(tag);
    }

    /** 40-hex-char fingerprint (the fingerprint column is varchar(51) unique). */
    private String randomFingerprint() {
        return (UUID.randomUUID().toString() + UUID.randomUUID().toString())
                .replace("-", "").substring(0, 40).toUpperCase();
    }

    private String rfc3339(LocalDateTime dt) {
        // The entity is persisted in UTC (BaseEntity writes UTC); render with the
        // +00:00 offset the JacksonConfig uses so the optimistic-lock match holds.
        return dt.withNano(0).toString() + "+00:00";
    }
}
