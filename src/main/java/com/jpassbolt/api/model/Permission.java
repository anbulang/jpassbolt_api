package com.jpassbolt.api.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Permission entity representing access control for resources.
 * Uses the ACO/ARO (Access Control Object / Access Request Object) model.
 *
 * <p>
 * ACO types: "Resource" (the object being accessed)
 * </p>
 * <p>
 * ARO types: "User" or "Group" (who is requesting access)
 * </p>
 * <p>
 * Permission types: READ(1), UPDATE(7), OWNER(15)
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "permissions", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "aco_foreign_key", "aro_foreign_key" })
})
public class Permission extends BaseEntity {

    /** Permission type constants */
    public static final int READ = 1;
    public static final int UPDATE = 7;
    public static final int OWNER = 15;

    /** ACO type constants */
    public static final String RESOURCE_ACO = "Resource";

    /** ARO type constants */
    public static final String USER_ARO = "User";
    public static final String GROUP_ARO = "Group";

    /**
     * The type of access control object (e.g., "Resource").
     */
    @Column(name = "aco", nullable = false, length = 30)
    private String aco;

    /**
     * The UUID of the access control object (e.g., a resource ID).
     */
    @Column(name = "aco_foreign_key", nullable = false, length = 36, columnDefinition = "char(36)")
    private String acoForeignKey;

    /**
     * The type of access request object (e.g., "User" or "Group").
     */
    @Column(name = "aro", nullable = false, length = 30)
    private String aro;

    /**
     * The UUID of the access request object (e.g., a user ID or group ID).
     */
    @Column(name = "aro_foreign_key", nullable = false, length = 36, columnDefinition = "char(36)")
    private String aroForeignKey;

    /**
     * The permission level: READ(1), UPDATE(7), or OWNER(15).
     */
    @Column(name = "type", nullable = false)
    private Integer type;

    // --- Relationships ---

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "aco_foreign_key", referencedColumnName = "id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Resource resource;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "aro_foreign_key", referencedColumnName = "id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User user;

    /**
     * Check if the ARO is a group.
     */
    public boolean isAroGroup() {
        return GROUP_ARO.equals(this.aro);
    }

    /**
     * Check if the permission type is valid.
     */
    public static boolean isValidType(int type) {
        return type == READ || type == UPDATE || type == OWNER;
    }
}
