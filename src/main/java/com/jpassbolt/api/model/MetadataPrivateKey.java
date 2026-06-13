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

/**
 * MetadataPrivateKey entity — one user's encrypted copy of a
 * {@link MetadataKey}'s private key (the v5 zero-knowledge metadata system).
 *
 * <p>
 * The {@code data} column holds an armored OpenPGP MESSAGE: the metadata
 * private key encrypted to a specific user's public key. The server STORES and
 * FORWARDS this ciphertext only — it never decrypts it.
 * </p>
 * <p>
 * {@code user_id} is NULLABLE: a NULL {@code user_id} row is the SERVER copy of
 * the key (used when zero-knowledge key share is disabled). App-level
 * uniqueness on {@code (metadata_key_id, user_id)} WHERE {@code user_id} IS NOT
 * NULL is enforced in the service layer, NOT by a DB unique constraint
 * (multiple NULL user_id rows are not desired but the NULL slot is allowed).
 * </p>
 * <p>
 * Schema notes (must stay aligned with the official Passbolt v5 schema,
 * ddl-auto=validate on MySQL — see migration
 * {@code V4100CreateMetadataPrivateKeys}):
 * <ul>
 *   <li>{@code metadata_key_id} uuid NOT NULL</li>
 *   <li>{@code user_id} uuid NULL (null = server key copy)</li>
 *   <li>{@code data} mediumtext NOT NULL (MysqlAdapter::TEXT_MEDIUM)</li>
 *   <li>{@code created_by}/{@code modified_by} NULLABLE uuids</li>
 *   <li>plain indexes on metadata_key_id, user_id, created_by, modified_by
 *       (no FK constraints)</li>
 * </ul>
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "metadata_private_keys")
public class MetadataPrivateKey extends BaseEntity {

    @Column(name = "metadata_key_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String metadataKeyId;

    /** Owner of this encrypted copy; NULL = the server key copy. */
    @Column(name = "user_id", length = 36, columnDefinition = "char(36)")
    private String userId;

    /** Armored OpenPGP MESSAGE (encrypted metadata private key). Stored verbatim. */
    @Column(name = "data", nullable = false, columnDefinition = "mediumtext")
    private String data;

    @Column(name = "created_by", length = 36, columnDefinition = "char(36)")
    private String createdBy;

    @Column(name = "modified_by", length = 36, columnDefinition = "char(36)")
    private String modifiedBy;

    // --- Read-only navigation (the String columns above are the writable
    // track). NO_CONSTRAINT keeps the generated H2 (create-drop) schema free of
    // FK constraints, matching the official metadata_private_keys DDL which
    // declares plain indexes only (no FK). ---

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "metadata_key_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private MetadataKey metadataKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User creator;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "modified_by", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User modifier;
}
