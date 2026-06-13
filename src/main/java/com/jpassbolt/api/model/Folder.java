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
 * Folder entity — organizes resources/folders in per-user trees.
 *
 * <p>
 * The hierarchy is NOT stored on this table: each user's view of the tree
 * lives in {@code folders_relations} (see {@link FoldersRelation}), so the
 * same folder can sit under different parents for different users.
 * </p>
 * <p>
 * Schema notes (must stay aligned with Passbolt official schema,
 * ddl-auto=validate on MySQL):
 * - no {@code deleted} column: folders are HARD deleted (unlike Resource/User)
 * - v5 ADDITIVE NULLABLE columns {@code metadata}/{@code metadata_key_id}/
 * {@code metadata_key_type} are mapped (zero-knowledge: the server only
 * stores/forwards the encrypted metadata blob, never decrypts it). v4 folders
 * leave them null; the v5 upgrade endpoints write ONLY these three, never
 * the v4 {@code name}. Matches migration V4100AddMetadataFieldsToFolders.
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "folders")
public class Folder extends BaseEntity {

    /**
     * The folder name. Nullable in DB (v5 folders store the name in encrypted
     * metadata); required for v4 folders, enforced at the service layer.
     */
    @Column(name = "name", length = 256)
    private String name;

    /**
     * v5 ADDITIVE NULLABLE metadata columns. {@code metadata} holds the
     * client-encrypted OpenPGP metadata blob (mediumtext), {@code metadataKeyId}
     * references the {@code metadata_keys} row used, {@code metadataKeyType}
     * distinguishes shared vs personal key. Null for v4 folders.
     */
    @Column(name = "metadata", columnDefinition = "mediumtext")
    private String metadata;

    @Column(name = "metadata_key_id", length = 36, columnDefinition = "char(36)")
    private String metadataKeyId;

    @Column(name = "metadata_key_type", length = 100)
    private String metadataKeyType;

    @Column(name = "created_by", nullable = false, length = 36, columnDefinition = "char(36)")
    private String createdBy;

    @Column(name = "modified_by", nullable = false, length = 36, columnDefinition = "char(36)")
    private String modifiedBy;

    // NO_CONSTRAINT: the official folders DDL has no FK constraints (indexes
    // only); also keeps H2 create-drop from generating FKs that would break
    // other test classes' cleanup order (same approach as Permission).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User creator;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "modified_by", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User modifier;
}
