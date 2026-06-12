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
 * Profile entity — maps the {@code profiles} table (one row per user,
 * created together with the user at registration time).
 *
 * <p>
 * Column mapping mirrors the official Passbolt schema exactly
 * (MySQL profile runs with ddl-auto=validate):
 * id char(36) PK, user_id char(36) NOT NULL (plain KEY, not unique),
 * first_name/last_name varchar(255) NOT NULL, created/modified datetime.
 * </p>
 *
 * <p>
 * There is NO {@code deleted} column: profiles are kept when their user is
 * soft-deleted, matching the PHP reference behaviour. Do not add soft-delete
 * filtering here.
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "profiles")
public class Profile extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String userId;

    @Column(name = "first_name", nullable = false, length = 255)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 255)
    private String lastName;

    /**
     * Read-only navigation track for the owning user. NO_CONSTRAINT so
     * H2 create-drop does not emit a real FK (profiles must survive user
     * soft-delete and test cleanup ordering) — same precedent as
     * {@link Comment}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User user;
}
