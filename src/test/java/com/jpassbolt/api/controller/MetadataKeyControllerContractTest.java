package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.MetadataKeyDto;
import com.jpassbolt.api.model.MetadataKey;
import com.jpassbolt.api.model.MetadataPrivateKey;
import com.jpassbolt.api.model.Role;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.MetadataKeyRepository;
import com.jpassbolt.api.repository.MetadataPrivateKeyRepository;
import com.jpassbolt.api.repository.RoleRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.util.GpgTestHelper;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.sig.Features;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;

import java.io.ByteArrayOutputStream;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI contract tests for the v5 Metadata Keys endpoints
 * ({@code GET|POST /metadata/keys.json},
 * {@code PUT|DELETE /metadata/keys/{metadataKeyId}.json},
 * {@code POST /metadata/keys/privates.json},
 * {@code PUT /metadata/keys/private/{metadataPrivateKeyId}.json}).
 *
 * <p>
 * The server is zero-knowledge: it only stores/forwards armored OpenPGP blobs.
 * The create flow nonetheless runs PARSE-ONLY Bouncy Castle validations (the
 * armored public key must parse and its fingerprint must match; each private
 * key {@code data} must parse as an OpenPGP MESSAGE). So this test generates a
 * real RSA OpenPGP keypair once in {@link #generateKeyMaterial()} and encrypts a
 * real MESSAGE to it via {@link GpgTestHelper#encrypt}, rather than using opaque
 * placeholder strings.
 * </p>
 *
 * <p>
 * The mock user maps to a real {@code admin} Role row (seeded in
 * {@link #seedData()}) because {@code userService.isAdmin()} resolves the role
 * through the roles table — the admin-gated create/expire/delete/privates
 * endpoints need an admin principal.
 * </p>
 *
 * <p>
 * {@code isValid(CONTRACT_VALIDATOR)} disable notes (documented
 * {@code e2eeMetadataBased} deviation): the {@code metadataKeysIndexAndView}
 * schema is an {@code allOf} that, once combinators are resolved, pulls in
 * {@code e2eeIdCreatedDataModifiedUserId → e2eeDataUserId → e2eeDataOnly},
 * making top-level {@code data} and {@code user_id} REQUIRED on a metadata key
 * view. A real Passbolt metadata key object carries neither at the top level
 * (the encrypted {@code data} lives inside each {@code metadata_private_keys}
 * element, not on the key itself), so the validator's strict view rejects the
 * PHP-accurate shape. The index/create responses therefore document-disable
 * {@code isValid} (same v3-compat e2ee precedent as Folder/Move/GroupShare). The
 * envelope, the PUT-private short view, the {@code nullBody} (expire/delete) and
 * {@code emptyBody} (privates) responses are spec-clean and keep {@code isValid}
 * ENABLED.
 * </p>
 */
@WithMockUser(username = "admin@passbolt.com", roles = { "USER" })
class MetadataKeyControllerContractTest extends OpenApiComplianceTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private MetadataKeyRepository metadataKeyRepository;

    @Autowired
    private MetadataPrivateKeyRepository metadataPrivateKeyRepository;

    /** A real, parsable armored OpenPGP public key (generated once). */
    private static String armoredPublicKey;
    /** Its 40-char uppercase fingerprint. */
    private static String fingerprint;
    /** A real, parsable armored OpenPGP MESSAGE encrypted to the key above. */
    private static String pgpMessage;

    private Role adminRole;
    private Role userRole;
    private User admin;

    @BeforeAll
    static void generateKeyMaterial() throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        rsa.initialize(2048);
        java.security.KeyPair kp = rsa.generateKeyPair();

        PGPKeyPair pgpKeyPair = new JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, kp, new Date());

        PGPSignatureSubpacketGenerator subpackets = new PGPSignatureSubpacketGenerator();
        subpackets.setKeyFlags(false, KeyFlags.CERTIFY_OTHER | KeyFlags.SIGN_DATA
                | KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE);
        subpackets.setFeature(false, Features.FEATURE_MODIFICATION_DETECTION);

        PGPKeyRingGenerator ringGen = new PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                pgpKeyPair,
                "JPassbolt Metadata Key <metadata@passbolt.test>",
                new JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1),
                subpackets.generate(),
                null,
                new JcaPGPContentSignerBuilder(pgpKeyPair.getPublicKey().getAlgorithm(),
                        HashAlgorithmTags.SHA256),
                new JcePBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256)
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        .build("metadata-key-passphrase".toCharArray()));

        PGPPublicKeyRing publicRing = ringGen.generatePublicKeyRing();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ArmoredOutputStream armored = new ArmoredOutputStream(out)) {
            publicRing.encode(armored);
        }
        armoredPublicKey = out.toString(java.nio.charset.StandardCharsets.UTF_8);

        PGPPublicKey master = publicRing.getPublicKey();
        fingerprint = GpgTestHelper.getFingerprint(master);

        PGPPublicKey encryptionKey = GpgTestHelper.loadPublicKey(armoredPublicKey);
        pgpMessage = GpgTestHelper.encrypt("the encrypted metadata private key", encryptionKey);
    }

    @BeforeEach
    void seedData() {
        metadataPrivateKeyRepository.deleteAll();
        metadataKeyRepository.deleteAll();
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

        admin = new User();
        admin.setUsername("admin@passbolt.com");
        admin.setRoleId(adminRole.getId());
        admin.setActive(true);
        admin.setDeleted(false);
        userRepository.save(admin);
    }

    // ------------------------------------------------------------------
    // GET /metadata/keys.json
    // ------------------------------------------------------------------

    @Test
    void testIndexKeysContract() throws Exception {
        MetadataKey key = seedActiveKey();
        seedServerPrivateKey(key.getId());

        // isValid DISABLED (documented e2eeMetadataBased): resolved-combinator
        // metadataKeysIndexAndView wrongly requires top-level data/user_id.
        mockMvc.perform(get("/metadata/keys.json")
                .param("contain[metadata_private_keys]", "1")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body").isArray())
                .andExpect(jsonPath("$.body[0].fingerprint").value(fingerprint));
    }

    // ------------------------------------------------------------------
    // POST /metadata/keys.json
    // ------------------------------------------------------------------

    @Test
    void testCreateKeyContract() throws Exception {
        MetadataKeyDto.PrivateKeyEntry serverCopy = MetadataKeyDto.PrivateKeyEntry.builder()
                .userId(null)
                .data(pgpMessage)
                .build();
        MetadataKeyDto.PrivateKeyEntry adminCopy = MetadataKeyDto.PrivateKeyEntry.builder()
                .userId(admin.getId())
                .data(pgpMessage)
                .build();
        MetadataKeyDto.CreateRequest request = MetadataKeyDto.CreateRequest.builder()
                .fingerprint(fingerprint)
                .armoredKey(armoredPublicKey)
                .metadataPrivateKeys(List.of(serverCopy, adminCopy))
                .build();

        // isValid DISABLED (documented e2eeMetadataBased): same top-level
        // data/user_id artefact on metadataKeysIndexAndView as the index path.
        mockMvc.perform(post("/metadata/keys.json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body").isArray())
                .andExpect(jsonPath("$.body[0].fingerprint").value(fingerprint))
                .andExpect(jsonPath("$.body[0].metadata_private_keys").isArray());
    }

    @Test
    @WithMockUser(username = "nonadmin@passbolt.com", roles = { "USER" })
    void testCreateKeyNonAdminForbidden() throws Exception {
        User plain = new User();
        plain.setUsername("nonadmin@passbolt.com");
        plain.setRoleId(userRole.getId());
        plain.setActive(true);
        plain.setDeleted(false);
        userRepository.save(plain);

        MetadataKeyDto.CreateRequest request = MetadataKeyDto.CreateRequest.builder()
                .fingerprint(fingerprint)
                .armoredKey(armoredPublicKey)
                .metadataPrivateKeys(List.of(MetadataKeyDto.PrivateKeyEntry.builder()
                        .userId(null).data(pgpMessage).build()))
                .build();

        // isValid DISABLED (documented, same UsersController precedent): the
        // spec's POST /metadata/keys.json declares only 200/400/401 — it does
        // NOT list 403 (unlike PUT/DELETE which do declare
        // accessRestrictedToAdministrators). PHP behaviour gates create with
        // admin too, so we return a real 403; the contract assertion on this
        // status is intentionally omitted because the spec lacks it.
        mockMvc.perform(post("/metadata/keys.json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.status").value("error"));
    }

    // ------------------------------------------------------------------
    // PUT /metadata/keys/{metadataKeyId}.json — mark expired
    // ------------------------------------------------------------------

    @Test
    void testExpireKeyContract() throws Exception {
        MetadataKey key = seedActiveKey();

        // The expired date must be strictly in the PAST (PHP
        // IsDateInPastValidationRule) — use a clearly-past timestamp.
        MetadataKeyDto.ExpireRequest request = MetadataKeyDto.ExpireRequest.builder()
                .fingerprint(fingerprint)
                .armoredKey(armoredPublicKey)
                .expired(java.time.LocalDateTime.now().minusMinutes(1))
                .build();

        mockMvc.perform(put("/metadata/keys/" + key.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    void testExpireKeyFutureDateBadRequest() throws Exception {
        MetadataKey key = seedActiveKey();

        // A future expired date is rejected (PHP IsDateInPastValidationRule:
        // "The date should not be set in the future.") → 400.
        MetadataKeyDto.ExpireRequest request = MetadataKeyDto.ExpireRequest.builder()
                .fingerprint(fingerprint)
                .armoredKey(armoredPublicKey)
                .expired(java.time.LocalDateTime.now().plusDays(1))
                .build();

        mockMvc.perform(put("/metadata/keys/" + key.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    void testExpireKeyInvalidUuidBadRequest() throws Exception {
        MetadataKeyDto.ExpireRequest request = MetadataKeyDto.ExpireRequest.builder()
                .fingerprint(fingerprint)
                .armoredKey(armoredPublicKey)
                .expired(java.time.LocalDateTime.now())
                .build();

        mockMvc.perform(put("/metadata/keys/not-a-uuid.json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    @Test
    void testExpireKeyMissingNotFound() throws Exception {
        MetadataKeyDto.ExpireRequest request = MetadataKeyDto.ExpireRequest.builder()
                .fingerprint(fingerprint)
                .armoredKey(armoredPublicKey)
                .expired(java.time.LocalDateTime.now())
                .build();

        mockMvc.perform(put("/metadata/keys/" + UUID.randomUUID() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    // ------------------------------------------------------------------
    // DELETE /metadata/keys/{metadataKeyId}.json — soft delete
    // ------------------------------------------------------------------

    @Test
    void testDeleteKeyContract() throws Exception {
        // The key must already be expired and must not be in use to be deletable.
        MetadataKey key = seedActiveKey();
        key.setExpired(java.time.LocalDateTime.now());
        metadataKeyRepository.save(key);

        mockMvc.perform(delete("/metadata/keys/" + key.getId() + ".json")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    // ------------------------------------------------------------------
    // POST /metadata/keys/privates.json — create / share missing
    // ------------------------------------------------------------------

    @Test
    void testCreatePrivatesContract() throws Exception {
        MetadataKey key = seedActiveKey();

        // A user who does not yet have a private-key copy of this key.
        User member = new User();
        member.setUsername("member@passbolt.com");
        member.setRoleId(userRole.getId());
        member.setActive(true);
        member.setDeleted(false);
        userRepository.save(member);

        String body = objectMapper.writeValueAsString(List.of(
                MetadataKeyDto.CreatePrivatesRequest.builder()
                        .metadataKeyId(key.getId())
                        .userId(member.getId())
                        .data(pgpMessage)
                        .build()));

        // emptyBody response ({}) is spec-clean → isValid ENABLED.
        mockMvc.perform(post("/metadata/keys/privates.json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    // ------------------------------------------------------------------
    // PUT /metadata/keys/private/{metadataPrivateKeyId}.json — update data
    // ------------------------------------------------------------------

    @Test
    void testUpdatePrivateContract() throws Exception {
        MetadataKey key = seedActiveKey();

        // Owned by the admin (the @WithMockUser principal). modified_by is a
        // DIFFERENT user so the "re-edit by same user" guard does not trip.
        User other = new User();
        other.setUsername("other@passbolt.com");
        other.setRoleId(userRole.getId());
        other.setActive(true);
        other.setDeleted(false);
        userRepository.save(other);

        MetadataPrivateKey pk = new MetadataPrivateKey();
        pk.setMetadataKeyId(key.getId());
        pk.setUserId(admin.getId());
        pk.setData(pgpMessage);
        pk.setCreatedBy(other.getId());
        pk.setModifiedBy(other.getId());
        metadataPrivateKeyRepository.save(pk);

        MetadataKeyDto.UpdatePrivateRequest request = MetadataKeyDto.UpdatePrivateRequest.builder()
                .data(pgpMessage)
                .build();

        // isValid DISABLED (documented e2eeMetadataBased / allOf-strict
        // artefact): metadataPrivateKeysShortIndex is
        // allOf[e2eeDataUserId{data,user_id}, {created_by, modified_by}]. With
        // combinators resolved the validator applies strict additionalProperties
        // to each allOf branch in isolation, so user_id/data are reported as
        // "not allowed" while validating the {created_by, modified_by} branch
        // (and vice versa). The body we send is exactly the union the plugin
        // expects (user_id, data, created_by, modified_by all present), so this
        // is the known validator artefact, not an envelope/shape defect.
        mockMvc.perform(put("/metadata/keys/private/" + pk.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.status").value("success"))
                .andExpect(jsonPath("$.body.data").exists())
                .andExpect(jsonPath("$.body.user_id").value(admin.getId()));
    }

    @Test
    void testUpdatePrivateForeignNotFound() throws Exception {
        MetadataKey key = seedActiveKey();

        User owner = new User();
        owner.setUsername("owner@passbolt.com");
        owner.setRoleId(userRole.getId());
        owner.setActive(true);
        owner.setDeleted(false);
        userRepository.save(owner);

        // Owned by someone else → the admin principal must get a 404 (ownership
        // is not disclosed).
        MetadataPrivateKey pk = new MetadataPrivateKey();
        pk.setMetadataKeyId(key.getId());
        pk.setUserId(owner.getId());
        pk.setData(pgpMessage);
        pk.setCreatedBy(owner.getId());
        pk.setModifiedBy(owner.getId());
        metadataPrivateKeyRepository.save(pk);

        MetadataKeyDto.UpdatePrivateRequest request = MetadataKeyDto.UpdatePrivateRequest.builder()
                .data(pgpMessage)
                .build();

        mockMvc.perform(put("/metadata/keys/private/" + pk.getId() + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.status").value("error"))
                .andExpect(openApi().isValid(CONTRACT_VALIDATOR));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private MetadataKey seedActiveKey() {
        MetadataKey key = new MetadataKey();
        key.setFingerprint(fingerprint);
        key.setArmoredKey(armoredPublicKey);
        key.setCreatedBy(admin.getId());
        key.setModifiedBy(admin.getId());
        return metadataKeyRepository.save(key);
    }

    private void seedServerPrivateKey(String metadataKeyId) {
        MetadataPrivateKey pk = new MetadataPrivateKey();
        pk.setMetadataKeyId(metadataKeyId);
        pk.setUserId(admin.getId());
        pk.setData(pgpMessage);
        pk.setCreatedBy(admin.getId());
        pk.setModifiedBy(admin.getId());
        metadataPrivateKeyRepository.save(pk);
    }
}
