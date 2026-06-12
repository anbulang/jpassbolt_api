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
 * Group entity representing a group of users (ARO "Group" in the
 * ACO/ARO permission model).
 *
 * <p>
 * Column layout mirrors the official Passbolt {@code groups} table:
 * id, name, deleted, created, modified, created_by, modified_by.
 * The table name is backtick-escaped because {@code GROUPS} is a reserved
 * word in MySQL 8 (Hibernate translates the backticks into the
 * dialect-specific quote character).
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "`groups`")
public class Group extends BaseEntity {

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "created_by", nullable = false, length = 36, columnDefinition = "char(36)")
    private String createdBy;

    @Column(name = "modified_by", nullable = false, length = 36, columnDefinition = "char(36)")
    private String modifiedBy;

    // --- Relationships (read-only navigation; the String columns above are
    // the writable track). NO_CONSTRAINT keeps the generated H2 schema free
    // of FK constraints, matching the official Passbolt MySQL schema which
    // declares plain indexes only. ---

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User creator;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "modified_by", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User modifier;
}
