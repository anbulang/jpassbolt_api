package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Role entity representing user roles in Passbolt (admin, user, guest).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "roles")
public class Role extends BaseEntity {

    public static final String ADMIN = "admin";
    public static final String USER = "user";
    public static final String GUEST = "guest";

    @Column(name = "name", nullable = false, length = 50, unique = true)
    private String name;

    @Column(name = "description", length = 255)
    private String description;
}
