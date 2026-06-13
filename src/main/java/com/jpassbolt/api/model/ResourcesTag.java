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
 * ResourcesTag entity — join table linking a {@link Resource} to a {@link Tag}
 * (Passbolt EE "Tags" feature).
 *
 * <p>
 * Each row records that a resource carries a tag <em>for a given user</em>.
 * {@code user_id} is the owner of a personal-tag association; it is
 * <strong>NULL for shared-tag associations</strong> (a shared tag is visible to
 * everyone who can see the resource, so it is not scoped to a single user).
 * </p>
 * <p>
 * Schema notes (must stay aligned with the official Passbolt EE schema,
 * ddl-auto=validate on MySQL — the EE Tags tables are NOT part of the CE
 * reference codebase). No {@code deleted} column: associations are HARD
 * deleted. Indexes exist on {@code resource_id}, {@code tag_id} and
 * {@code user_id}; the FK dual-track associations carry NO_CONSTRAINT so the
 * H2 (create-drop) schema does not generate foreign keys (same approach as
 * Comment/Folder/Permission).
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "resources_tags")
public class ResourcesTag extends BaseEntity {

    @Column(name = "resource_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String resourceId;

    @Column(name = "tag_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String tagId;

    /** Owner of a personal-tag association; NULL for shared-tag associations. */
    @Column(name = "user_id", length = 36, columnDefinition = "char(36)")
    private String userId;

    // FK dual-track (query-only associations). NO_CONSTRAINT: the EE
    // resources_tags table is mapped with indexes only, and disabling FK
    // generation keeps H2 create-drop cleanup order from breaking other tests.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resource_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Resource resource;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Tag tag;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User user;
}
