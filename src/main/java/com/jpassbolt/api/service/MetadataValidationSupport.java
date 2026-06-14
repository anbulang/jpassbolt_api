package com.jpassbolt.api.service;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.repository.GpgKeyRepository;
import com.jpassbolt.api.repository.MetadataKeyRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.bcpg.ArmoredInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Shared, PARSE-ONLY validation support for the v4&#8596;v5 metadata fusion on
 * the Resource and Folder create/update paths.
 *
 * <p>
 * This is the single shared file that {@code ResourceService} and
 * {@code FolderService} both inject so the two feature streams never edit the
 * same source. It centralizes the four ported PHP v5 metadata rules plus the
 * lenient armored-MESSAGE marker check.
 * </p>
 *
 * <h3>Iron Laws</h3>
 * <ul>
 *   <li><b>#1 zero-knowledge</b>: the {@code metadata} payload is a
 *       client-encrypted armored OpenPGP MESSAGE. This class only STORES/FORWARDS
 *       it and NEVER decrypts. {@link #assertParsableMetadataMessage} performs a
 *       Bouncy Castle ({@link ArmoredInputStream}) dearmor of the armor framing
 *       and asserts the {@code PGP MESSAGE} marker — it never reads into the
 *       encrypted payload and never invokes {@code gpg}.</li>
 *   <li><b>#3 transport-only DTOs</b>: all business rules live here, not in the
 *       DTOs.</li>
 * </ul>
 *
 * <h3>Lenient marker variant (deliberate)</h3>
 * The marker check is copied verbatim from
 * {@code MetadataUpgradeService.assertParsableMessage}: a BC dearmor (lenient
 * about an empty body) plus a {@code PGP MESSAGE} armor-marker assertion. This
 * is chosen over the stricter
 * {@code MetadataKeyValidationService.assertParsableOpenPgpMessage} (which
 * requires a real {@code PGPEncryptedDataList}) BECAUSE the OpenAPI
 * examples/contract supply the single-line placeholder
 * {@code -----BEGIN PGP MESSAGE-----}; the lenient variant accepts it while
 * still rejecting garbage and {@code PUBLIC KEY BLOCK}.
 *
 * <h3>Scope boundary</h3>
 * The deeper {@code IsValidEncryptedMetadataRule} recipient-key-id match (parse
 * the message's single recipient key-id and compare it against the resolved
 * key's key-ids) is intentionally NOT ported here — the parse-only MESSAGE
 * assertion plus key existence/expiry is the faithful, contract-passing subset.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataValidationSupport {

    /** Metadata key type — personal (user GPG key). PHP {@code MetadataKey::TYPE_USER_KEY}. */
    public static final String TYPE_USER_KEY = "user_key";

    /** Metadata key type — shared metadata key. PHP {@code MetadataKey::TYPE_SHARED_KEY}. */
    public static final String TYPE_SHARED_KEY = "shared_key";

    /** PHP {@code OpenPGPBackend} armor marker regex. */
    private static final Pattern GPG_MARKER = Pattern.compile("-(BEGIN )*([A-Z0-9 ]+)-");

    /** PHP {@code OpenPGPBackendInterface::MESSAGE_MARKER}. */
    private static final String MESSAGE_MARKER = "PGP MESSAGE";

    private final MetadataKeysSettingsService metadataKeysSettingsService;
    private final MetadataKeyRepository metadataKeyRepository;
    private final GpgKeyRepository gpgKeyRepository;
    private final PermissionRepository permissionRepository;

    // ---------------------------------------------------------------------
    // (0) Structural v5-payload checks (PARTIAL-V5 then MIXED), PHP-faithful order
    // ---------------------------------------------------------------------

    /**
     * Partial-v5 structural guard, ported from PHP
     * {@code MetadataResourceDto::validateRequestPayload} /
     * {@code MetadataFolderDto::validate}: once a payload is v5 (the
     * {@code metadata} blob is present), ALL THREE v5 fields ({@code metadata},
     * {@code metadata_key_id}, {@code metadata_key_type}) must be supplied
     * together; any missing one yields the PHP message
     * {@code "Few fields are missing for the V5."}.
     *
     * <p>
     * Only MISSING (null/blank) fields are treated as partial-v5. An
     * INVALID-but-present value (e.g. a bogus {@code metadata_key_type}) passes
     * this check and is rejected later by {@link #assertMetadataKeyType} with the
     * distinct {@code "The metadata key type is not valid."} message.
     * </p>
     *
     * <p>
     * Per the PHP {@code validateRequestPayload} ordering this MUST run BEFORE
     * {@link #assertNoV4Fields} so a payload that is simultaneously partial AND
     * mixed reports the missing-fields error first.
     * </p>
     *
     * @param metadata        the v5 metadata blob (already known non-null at the
     *                        call site, included for parity/robustness)
     * @param metadataKeyId   the posted metadata_key_id
     * @param metadataKeyType the posted metadata_key_type
     * @throws PassboltApiException 400 when any of the three is null/blank
     */
    public void assertV5FieldsComplete(String metadata, String metadataKeyId, String metadataKeyType) {
        if (isBlank(metadata) || isBlank(metadataKeyId) || isBlank(metadataKeyType)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "Few fields are missing for the V5.");
        }
    }

    /**
     * Mixed-payload structural guard, ported from PHP
     * {@code MetadataResourceDto::validateRequestPayload} /
     * {@code MetadataFolderDto::validate}: a v5 payload may not also carry any v4
     * plaintext field; any present one yields the PHP message
     * {@code "V4 related fields are not supported for V5."}.
     *
     * <p>
     * Folders only have the v4 {@code name} field — {@code username}/{@code uri}/
     * {@code description} are simply passed as {@code null} from the folder path.
     * Per the PHP ordering this runs AFTER {@link #assertV5FieldsComplete}.
     * </p>
     *
     * @param name        the posted v4 name (null when absent)
     * @param username    the posted v4 username (null for folders / when absent)
     * @param uri         the posted v4 uri (null for folders / when absent)
     * @param description the posted v4 description (null for folders / when absent)
     * @throws PassboltApiException 400 when any v4 field is non-null
     */
    public void assertNoV4Fields(String name, String username, String uri, String description) {
        if (name != null || username != null || uri != null || description != null) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "V4 related fields are not supported for V5.");
        }
    }

    // ---------------------------------------------------------------------
    // (1) Parse-only armored-MESSAGE marker check (zero-knowledge, Iron Law #1)
    // ---------------------------------------------------------------------

    /**
     * PARSE-ONLY structural validation of a client-encrypted armored OpenPGP
     * MESSAGE blob. Copied from {@code MetadataUpgradeService.assertParsableMessage}
     * so the spec's single-line placeholder {@code -----BEGIN PGP MESSAGE-----}
     * passes. Uses Bouncy Castle to dearmor the framing (Iron Law #1 — crypto
     * only via Bouncy Castle, never {@code gpg} exec) and asserts the armor
     * marker is {@code PGP MESSAGE}. The server is ZERO-KNOWLEDGE: this NEVER
     * decrypts and never reads into the encrypted payload.
     *
     * @param metadata     the client-encrypted armored OpenPGP MESSAGE
     * @param errorMessage the item-specific 400 message for an undecryptable
     *                     blob (e.g. {@code "The resource metadata provided can
     *                     not be decrypted."} vs {@code "The folder metadata
     *                     provided cannot be decrypted."} — copied verbatim, note
     *                     the can-not/cannot difference)
     * @throws PassboltApiException 400 when the blob is blank, non-ASCII, not
     *                              dearmorable or not a {@code PGP MESSAGE} block
     */
    public void assertParsableMetadataMessage(String metadata, String errorMessage) {
        if (isBlank(metadata)) {
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
            log.debug("Metadata blob is not a dearmorable OpenPGP block", e);
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, errorMessage);
        }
        // PHP assertGpgMarker(metadata, MESSAGE_MARKER): the armor marker must be
        // exactly "PGP MESSAGE" (rejects garbage and e.g. PUBLIC KEY BLOCK).
        var matcher = GPG_MARKER.matcher(metadata);
        if (!matcher.find() || !MESSAGE_MARKER.equals(matcher.group(2))) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, errorMessage);
        }
    }

    // ---------------------------------------------------------------------
    // (2) metadata_key_type enum check {user_key, shared_key}
    // ---------------------------------------------------------------------

    /**
     * Assert {@code metadata_key_type} is one of the allowed enum values
     * {@code user_key} / {@code shared_key} (matches OpenAPI e2eeMetadataBased).
     *
     * @param type the posted metadata_key_type
     * @throws PassboltApiException 400 when the value is missing or not in the enum
     */
    public void assertMetadataKeyType(String type) {
        if (!TYPE_USER_KEY.equals(type) && !TYPE_SHARED_KEY.equals(type)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The metadata key type is not valid.");
        }
    }

    // ---------------------------------------------------------------------
    // (3) IsMetadataKeyTypeAllowedBySettingsRule
    // ---------------------------------------------------------------------

    /**
     * Port of {@code IsMetadataKeyTypeAllowedBySettingsRule}: when the type is
     * {@code user_key} the {@code allow_usage_of_personal_keys} setting must be
     * true (read from {@link MetadataKeysSettingsService}); {@code shared_key} is
     * always allowed.
     *
     * @param type the posted metadata_key_type (assumed already enum-validated)
     * @throws PassboltApiException 400 when {@code user_key} is used while
     *                              personal keys are disabled by the admin
     */
    public void assertMetadataKeyTypeAllowedBySettings(String type) {
        if (TYPE_USER_KEY.equals(type)) {
            Boolean allowed = metadataKeysSettingsService.getKeysSettings().getAllowUsageOfPersonalKeys();
            if (!Boolean.TRUE.equals(allowed)) {
                throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                        "The settings selected by your administrator prevent from using that key type.");
            }
        }
    }

    // ---------------------------------------------------------------------
    // (4) MetadataKeyIdNotExpiredRule
    // ---------------------------------------------------------------------

    /**
     * Port of {@code MetadataKeyIdNotExpiredRule}: the {@code metadata_key_id}
     * must be a valid UUID referencing an ACTIVE (not expired) key. For
     * {@code user_key} the personal GPG key must satisfy
     * {@code Gpgkeys.expires IS NULL}; for {@code shared_key} the metadata key
     * must satisfy {@code MetadataKeys.expired IS NULL} (and not be soft-deleted).
     *
     * @param type  the posted metadata_key_type (assumed already enum-validated)
     * @param keyId the posted metadata_key_id
     * @throws PassboltApiException 400 when the id is missing/not a UUID, the key
     *                              does not exist, or it is expired
     */
    public void assertMetadataKeyIdNotExpired(String type, String keyId) {
        final String expiredMessage = "The metadata key is marked as expired.";
        if (!isUuid(keyId)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, expiredMessage);
        }
        boolean active;
        if (TYPE_USER_KEY.equals(type)) {
            active = gpgKeyRepository.existsByIdAndExpiresIsNull(keyId);
        } else if (TYPE_SHARED_KEY.equals(type)) {
            active = metadataKeyRepository.existsByIdAndDeletedIsNullAndExpiredIsNull(keyId);
        } else {
            active = false;
        }
        if (!active) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, expiredMessage);
        }
    }

    // ---------------------------------------------------------------------
    // (5) IsMetadataKeyTypeSharedOnSharedItemRule (update path only)
    // ---------------------------------------------------------------------

    /**
     * Port of {@code IsMetadataKeyTypeSharedOnSharedItemRule} (an addUpdate rule,
     * invoked on the UPDATE path only — on create the item has no permissions
     * yet). A personal ({@code user_key}) item may NOT be shared: it may not have
     * more than one permission, nor may its sole permission be a Group ARO.
     * {@code shared_key} always passes.
     *
     * @param type          the posted metadata_key_type
     * @param acoForeignKey the item id (resource or folder) whose permissions are
     *                      inspected
     * @param errorMessage  the item-specific 400 message (e.g. {@code "A resource
     *                      of type personal cannot be shared with other users or a
     *                      group."} vs the folder variant)
     * @throws PassboltApiException 400 when a {@code user_key} item is shared
     */
    public void assertKeyTypeSharedOnSharedItem(String type, String acoForeignKey, String errorMessage) {
        if (!TYPE_USER_KEY.equals(type)) {
            return;
        }
        // Resolve the full permission set for the ACO across both ARO kinds.
        List<Permission> all = allPermissionsForAco(acoForeignKey);
        if (all.size() > 1) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, errorMessage);
        }
        if (all.size() == 1 && Permission.GROUP_ARO.equals(all.get(0).getAro())) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, errorMessage);
        }
    }

    /**
     * Every permission row of an ACO regardless of ARO kind. PHP queries
     * {@code Permissions} by {@code aco_foreign_key} only (limit 2) for this rule;
     * we union the User and Group ARO rows.
     */
    private List<Permission> allPermissionsForAco(String acoForeignKey) {
        List<Permission> userRows = permissionRepository
                .findByAcoForeignKeyAndAro(acoForeignKey, Permission.USER_ARO);
        List<Permission> groupRows = permissionRepository
                .findByAcoForeignKeyAndAro(acoForeignKey, Permission.GROUP_ARO);
        return java.util.stream.Stream.concat(userRows.stream(), groupRows.stream()).toList();
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

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
