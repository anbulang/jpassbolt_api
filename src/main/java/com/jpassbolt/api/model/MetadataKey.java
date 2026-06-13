package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * MetadataKey entity — a shared OpenPGP public key used by the v5 metadata
 * (zero-knowledge) system to encrypt resource/folder/tag metadata blobs.
 *
 * <p>
 * The server is ZERO-KNOWLEDGE for v5: it only STORES and FORWARDS the armored
 * public key here, and the per-user-encrypted copies of the matching private
 * key live in {@link MetadataPrivateKey}. The server never decrypts metadata.
 * </p>
 * <p>
 * Schema notes (must stay aligned with the official Passbolt v5 schema,
 * ddl-auto=validate on MySQL — see migration
 * {@code V4100CreateMetadataKeys}):
 * <ul>
 *   <li>{@code fingerprint} varchar(51) NOT NULL (NOT unique — the official
 *       migration declares no unique index on it, only created_by/modified_by
 *       plain indexes)</li>
 *   <li>{@code armored_key} TEXT NOT NULL</li>
 *   <li>{@code expired} datetime NULL — non-null marks the key as expired (no
 *       longer usable for new encryption); NULL = active</li>
 *   <li>{@code deleted} is a datetime soft-delete column (NOT a boolean):
 *       non-null marks the key deleted, NULL = active</li>
 *   <li>{@code created_by}/{@code modified_by} are NULLABLE uuids (per the
 *       official migration), with plain indexes (no FK constraints)</li>
 * </ul>
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "metadata_keys")
public class MetadataKey extends BaseEntity {

    /**
     * OpenPGP key fingerprint (40 hex chars; column sized 51 per official
     * schema). NOT unique: the official migration {@code V4100CreateMetadataKeys}
     * declares only plain {@code created_by}/{@code modified_by} indexes and NO
     * unique index on {@code fingerprint} (uniqueness is enforced by application
     * rules, not the schema). Mapping it as unique here would diverge from the
     * official DDL that ddl-auto=validate must match.
     */
    @Column(name = "fingerprint", nullable = false, length = 51)
    private String fingerprint;

    /** Armored OpenPGP public key block (stored verbatim, never parsed for crypto). */
    @Column(name = "armored_key", nullable = false, columnDefinition = "text")
    private String armoredKey;

    /** Expiry timestamp; NULL = active (not expired). */
    @Column(name = "expired")
    private LocalDateTime expired;

    /** Soft-delete timestamp (datetime, NOT boolean); NULL = active. */
    @Column(name = "deleted")
    private LocalDateTime deleted;

    @Column(name = "created_by", length = 36, columnDefinition = "char(36)")
    private String createdBy;

    @Column(name = "modified_by", length = 36, columnDefinition = "char(36)")
    private String modifiedBy;

    // --- Read-only navigation (the String columns above are the writable
    // track). NO_CONSTRAINT keeps the generated H2 (create-drop) schema free
    // of FK constraints, matching the official metadata_keys DDL which declares
    // plain indexes on created_by/modified_by only (no FK). ---

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User creator;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "modified_by", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User modifier;
}
