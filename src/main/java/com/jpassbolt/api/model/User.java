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

    @Column(name = "username", nullable = false, length = 255, unique = true)
    private String username;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "disabled")
    private LocalDateTime disabled;
}
