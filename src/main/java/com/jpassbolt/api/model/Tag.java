package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Tag entity — Passbolt EE "Tags" feature.
 *
 * <p>
 * A Tag labels resources. Two kinds exist, distinguished purely by the slug:
 * a slug starting with {@code '#'} is a <strong>shared</strong> tag (visible to
 * every user who can see the tagged resource); any other slug is a
 * <strong>personal</strong> tag, owned by the user who created the
 * {@link ResourcesTag} association. The {@code is_shared} flag mirrors that
 * convention and is kept in sync at the service layer.
 * </p>
 * <p>
 * Schema notes (must stay aligned with the official Passbolt EE schema,
 * ddl-auto=validate on MySQL — the EE Tags tables are NOT part of the CE
 * reference codebase, so the canonical Passbolt EE column shapes are used):
 * <ul>
 * <li>no {@code deleted} column: tags are HARD deleted (orphaned tags are
 * removed by the service when their last association disappears).</li>
 * <li>v5.1 added three ADDITIVE NULLABLE columns
 * ({@code metadata}/{@code metadata_key_id}/{@code metadata_key_type}) so a tag
 * label can itself be stored as encrypted, zero-knowledge metadata. The server
 * only stores/forwards that armored blob and never decrypts it.</li>
 * </ul>
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "tags")
public class Tag extends BaseEntity {

    /** Prefix that marks a slug (and therefore the tag) as shared. */
    public static final String SHARED_SLUG_PREFIX = "#";

    /**
     * The tag label. For shared tags it begins with {@code '#'}. For v5 tags the
     * human-readable label lives in encrypted {@code metadata} instead, but the
     * slug column remains NOT NULL in the schema.
     */
    @Column(name = "slug", nullable = false, length = 128)
    private String slug;

    /**
     * Whether this tag is shared across users (true) or personal (false).
     * Kept consistent with the {@code '#'} slug prefix at the service layer.
     */
    @Column(name = "is_shared", nullable = false)
    private Boolean isShared = false;

    // --- v5.1 ADDITIVE NULLABLE columns (zero-knowledge metadata) -----------
    // Store-and-forward only: the server never decrypts these.

    /** Encrypted OpenPGP metadata blob (v5.1). Null for legacy v4 tags. */
    @Column(name = "metadata", columnDefinition = "mediumtext")
    private String metadata;

    /** Metadata key the {@code metadata} blob was encrypted to (v5.1). */
    @Column(name = "metadata_key_id", length = 36, columnDefinition = "char(36)")
    private String metadataKeyId;

    /** Type of metadata key used ("shared_key" / "user_key"), v5.1. */
    @Column(name = "metadata_key_type", length = 100)
    private String metadataKeyType;

    /**
     * Convenience helper mirroring the official Passbolt rule: a slug starting
     * with {@code '#'} denotes a shared tag.
     */
    public boolean isSharedSlug() {
        return slug != null && slug.startsWith(SHARED_SLUG_PREFIX);
    }
}
