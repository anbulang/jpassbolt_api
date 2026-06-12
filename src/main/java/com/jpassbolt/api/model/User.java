package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(name = "role_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String roleId;

    /**
     * No unique=true: the official Passbolt schema has NO unique key on
     * username (only KEY deleted). Uniqueness is a business rule scoped to
     * deleted=false users (re-inviting the username of a soft-deleted user
     * must succeed) and is enforced by UserService
     * (existsByUsernameAndDeletedFalse + lowercase normalization).
     */
    @Column(name = "username", nullable = false, length = 255)
    private String username;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "disabled")
    private LocalDateTime disabled;
}
