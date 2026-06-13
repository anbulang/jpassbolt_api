package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * GroupUser entity representing the membership of a user in a group
 * ({@code groups_users} join table). {@code is_admin} marks the user as a
 * group manager.
 *
 * <p>
 * NOTE: this entity intentionally does NOT extend {@link BaseEntity} because
 * the official Passbolt {@code groups_users} table has no {@code modified}
 * column (only {@code created}); mapping a non-existent column would break
 * {@code ddl-auto=validate} against MySQL.
 * </p>
 */
@Data
@Entity
@Table(name = "groups_users")
public class GroupUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private String id;

    @Column(name = "group_id", length = 36, columnDefinition = "char(36)")
    private String groupId;

    @Column(name = "user_id", length = 36, columnDefinition = "char(36)")
    private String userId;

    @Column(name = "is_admin", nullable = false)
    private Boolean isAdmin = false;

    @Column(name = "created", nullable = false, updatable = false)
    private LocalDateTime created;

    // --- Relationships (read-only navigation; the String columns above are
    // the writable track). NO_CONSTRAINT keeps the generated H2 schema free
    // of FK constraints, matching the official Passbolt MySQL schema which
    // declares plain indexes only. ---

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User user;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (created == null) {
            // 统一以 UTC 写入，与全局 RFC3339(+00:00) 序列化对齐。
            created = LocalDateTime.now(ZoneOffset.UTC);
        }
        if (isAdmin == null) {
            isAdmin = false;
        }
    }
}
