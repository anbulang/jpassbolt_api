package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Resource Entity - Represents a password entry's metadata.
 * The actual encrypted password is stored separately in the Secret entity.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "resources")
public class Resource extends BaseEntity {

    /**
     * Resource name. NULLABLE per the official v5 migration
     * {@code V4100AlterNameToNullableOnResources} (resources.name DEFAULT NULL):
     * the v5 upgrade moves the plaintext name into the encrypted metadata blob
     * and NULLs this column. v4 create/update still requires it (enforced at the
     * service/contract layer), so existing v4 rows/tests are unaffected.
     */
    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "username", length = 255)
    private String username;

    @Column(name = "uri", length = 1024)
    private String uri;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * v5 ADDITIVE NULLABLE metadata columns (Passbolt v5 zero-knowledge
     * metadata: the encrypted metadata blob is stored/forwarded by the server,
     * never decrypted). v4 create/update flow leaves these null; the v5 upgrade
     * endpoints write ONLY these three columns (name/username/uri/description
     * stay intact). Matches migration V4100AddMetadataFieldsToResources:
     * metadata TEXT_MEDIUM null, metadata_key_id uuid null, metadata_key_type
     * string(100) null.
     */
    @Column(name = "metadata", columnDefinition = "mediumtext")
    private String metadata;

    @Column(name = "metadata_key_id", length = 36, columnDefinition = "char(36)")
    private String metadataKeyId;

    @Column(name = "metadata_key_type", length = 100)
    private String metadataKeyType;

    @Column(name = "deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "expired")
    private LocalDateTime expired;

    @Column(name = "created_by", nullable = false, length = 36, columnDefinition = "char(36)")
    private String createdBy;

    @Column(name = "modified_by", nullable = false, length = 36, columnDefinition = "char(36)")
    private String modifiedBy;

    @Column(name = "resource_type_id", length = 36, columnDefinition = "char(36)")
    private String resourceTypeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", insertable = false, updatable = false)
    private User creator;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "modified_by", insertable = false, updatable = false)
    private User modifier;

    /**
     * Checks if this resource has expired.
     * 
     * @return true if expired date is in the past, false otherwise.
     */
    public boolean isExpired() {
        if (expired == null) {
            return false;
        }
        return expired.isBefore(LocalDateTime.now());
    }
}
