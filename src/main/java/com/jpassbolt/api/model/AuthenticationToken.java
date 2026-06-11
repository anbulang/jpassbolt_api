package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "authentication_tokens")
public class AuthenticationToken extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String userId;

    @Column(name = "token", nullable = false, length = 36, columnDefinition = "char(36)")
    private String token;

    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "data", columnDefinition = "TEXT")
    private String data;

    @Column(name = "active", nullable = false)
    private Boolean active = true;
}
