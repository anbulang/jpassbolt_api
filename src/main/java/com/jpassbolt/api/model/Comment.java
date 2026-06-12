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
 * Comment Entity - Represents a plaintext comment attached to a resource.
 * Comments are NOT end-to-end encrypted (unlike secrets) and support
 * nested replies via {@code parentId}.
 *
 * Note: the {@code data} (mediumtext, nullable) column existing in the
 * official Passbolt v5 schema is intentionally NOT mapped here — it holds
 * v5 encrypted comment data which this implementation does not use yet.
 * Comments have no {@code deleted} column: deletion is a hard delete,
 * matching the official Passbolt behavior.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "comments")
public class Comment extends BaseEntity {

    /** The only foreign model supported for comments. */
    public static final String RESOURCE_FOREIGN_MODEL = "Resource";

    @Column(name = "parent_id", length = 36, columnDefinition = "char(36)")
    private String parentId;

    @Column(name = "foreign_key", nullable = false, length = 36, columnDefinition = "char(36)")
    private String foreignKey;

    @Column(name = "foreign_model", nullable = false, length = 36)
    private String foreignModel;

    @Column(name = "content", nullable = false, length = 256)
    private String content;

    @Column(name = "created_by", nullable = false, length = 36, columnDefinition = "char(36)")
    private String createdBy;

    @Column(name = "modified_by", nullable = false, length = 36, columnDefinition = "char(36)")
    private String modifiedBy;

    @Column(name = "user_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String userId;

    // The official comments table defines no foreign key constraints
    // (primary key only), so constraint generation is disabled to keep the
    // H2 (create-drop) schema aligned with the MySQL reference schema.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", insertable = false, updatable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User creator;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "modified_by", insertable = false, updatable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User modifier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User user;
}
