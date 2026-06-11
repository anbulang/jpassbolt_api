package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Secret Entity - Stores the encrypted password data.
 * Each secret is associated with a resource and a user.
 * The 'data' field contains PGP-encrypted content.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "secrets")
public class Secret extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String userId;

    @Column(name = "resource_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String resourceId;

    @Column(name = "data", nullable = false, columnDefinition = "TEXT")
    private String data;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resource_id", insertable = false, updatable = false)
    private Resource resource;
}
