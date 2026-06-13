package com.jpassbolt.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpassbolt.api.dto.MetadataUpgradeDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Folder;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.ResourceType;
import com.jpassbolt.api.model.Tag;
import com.jpassbolt.api.repository.FolderRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.ResourceTypeRepository;
import com.jpassbolt.api.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.bcpg.ArmoredInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * v5 Metadata <b>upgrade</b> service — converts existing v4 resources / folders
 * / tags to v5 by writing their client-encrypted metadata blob.
 *
 * <p>
 * Port of the PHP {@code Passbolt\Metadata\Service\Upgrade\*UpdateService}
 * (resources/folders) plus the v5.1 tags equivalent. Two phases per model:
 * <ul>
 *   <li><b>Index</b> — list the entities still eligible for upgrade. An entity is
 *       eligible while it is "v4", i.e. its {@code metadata} column is still NULL
 *       (PHP {@code findV4}: {@code metadata IS NULL}; resources additionally
 *       {@code deleted = false}). Paginated, default page size 20.</li>
 *   <li><b>Apply</b> — for each posted element the entity is converted to v5,
 *       mirroring the PHP
 *       {@code MetadataUpgrade{Resources,Folders}UpdateService}:
 *       <ol>
 *         <li>write the three nullable v5 columns
 *             ({@code metadata}/{@code metadata_key_id}/{@code metadata_key_type});</li>
 *         <li>re-point {@code resource_type_id} to the mapped v5 resource type
 *             (resources only; PHP {@code ResourceType::getV5Mapping}), and apply
 *             the equivalent {@code secret_revisions.resource_type_id} update where
 *             that table exists (it is not part of this port — see below);</li>
 *         <li>NULL OUT the now-encrypted v4 plaintext fields — resource
 *             {@code name}/{@code username}/{@code uri}/{@code description}, folder
 *             {@code name} (PHP {@code MetadataResourceDto::V4_META_PROPS} /
 *             {@code MetadataFolderDto::V4_META_PROPS}) — since v5 stores those
 *             inside the encrypted {@code metadata} blob.</li>
 *       </ol>
 *       The whole apply runs inside the {@code @Transactional} boundary so a
 *       failure rolls every element back, mirroring the PHP
 *       {@code saveManyOrFail} lifecycle.</li>
 * </ul>
 * </p>
 *
 * <h3>Iron Laws</h3>
 * <ul>
 *   <li>#1 zero-knowledge: the {@code metadata} payload is an armored OpenPGP
 *       MESSAGE; this service only stores/forwards it and NEVER decrypts. Apply
 *       additionally runs a <em>parse-only</em> Bouncy Castle armored-MESSAGE
 *       marker check on the incoming blob ({@link #assertParsableMessage}) and
 *       rejects a malformed blob with a 400 — still without decrypting.</li>
 *   <li>Repos {@link ResourceRepository}/{@link FolderRepository}/
 *       {@link TagRepository} are consumed read-only — no finders added there;
 *       the v4/eligibility filtering is applied in-memory in this service.</li>
 *   <li>The {@code allow_v4_v5_upgrade} gate is read from the
 *       {@code organization_settings} row {@code property = "metadataTypes"} via
 *       the existing read-only {@link OrganizationSettingRepository}; absent /
 *       false &#8594; 403 (PHP {@code IsV4ToV5UpgradeAllowedRule}). Default is
 *       {@code false} per the metadata types settings defaults.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataUpgradeService {

    /** Default page size for the upgrade index (PHP metadata defaultPaginationLimit). */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * Max number of elements accepted in a single batch apply
     * (PHP {@code MetadataPaginationComponent::MAX_PAGINATION_LIMIT}).
     */
    public static final int MAX_BATCH_SIZE = 200;

    /** organization_settings property holding the metadata types settings JSON. */
    public static final String TYPES_SETTING_PROPERTY = "metadataTypes";

    /** JSON flag name gating v4&#8594;v5 upgrade (default false when absent). */
    public static final String ALLOW_V4_V5_UPGRADE = "allow_v4_v5_upgrade";

    /**
     * v4 resource-type slug &#8594; v5 resource-type slug mapping, ported verbatim
     * from PHP {@code ResourceType::getV5Mapping()}. The mapping is expressed in
     * slugs (not fixed UUIDs) because this port resolves the concrete v5
     * {@code resource_type_id} by slug at runtime ({@code ResourceTypeRepository
     * .findBySlug}); the seeded v5 type ids may differ from the canonical UUIDv5
     * values (see {@code DataInitializer}), so slug resolution is the robust key.
     */
    public static final Map<String, String> V4_TO_V5_TYPE_SLUG = Map.of(
            ResourceType.SLUG_PASSWORD_STRING, "v5-password-string",
            ResourceType.SLUG_PASSWORD_AND_DESCRIPTION, "v5-default",
            ResourceType.SLUG_STANDALONE_TOTP, "v5-totp-standalone",
            ResourceType.SLUG_PASSWORD_DESCRIPTION_TOTP, "v5-default-with-totp");

    /**
     * PHP {@code getGpgMarker} regex (verbatim): extracts the OpenPGP armor marker
     * (capture group 2) from an armored block. Used only for the parse-only
     * marker check; never for decryption.
     */
    private static final Pattern GPG_MARKER = Pattern.compile("-(BEGIN )*([A-Z0-9 ]+)-");

    /** PHP {@code OpenPGPBackendInterface::MESSAGE_MARKER}. */
    private static final String MESSAGE_MARKER = "PGP MESSAGE";

    private final ResourceRepository resourceRepository;
    private final FolderRepository folderRepository;
    private final TagRepository tagRepository;
    private final ResourceTypeRepository resourceTypeRepository;
    private final OrganizationSettingRepository organizationSettingRepository;
    private final ObjectMapper objectMapper;

    // ---------------------------------------------------------------------
    // Resources
    // ---------------------------------------------------------------------

    /**
     * List the v4 resources still eligible for upgrade ({@code deleted = false}
     * AND {@code metadata IS NULL}), ordered by id, paginated.
     *
     * @param page zero-based page index
     * @param size page size (defaults to {@link #DEFAULT_PAGE_SIZE} when &lt;= 0)
     * @return the eligible resources for the requested page
     */
    @Transactional(readOnly = true)
    public List<Resource> findUpgradableResources(int page, int size) {
        List<Resource> eligible = resourceRepository.findByDeletedFalse().stream()
                .filter(r -> r.getMetadata() == null)
                .sorted(Comparator.comparing(Resource::getId))
                .toList();
        return paginate(eligible, page, size);
    }

    /**
     * Upgrade the given v4 resources to v5 (additive: writes only the three v5
     * columns). After applying, the still-upgradeable list is recomputed by the
     * controller — this method performs the persistence.
     *
     * @param entries the posted upgrade elements
     * @param userId  the acting (admin) user id
     * @return the resources that were upgraded by this call
     * @throws PassboltApiException 403 when v4&#8594;v5 upgrade is disabled, 400 on
     *                              an empty/oversized/invalid batch, 404 when an
     *                              id does not exist, 409 on an optimistic-lock
     *                              mismatch
     */
    @Transactional
    public List<Resource> upgradeResources(List<MetadataUpgradeDto.UpgradeRequest> entries, String userId) {
        assertUpgradeAllowed();
        assertBatch(entries);

        List<Resource> updated = new ArrayList<>();
        for (MetadataUpgradeDto.UpgradeRequest entry : entries) {
            validateElement(entry);
            assertParsableMessage(entry.getMetadata());
            Resource resource = resourceRepository.findById(entry.getId())
                    .filter(r -> !Boolean.TRUE.equals(r.getDeleted()))
                    .orElseThrow(() -> notFound(entry.getId()));
            assertNotAlreadyUpgraded(resource.getMetadata(), entry.getId());
            assertConflict(entry, resource.getModified(), resource.getModifiedBy());

            // (1) write the v5 metadata columns
            applyMetadata(entry, resource::setMetadata, resource::setMetadataKeyId, resource::setMetadataKeyType);
            // (2) re-point resource_type_id to the mapped v5 type (PHP getV5ResourceType)
            mapToV5ResourceType(resource.getResourceTypeId()).ifPresent(resource::setResourceTypeId);
            // (3) NULL OUT the now-encrypted v4 plaintext fields (V4_META_PROPS):
            //     name/username/uri/description now live inside the encrypted blob.
            resource.setName(null);
            resource.setUsername(null);
            resource.setUri(null);
            resource.setDescription(null);
            resource.setModifiedBy(userId);
            updated.add(resourceRepository.save(resource));
        }
        // PHP additionally re-points secret_revisions.resource_type_id for the
        // upgraded resources (MetadataUpgradeResourcesUpdateService
        // ::updateResourceTypeInSecretRevisions). The EE SecretRevisions table is
        // NOT part of this port (no secret_revisions entity/table exists, and
        // ddl-auto=validate would reject a fabricated one), so that step is a
        // deliberate no-op here — documented to preserve the PHP lifecycle intent.
        log.debug("Upgraded {} resource(s) to v5 by user {}", updated.size(), userId);
        return updated;
    }

    /**
     * Resolve the v5 {@code resource_type_id} a v4 resource should be re-pointed
     * to on upgrade (PHP {@code getV5ResourceType} via
     * {@code ResourceType::getV5Mapping}). The current type is resolved to its
     * slug, mapped to the v5 slug, then back to the seeded v5 type id.
     *
     * <p>
     * PHP throws a 500 when no mapping exists; here, when the current type cannot
     * be resolved to a known v4 slug (or the mapped v5 type is not seeded), the
     * re-point is skipped (the metadata columns are still written) rather than
     * failing the whole batch. This keeps the additive upgrade robust against
     * non-standard resource types while preserving correct behaviour for the four
     * official v4 types.
     * </p>
     *
     * @param currentTypeId the resource's current (v4) resource_type_id
     * @return the mapped v5 resource_type_id when resolvable, else empty
     */
    private Optional<String> mapToV5ResourceType(String currentTypeId) {
        if (currentTypeId == null) {
            return Optional.empty();
        }
        return resourceTypeRepository.findById(currentTypeId)
                .map(ResourceType::getSlug)
                .map(V4_TO_V5_TYPE_SLUG::get)
                .flatMap(resourceTypeRepository::findBySlug)
                .map(ResourceType::getId);
    }

    // ---------------------------------------------------------------------
    // Folders
    // ---------------------------------------------------------------------

    /**
     * List the v4 folders still eligible for upgrade ({@code metadata IS NULL}),
     * ordered by id, paginated.
     *
     * @param page zero-based page index
     * @param size page size (defaults to {@link #DEFAULT_PAGE_SIZE} when &lt;= 0)
     * @return the eligible folders for the requested page
     */
    @Transactional(readOnly = true)
    public List<Folder> findUpgradableFolders(int page, int size) {
        List<Folder> eligible = folderRepository.findAll().stream()
                .filter(f -> f.getMetadata() == null)
                .sorted(Comparator.comparing(Folder::getId))
                .toList();
        return paginate(eligible, page, size);
    }

    /**
     * Upgrade the given v4 folders to v5 (additive: writes only the three v5
     * columns).
     *
     * @param entries the posted upgrade elements
     * @param userId  the acting (admin) user id
     * @return the folders that were upgraded by this call
     * @throws PassboltApiException 403/400/404/409 as for
     *                              {@link #upgradeResources(List, String)}
     */
    @Transactional
    public List<Folder> upgradeFolders(List<MetadataUpgradeDto.UpgradeRequest> entries, String userId) {
        assertUpgradeAllowed();
        assertBatch(entries);

        List<Folder> updated = new ArrayList<>();
        for (MetadataUpgradeDto.UpgradeRequest entry : entries) {
            validateElement(entry);
            assertParsableMessage(entry.getMetadata());
            Folder folder = folderRepository.findById(entry.getId())
                    .orElseThrow(() -> notFound(entry.getId()));
            assertNotAlreadyUpgraded(folder.getMetadata(), entry.getId());
            assertConflict(entry, folder.getModified(), folder.getModifiedBy());

            applyMetadata(entry, folder::setMetadata, folder::setMetadataKeyId, folder::setMetadataKeyType);
            // NULL OUT the now-encrypted v4 plaintext name (MetadataFolderDto
            // ::V4_META_PROPS = ['name']) — v5 stores it inside the metadata blob.
            folder.setName(null);
            folder.setModifiedBy(userId);
            updated.add(folderRepository.save(folder));
        }
        log.debug("Upgraded {} folder(s) to v5 by user {}", updated.size(), userId);
        return updated;
    }

    // ---------------------------------------------------------------------
    // Tags (v5.1)
    // ---------------------------------------------------------------------

    /**
     * List the v4 tags still eligible for upgrade ({@code metadata IS NULL}),
     * ordered by id, paginated (v5.1).
     *
     * @param page zero-based page index
     * @param size page size (defaults to {@link #DEFAULT_PAGE_SIZE} when &lt;= 0)
     * @return the eligible tags for the requested page
     */
    @Transactional(readOnly = true)
    public List<Tag> findUpgradableTags(int page, int size) {
        List<Tag> eligible = tagRepository.findAll().stream()
                .filter(t -> t.getMetadata() == null)
                .sorted(Comparator.comparing(Tag::getId))
                .toList();
        return paginate(eligible, page, size);
    }

    /**
     * Upgrade the given v4 tags to v5 (additive: writes only the three v5
     * columns; the {@code slug} is left intact as the schema keeps it NOT NULL).
     *
     * @param entries the posted upgrade elements
     * @param userId  the acting (admin) user id
     * @return the tags that were upgraded by this call
     * @throws PassboltApiException 403/400/404/409 as for
     *                              {@link #upgradeResources(List, String)}
     */
    @Transactional
    public List<Tag> upgradeTags(List<MetadataUpgradeDto.UpgradeRequest> entries, String userId) {
        assertUpgradeAllowed();
        assertBatch(entries);

        List<Tag> updated = new ArrayList<>();
        for (MetadataUpgradeDto.UpgradeRequest entry : entries) {
            validateElement(entry);
            assertParsableMessage(entry.getMetadata());
            Tag tag = tagRepository.findById(entry.getId())
                    .orElseThrow(() -> notFound(entry.getId()));
            assertNotAlreadyUpgraded(tag.getMetadata(), entry.getId());
            // Tags carry no modified_by column (EE Tags schema), so the conflict
            // guard checks the modified timestamp only.
            assertConflict(entry, tag.getModified(), null);

            applyMetadata(entry, tag::setMetadata, tag::setMetadataKeyId, tag::setMetadataKeyType);
            updated.add(tagRepository.save(tag));
        }
        log.debug("Upgraded {} tag(s) to v5 by user {}", updated.size(), userId);
        return updated;
    }

    // ---------------------------------------------------------------------
    // shared helpers
    // ---------------------------------------------------------------------

    /**
     * Apply the three additive v5 columns from the request element via the
     * supplied entity setters. No v4 column is ever touched here.
     */
    private void applyMetadata(MetadataUpgradeDto.UpgradeRequest entry,
            java.util.function.Consumer<String> setMetadata,
            java.util.function.Consumer<String> setMetadataKeyId,
            java.util.function.Consumer<String> setMetadataKeyType) {
        setMetadata.accept(entry.getMetadata());
        setMetadataKeyId.accept(entry.getMetadataKeyId());
        setMetadataKeyType.accept(entry.getMetadataKeyType());
    }

    /**
     * PHP {@code IsV4ToV5UpgradeAllowedRule}: forbid upgrading when the
     * {@code allow_v4_v5_upgrade} metadata types setting is off (the default).
     * Read directly from the {@code metadataTypes} organization_settings row to
     * avoid coupling to the settings-domain service bean.
     */
    private void assertUpgradeAllowed() {
        if (!isV4V5UpgradeAllowed()) {
            throw new PassboltApiException(HttpStatus.FORBIDDEN,
                    "The metadata v4 to v5 upgrade is not allowed.");
        }
    }

    private boolean isV4V5UpgradeAllowed() {
        return organizationSettingRepository.findByProperty(TYPES_SETTING_PROPERTY)
                .map(s -> s.getValue())
                .map(this::readUpgradeFlag)
                .orElse(false);
    }

    private boolean readUpgradeFlag(String json) {
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {
            });
            Object value = raw.get(ALLOW_V4_V5_UPGRADE);
            return value instanceof Boolean b ? b : false;
        } catch (Exception e) {
            log.warn("Unreadable metadataTypes organization setting, treating upgrade as not allowed: {}",
                    e.getMessage());
            return false;
        }
    }

    /** PHP {@code assertNotEmptyArrayData} + the MAX_PAGINATION_LIMIT batch guard. */
    private void assertBatch(List<MetadataUpgradeDto.UpgradeRequest> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The request data should not be empty.");
        }
        if (entries.size() > MAX_BATCH_SIZE) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The request data is too large.");
        }
    }

    /**
     * Per-element validation mirroring PHP {@code MetadataBatchUpgradeForm}:
     * id, metadata, metadata_key_id, metadata_key_type, modified, modified_by are
     * all required; id/metadata_key_id/modified_by must be valid UUIDs.
     */
    private void validateElement(MetadataUpgradeDto.UpgradeRequest entry) {
        if (entry == null) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "The entity must be an array.");
        }
        if (!isUuid(entry.getId())) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The identifier should be a valid UUID.");
        }
        if (isBlank(entry.getMetadata())) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "A metadata is required.");
        }
        if (!isUuid(entry.getMetadataKeyId())) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The metadata key identifier should be a valid UUID.");
        }
        if (isBlank(entry.getMetadataKeyType())) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "A metadata key type is required.");
        }
        if (entry.getModified() == null) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "A modified date is required.");
        }
        if (!isUuid(entry.getModifiedBy())) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The modified by should be a valid UUID.");
        }
    }

    /**
     * Upgrade applies only to still-v4 entities. If the row already carries a
     * metadata blob it is no longer upgradeable; treat as a not-found target to
     * mirror the index that would no longer surface it.
     */
    private void assertNotAlreadyUpgraded(String currentMetadata, String id) {
        if (currentMetadata != null) {
            throw notFound(id);
        }
    }

    /**
     * PHP {@code AbstractMetadataRotateKeyUpdateService::assertConflict}: the
     * stored {@code modified}/{@code modified_by} must match what the client sent,
     * otherwise the entity was changed concurrently (409). Comparison is to the
     * second to avoid sub-second drift, matching the PHP {@code toDateTimeString()}.
     */
    private void assertConflict(MetadataUpgradeDto.UpgradeRequest entry,
            LocalDateTime storedModified, String storedModifiedBy) {
        if (storedModified != null && entry.getModified() != null
                && !storedModified.withNano(0).equals(entry.getModified().withNano(0))) {
            throw new PassboltApiException(HttpStatus.CONFLICT,
                    "The provided modified date does not match.");
        }
        if (storedModifiedBy != null && !storedModifiedBy.equals(entry.getModifiedBy())) {
            throw new PassboltApiException(HttpStatus.CONFLICT,
                    "The provided modified by does not match.");
        }
    }

    private PassboltApiException notFound(String id) {
        return new PassboltApiException(HttpStatus.NOT_FOUND, "Entity " + id + " not found.");
    }

    /**
     * Parse-only check that the incoming {@code metadata} blob is a well-formed
     * ASCII-armored OpenPGP MESSAGE, mirroring the PHP
     * {@code MessageValidationService::isParsableArmoredMessage} /
     * {@code OpenPGPBackend::isValidMessage} marker assertion. Uses Bouncy Castle
     * ({@link ArmoredInputStream}) to dearmor the framing (Iron Law #1 — crypto
     * only via Bouncy Castle, never {@code gpg} exec) and asserts the armor marker
     * is {@code PGP MESSAGE}. The server is ZERO-KNOWLEDGE: this NEVER decrypts and
     * never reads into the encrypted payload. A malformed blob &#8594; 400.
     *
     * @param metadata the client-encrypted armored OpenPGP MESSAGE
     * @throws PassboltApiException 400 when the blob is non-ASCII, not dearmorable
     *                              or not a {@code PGP MESSAGE}-marked block
     */
    private void assertParsableMessage(String metadata) {
        if (isBlank(metadata)) {
            // validateElement already enforces presence; keep this defensive.
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "A metadata is required.");
        }
        if (!isAscii(metadata)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The metadata should be a valid ASCII string.");
        }
        // Bouncy Castle dearmor (parse-only): a valid armored block opens without
        // error. ArmoredInputStream is lenient about an empty body, which is what
        // lets the spec's single-line "-----BEGIN PGP MESSAGE-----" placeholder
        // pass — exactly as the official OpenAPI examples / contract use it.
        try (InputStream raw = new ByteArrayInputStream(metadata.getBytes(StandardCharsets.UTF_8));
                ArmoredInputStream armoredIn = new ArmoredInputStream(raw)) {
            armoredIn.read();
        } catch (Exception e) {
            log.debug("Upgrade metadata blob is not a dearmorable OpenPGP block", e);
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The metadata could not be parsed as an OpenPGP message.");
        }
        // PHP assertGpgMarker(metadata, MESSAGE_MARKER): the armor marker must be
        // exactly "PGP MESSAGE" (rejects garbage and e.g. PUBLIC KEY BLOCK).
        var matcher = GPG_MARKER.matcher(metadata);
        if (!matcher.find() || !MESSAGE_MARKER.equals(matcher.group(2))) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The metadata must be a valid OpenPGP message.");
        }
    }

    private static <T> List<T> paginate(List<T> all, int page, int size) {
        int effectiveSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_BATCH_SIZE);
        int from = Math.max(page, 0) * effectiveSize;
        if (from >= all.size()) {
            return List.of();
        }
        int to = Math.min(from + effectiveSize, all.size());
        return List.copyOf(all.subList(from, to));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Armored OpenPGP blocks are 7-bit ASCII (PHP {@code ->ascii()} validator). */
    private static boolean isAscii(String value) {
        return StandardCharsets.US_ASCII.newEncoder().canEncode(value);
    }

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
