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
@Table(name = "gpgkeys")
public class GpgKey extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String userId;

    @Column(name = "armored_key", nullable = false, columnDefinition = "TEXT")
    private String armoredKey;

    @Column(name = "bits")
    private Integer bits;

    @Column(name = "uid", nullable = false, length = 769)
    private String uid;

    @Column(name = "key_id", nullable = false, length = 16)
    private String keyId;

    @Column(name = "fingerprint", nullable = false, length = 51)
    private String fingerprint;

    @Column(name = "type", length = 16)
    private String type;

    @Column(name = "expires")
    private LocalDateTime expires;

    @Column(name = "key_created")
    private LocalDateTime keyCreated;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;
}
