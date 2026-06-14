package com.jpassbolt.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.ResourceDto;
import com.jpassbolt.api.model.MetadataKey;
import com.jpassbolt.api.model.OrganizationSetting;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.MetadataKeyRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for ResourceController with permission enforcement.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "test@example.com", roles = { "USER" })
class ResourceControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ResourceRepository resourceRepository;

        @Autowired
        private SecretRepository secretRepository;

        @Autowired
        private PermissionRepository permissionRepository;

        @Autowired
        private UserRepository userRepository;

        @Autowired
        private MetadataKeyRepository metadataKeyRepository;

        @Autowired
        private OrganizationSettingRepository organizationSettingRepository;

        @Autowired
        private ObjectMapper objectMapper;

        private User testUser;

        /** Single-line placeholder accepted by the lenient parse-only validator. */
        private static final String ARMORED = "-----BEGIN PGP MESSAGE-----";

        @BeforeEach
        void setUp() {
                organizationSettingRepository.deleteAll();
                metadataKeyRepository.deleteAll();
                permissionRepository.deleteAll();
                secretRepository.deleteAll();
                resourceRepository.deleteAll();
                userRepository.deleteAll();

                testUser = new User();
                testUser.setUsername("test@example.com");
                testUser.setRoleId("user");
                testUser.setActive(true);
                testUser.setDeleted(false);
                userRepository.save(testUser);
        }

        /**
         * Helper to create a resource with OWNER permission for the test user.
         */
        private Resource createResourceWithPermission(String name, String username, String uri, int permType) {
                Resource resource = new Resource();
                resource.setName(name);
                resource.setUsername(username);
                resource.setUri(uri);
                resource.setCreatedBy(testUser.getId());
                resource.setModifiedBy(testUser.getId());
                resource.setDeleted(false);
                resourceRepository.save(resource);

                Permission perm = new Permission();
                perm.setAco(Permission.RESOURCE_ACO);
                perm.setAcoForeignKey(resource.getId());
                perm.setAro(Permission.USER_ARO);
                perm.setAroForeignKey(testUser.getId());
                perm.setType(permType);
                permissionRepository.save(perm);

                return resource;
        }

        @Test
        void testGetAllResources_ReturnsEmptyList() throws Exception {
                mockMvc.perform(get("/resources"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                .andExpect(jsonPath("$.body").isArray())
                                .andExpect(jsonPath("$.body").isEmpty());
        }

        @Test
        void testGetAllResources_OnlyShowsPermittedResources() throws Exception {
                // Resource with permission
                createResourceWithPermission("Visible", "admin", "https://visible.com", Permission.READ);

                // Resource without permission (created by another user, no permission)
                Resource hidden = new Resource();
                hidden.setName("Hidden");
                hidden.setCreatedBy(testUser.getId());
                hidden.setModifiedBy(testUser.getId());
                hidden.setDeleted(false);
                resourceRepository.save(hidden);

                mockMvc.perform(get("/resources"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body").isArray())
                                .andExpect(jsonPath("$.body.length()").value(1))
                                .andExpect(jsonPath("$.body[0].name").value("Visible"));
        }

        @Test
        void testGetResource_WithPermission() throws Exception {
                Resource resource = createResourceWithPermission("My Password", "user1", "https://mysite.com",
                                Permission.OWNER);

                Secret secret = new Secret();
                secret.setResourceId(resource.getId());
                secret.setUserId(testUser.getId());
                secret.setData("-----BEGIN PGP MESSAGE-----\nEncrypted\n-----END PGP MESSAGE-----");
                secretRepository.save(secret);

                mockMvc.perform(get("/resources/" + resource.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.name").value("My Password"))
                                .andExpect(jsonPath("$.body.secrets").isArray());
        }

        @Test
        void testGetResource_WithoutPermission() throws Exception {
                // Resource with no permission for test user
                Resource resource = new Resource();
                resource.setName("Forbidden");
                resource.setCreatedBy(testUser.getId());
                resource.setModifiedBy(testUser.getId());
                resource.setDeleted(false);
                resourceRepository.save(resource);

                mockMvc.perform(get("/resources/" + resource.getId() + ".json"))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        @Test
        void testCreateResource_AutoCreatesOwnerPermission() throws Exception {
                ResourceDto.CreateRequest request = ResourceDto.CreateRequest.builder()
                                .name("New Password")
                                .username("newuser")
                                .uri("https://newsite.com")
                                .description("A new password entry")
                                .secrets(List.of(
                                                ResourceDto.CreateRequest.SecretData.builder()
                                                                .data("-----BEGIN PGP MESSAGE-----\nNew secret\n-----END PGP MESSAGE-----")
                                                                .build()))
                                .build();

                mockMvc.perform(post("/resources")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.body.name").value("New Password"));

                // Verify OWNER permission was auto-created
                List<Resource> resources = resourceRepository.findByDeletedFalse();
                assertThat(resources).hasSize(1);
                assertThat(permissionRepository.userHasAccess(
                                resources.get(0).getId(), testUser.getId(), Permission.OWNER)).isTrue();
        }

        @Test
        void testUpdateResource_WithUpdatePermission() throws Exception {
                Resource resource = createResourceWithPermission("Original", "original", null, Permission.UPDATE);

                ResourceDto.UpdateRequest request = ResourceDto.UpdateRequest.builder()
                                .name("Updated Name")
                                .build();

                mockMvc.perform(put("/resources/" + resource.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.name").value("Updated Name"));
        }

        @Test
        void testDeleteResource_WithOwnerPermission() throws Exception {
                Resource resource = createResourceWithPermission("To Delete", null, null, Permission.OWNER);

                mockMvc.perform(delete("/resources/" + resource.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.header.status").value("success"));

                Resource deleted = resourceRepository.findById(resource.getId()).orElseThrow();
                assertThat(deleted.getDeleted()).isTrue();
        }

        @Test
        void testDeleteResource_WithReadOnlyPermission_Forbidden() throws Exception {
                Resource resource = createResourceWithPermission("Cannot Delete", null, null, Permission.READ);

                mockMvc.perform(delete("/resources/" + resource.getId() + ".json"))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.header.status").value("error"));
        }

        // =================================================================
        // v4 <-> v5 metadata fusion (ResourceService.createResource /
        // updateResource branch on request.getMetadata() != null).
        //
        // v5 path: the encrypted metadata blob is stored VERBATIM
        // (zero-knowledge — never decrypted), the metadata trio +
        // resource_type_id persist, and the v4 plaintext columns
        // (name/username/uri/description) are left NULL. The response carries
        // the metadata trio; a v4 row omits it (NON_NULL).
        // =================================================================

        @Test
        void testCreateResource_V5_StoresMetadataVerbatim_PlaintextNull() throws Exception {
                enableV5ResourceCreation();
                MetadataKey key = seedActiveSharedKey();
                String resourceTypeId = UUID.randomUUID().toString();

                ResourceDto.CreateRequest request = ResourceDto.CreateRequest.builder()
                                .metadata(ARMORED)
                                .metadataKeyId(key.getId())
                                .metadataKeyType("shared_key")
                                .resourceTypeId(resourceTypeId)
                                .secrets(List.of(ResourceDto.CreateRequest.SecretData.builder()
                                                .data("-----BEGIN PGP MESSAGE-----\nv5 secret\n-----END PGP MESSAGE-----")
                                                .build()))
                                .build();

                mockMvc.perform(post("/resources")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.header.status").value("success"))
                                // response emits the metadata trio
                                .andExpect(jsonPath("$.body.metadata").value(ARMORED))
                                .andExpect(jsonPath("$.body.metadata_key_id").value(key.getId()))
                                .andExpect(jsonPath("$.body.metadata_key_type").value("shared_key"))
                                .andExpect(jsonPath("$.body.resource_type_id").value(resourceTypeId));

                // The stored row keeps the blob verbatim and leaves plaintext NULL.
                List<Resource> resources = resourceRepository.findByDeletedFalse();
                assertThat(resources).hasSize(1);
                Resource stored = resources.get(0);
                assertThat(stored.getMetadata()).isEqualTo(ARMORED);
                assertThat(stored.getMetadataKeyId()).isEqualTo(key.getId());
                assertThat(stored.getMetadataKeyType()).isEqualTo("shared_key");
                assertThat(stored.getResourceTypeId()).isEqualTo(resourceTypeId);
                assertThat(stored.getName()).isNull();
                assertThat(stored.getUsername()).isNull();
                assertThat(stored.getUri()).isNull();
                assertThat(stored.getDescription()).isNull();

                // OWNER permission still auto-created in the v5 branch.
                assertThat(permissionRepository.userHasAccess(
                                stored.getId(), testUser.getId(), Permission.OWNER)).isTrue();
        }

        @Test
        void testUpdateResource_V5_SetsMetadataTrio() throws Exception {
                enableV5ResourceCreation();
                MetadataKey key = seedActiveSharedKey();
                // A v4 resource the user owns, then switched to v5 by an update.
                Resource resource = createResourceWithPermission("v4 name", "v4 user", null, Permission.OWNER);

                ResourceDto.UpdateRequest request = ResourceDto.UpdateRequest.builder()
                                .metadata(ARMORED)
                                .metadataKeyId(key.getId())
                                .metadataKeyType("shared_key")
                                .build();

                mockMvc.perform(put("/resources/" + resource.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.metadata").value(ARMORED))
                                .andExpect(jsonPath("$.body.metadata_key_id").value(key.getId()))
                                .andExpect(jsonPath("$.body.metadata_key_type").value("shared_key"));

                Resource stored = resourceRepository.findById(resource.getId()).orElseThrow();
                assertThat(stored.getMetadata()).isEqualTo(ARMORED);
                assertThat(stored.getMetadataKeyId()).isEqualTo(key.getId());
                assertThat(stored.getMetadataKeyType()).isEqualTo("shared_key");
                // The stale v4 plaintext columns MUST be cleared on the v5 upgrade
                // (PHP ResourcesUpdateService::patchEntity nulls them server-side) —
                // otherwise the row leaks the old plaintext alongside the blob.
                assertThat(stored.getName()).isNull();
                assertThat(stored.getUsername()).isNull();
                assertThat(stored.getUri()).isNull();
                assertThat(stored.getDescription()).isNull();
        }

        @Test
        void testCreateResource_MixedPayload_BadRequest() throws Exception {
                enableV5ResourceCreation();
                MetadataKey key = seedActiveSharedKey();

                ResourceDto.CreateRequest request = ResourceDto.CreateRequest.builder()
                                .name("v4 name not allowed with v5")
                                .metadata(ARMORED)
                                .metadataKeyId(key.getId())
                                .metadataKeyType("shared_key")
                                .build();

                mockMvc.perform(post("/resources")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message")
                                                .value(Matchers.containsString(
                                                                "V4 related fields are not supported for V5")));

                assertThat(resourceRepository.findByDeletedFalse()).isEmpty();
        }

        @Test
        void testUpdateResource_MixedPayload_BadRequest() throws Exception {
                enableV5ResourceCreation();
                MetadataKey key = seedActiveSharedKey();
                Resource resource = createResourceWithPermission("v4 name", "v4 user", null, Permission.OWNER);

                ResourceDto.UpdateRequest request = ResourceDto.UpdateRequest.builder()
                                .name("v4 name not allowed with v5")
                                .metadata(ARMORED)
                                .metadataKeyId(key.getId())
                                .metadataKeyType("shared_key")
                                .build();

                mockMvc.perform(put("/resources/" + resource.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message")
                                                .value(Matchers.containsString(
                                                                "V4 related fields are not supported for V5")));

                // Rejected: the v4 row is untouched (no partial/corrupt write).
                Resource stored = resourceRepository.findById(resource.getId()).orElseThrow();
                assertThat(stored.getName()).isEqualTo("v4 name");
                assertThat(stored.getMetadata()).isNull();
        }

        @Test
        void testGetResource_V5_EmitsMetadataTrio() throws Exception {
                MetadataKey key = seedActiveSharedKey();
                Resource resource = createV5ResourceWithPermission(key.getId());

                mockMvc.perform(get("/resources/" + resource.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.metadata").value(ARMORED))
                                .andExpect(jsonPath("$.body.metadata_key_id").value(key.getId()))
                                .andExpect(jsonPath("$.body.metadata_key_type").value("shared_key"));
        }

        @Test
        void testGetResource_V4_OmitsMetadataTrio() throws Exception {
                // A plain v4 row: NON_NULL keeps the response clean (no metadata keys).
                Resource resource = createResourceWithPermission("Plain v4", "user", "https://x", Permission.OWNER);

                mockMvc.perform(get("/resources/" + resource.getId() + ".json"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.name").value("Plain v4"))
                                .andExpect(jsonPath("$.body.metadata").doesNotExist())
                                .andExpect(jsonPath("$.body.metadata_key_id").doesNotExist())
                                .andExpect(jsonPath("$.body.metadata_key_type").doesNotExist());
        }

        @Test
        void testCreateResource_V4_Regression_NoMetadataKeysInResponse() throws Exception {
                // The existing v4 create flow must be byte-for-byte unchanged: the
                // response carries the flat fields and NONE of the metadata keys.
                ResourceDto.CreateRequest request = ResourceDto.CreateRequest.builder()
                                .name("Regression v4")
                                .username("ru")
                                .uri("https://reg.example")
                                .description("v4 still works")
                                .secrets(List.of(ResourceDto.CreateRequest.SecretData.builder()
                                                .data("-----BEGIN PGP MESSAGE-----\nx\n-----END PGP MESSAGE-----")
                                                .build()))
                                .build();

                mockMvc.perform(post("/resources")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.body.name").value("Regression v4"))
                                .andExpect(jsonPath("$.body.metadata").doesNotExist())
                                .andExpect(jsonPath("$.body.metadata_key_id").doesNotExist())
                                .andExpect(jsonPath("$.body.metadata_key_type").doesNotExist());

                Resource stored = resourceRepository.findByDeletedFalse().get(0);
                assertThat(stored.getMetadata()).isNull();
                assertThat(stored.getMetadataKeyId()).isNull();
                assertThat(stored.getMetadataKeyType()).isNull();
        }

        @Test
        void testUpdateResource_V4_Regression_RenameOnly() throws Exception {
                Resource resource = createResourceWithPermission("Original", "original", null, Permission.UPDATE);

                ResourceDto.UpdateRequest request = ResourceDto.UpdateRequest.builder()
                                .name("Renamed v4")
                                .build();

                mockMvc.perform(put("/resources/" + resource.getId() + ".json")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.body.name").value("Renamed v4"))
                                .andExpect(jsonPath("$.body.metadata").doesNotExist());

                Resource stored = resourceRepository.findById(resource.getId()).orElseThrow();
                assertThat(stored.getName()).isEqualTo("Renamed v4");
                assertThat(stored.getMetadata()).isNull();
        }

        @Test
        void testCreateResource_V5_RejectedWhenSettingsDisallow() throws Exception {
                // No metadataTypes org-setting row -> allow_creation_of_v5_resources
                // defaults to false. The service throws; ResourceController wraps any
                // service exception as a 400 envelope.
                MetadataKey key = seedActiveSharedKey();

                ResourceDto.CreateRequest request = v5CreateRequest(key.getId(), "shared_key");

                mockMvc.perform(post("/resources")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message")
                                                .value(Matchers.containsString("prevent from creating a V5 resource")));

                assertThat(resourceRepository.findByDeletedFalse()).isEmpty();
        }

        @Test
        void testCreateResource_V5_BadMetadataKeyType_BadRequest() throws Exception {
                enableV5ResourceCreation();
                MetadataKey key = seedActiveSharedKey();

                ResourceDto.CreateRequest request = v5CreateRequest(key.getId(), "not_a_valid_type");

                mockMvc.perform(post("/resources")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));

                assertThat(resourceRepository.findByDeletedFalse()).isEmpty();
        }

        @Test
        void testCreateResource_PartialV5_BadRequest() throws Exception {
                enableV5ResourceCreation();
                // metadata + metadata_key_id but NO metadata_key_type -> partial-v5.
                // PHP MetadataResourceDto::validateRequestPayload reports the missing
                // fields with a dedicated message (not the key-type / expiry message).
                MetadataKey key = seedActiveSharedKey();

                ResourceDto.CreateRequest request = ResourceDto.CreateRequest.builder()
                                .metadata(ARMORED)
                                .metadataKeyId(key.getId())
                                .resourceTypeId(UUID.randomUUID().toString())
                                .build();

                mockMvc.perform(post("/resources")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message")
                                                .value(Matchers.containsString("Few fields are missing for the V5")));

                assertThat(resourceRepository.findByDeletedFalse()).isEmpty();
        }

        @Test
        void testCreateResource_PartialAndMixedV5_ReportsMissingFieldsFirst() throws Exception {
                enableV5ResourceCreation();
                // Simultaneously PARTIAL (no metadata_key_type) AND MIXED (a v4 name).
                // PHP checks partial-v5 BEFORE the superfluous-v4 (mixed) check, so the
                // "Few fields are missing for the V5." message must win.
                MetadataKey key = seedActiveSharedKey();

                ResourceDto.CreateRequest request = ResourceDto.CreateRequest.builder()
                                .name("v4 name present")
                                .metadata(ARMORED)
                                .metadataKeyId(key.getId())
                                .resourceTypeId(UUID.randomUUID().toString())
                                .build();

                mockMvc.perform(post("/resources")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"))
                                .andExpect(jsonPath("$.header.message")
                                                .value(Matchers.containsString("Few fields are missing for the V5")));

                assertThat(resourceRepository.findByDeletedFalse()).isEmpty();
        }

        @Test
        void testCreateResource_V5_MalformedMetadataBlob_BadRequest() throws Exception {
                enableV5ResourceCreation();
                MetadataKey key = seedActiveSharedKey();

                ResourceDto.CreateRequest request = ResourceDto.CreateRequest.builder()
                                .metadata("this is not an armored pgp message")
                                .metadataKeyId(key.getId())
                                .metadataKeyType("shared_key")
                                .resourceTypeId(UUID.randomUUID().toString())
                                .build();

                mockMvc.perform(post("/resources")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));

                assertThat(resourceRepository.findByDeletedFalse()).isEmpty();
        }

        @Test
        void testCreateResource_V5_UnknownMetadataKeyId_BadRequest() throws Exception {
                enableV5ResourceCreation();
                // No metadata key seeded with this id -> MetadataKeyIdNotExpiredRule fails.
                ResourceDto.CreateRequest request = v5CreateRequest(UUID.randomUUID().toString(), "shared_key");

                mockMvc.perform(post("/resources")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));

                assertThat(resourceRepository.findByDeletedFalse()).isEmpty();
        }

        @Test
        void testCreateResource_V5_ExpiredMetadataKeyId_BadRequest() throws Exception {
                enableV5ResourceCreation();
                MetadataKey expired = seedExpiredSharedKey();

                ResourceDto.CreateRequest request = v5CreateRequest(expired.getId(), "shared_key");

                mockMvc.perform(post("/resources")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.header.status").value("error"));

                assertThat(resourceRepository.findByDeletedFalse()).isEmpty();
        }

        // ----------------------------------------------------------------
        // v5 helpers
        // ----------------------------------------------------------------

        private ResourceDto.CreateRequest v5CreateRequest(String metadataKeyId, String metadataKeyType) {
                return ResourceDto.CreateRequest.builder()
                                .metadata(ARMORED)
                                .metadataKeyId(metadataKeyId)
                                .metadataKeyType(metadataKeyType)
                                .resourceTypeId(UUID.randomUUID().toString())
                                .build();
        }

        /** Seed the metadataTypes org-setting flipping v5 resource creation ON. */
        private void enableV5ResourceCreation() {
                OrganizationSetting setting = new OrganizationSetting();
                setting.setProperty("metadataTypes");
                setting.setPropertyId(UUID.randomUUID().toString());
                setting.setValue("{\"allow_creation_of_v5_resources\":true}");
                setting.setCreatedBy(testUser.getId());
                setting.setModifiedBy(testUser.getId());
                organizationSettingRepository.save(setting);
        }

        private MetadataKey seedActiveSharedKey() {
                MetadataKey key = new MetadataKey();
                key.setFingerprint(randomFingerprint());
                key.setArmoredKey("-----BEGIN PGP PUBLIC KEY BLOCK-----");
                key.setCreatedBy(testUser.getId());
                key.setModifiedBy(testUser.getId());
                return metadataKeyRepository.save(key);
        }

        private MetadataKey seedExpiredSharedKey() {
                MetadataKey key = new MetadataKey();
                key.setFingerprint(randomFingerprint());
                key.setArmoredKey("-----BEGIN PGP PUBLIC KEY BLOCK-----");
                key.setExpired(java.time.LocalDateTime.now().minusDays(1));
                key.setCreatedBy(testUser.getId());
                key.setModifiedBy(testUser.getId());
                return metadataKeyRepository.save(key);
        }

        /** A persisted v5 resource (metadata trio) + OWNER permission for the test user. */
        private Resource createV5ResourceWithPermission(String metadataKeyId) {
                Resource resource = new Resource();
                resource.setMetadata(ARMORED);
                resource.setMetadataKeyId(metadataKeyId);
                resource.setMetadataKeyType("shared_key");
                resource.setResourceTypeId(UUID.randomUUID().toString());
                resource.setCreatedBy(testUser.getId());
                resource.setModifiedBy(testUser.getId());
                resource.setDeleted(false);
                resourceRepository.save(resource);

                Permission perm = new Permission();
                perm.setAco(Permission.RESOURCE_ACO);
                perm.setAcoForeignKey(resource.getId());
                perm.setAro(Permission.USER_ARO);
                perm.setAroForeignKey(testUser.getId());
                perm.setType(Permission.OWNER);
                permissionRepository.save(perm);

                return resource;
        }

        /** 40-hex-char fingerprint (column is varchar(51) unique). */
        private String randomFingerprint() {
                return (UUID.randomUUID().toString() + UUID.randomUUID().toString())
                                .replace("-", "").substring(0, 40).toUpperCase();
        }
}
