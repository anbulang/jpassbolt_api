package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * SecretAccess Entity — one row per "user X read secret S of resource R".
 *
 * <p>Faithful port of the Passbolt Log plugin's {@code secret_accesses} table
 * (see {@code passbolt_api_ref/plugins/PassboltCe/Log/.../SecretAccessesTable.php}
 * and the V270 migration). It is the compliance backbone of the Activity log:
 * every time a caller actually receives a decryptable secret ciphertext, an
 * access is recorded.</p>
 *
 * <p>Write points (mirroring the PHP {@code _logSecretAccesses} controller hooks):
 * <ul>
 *   <li>{@code GET /secrets/resource/{resourceId}.json} — single secret view</li>
 *   <li>{@code GET /resources/{id}.json} — resource view embeds the caller's secret</li>
 * </ul>
 * The resources <em>index</em> ({@code GET /resources.json}) does not embed secrets
 * in JPassbolt, so — like PHP only logging when {@code contain[secret]} is set — it
 * records nothing.</p>
 *
 * <p><b>Schema facts (do NOT change — MySQL {@code ddl-auto=validate}):</b> the table
 * has only a {@code created} column, no {@code modified} and no soft-delete; rows are
 * append-only and never updated. That is why this entity does <b>not</b> extend
 * {@link BaseEntity} (which would map a non-existent {@code modified} column and add a
 * {@code @PreUpdate}). The id (char(36) UUID) and {@code created} are stamped in
 * {@link #onCreate()}.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "secret_accesses")
public class SecretAccess {

    @Id
    @Column(columnDefinition = "char(36)")
    private String id;

    @Column(name = "user_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String userId;

    @Column(name = "resource_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String resourceId;

    @Column(name = "secret_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String secretId;

    @Column(name = "created", nullable = false, updatable = false)
    private LocalDateTime created;

    /**
     * Convenience constructor for recording a new access.
     *
     * @param userId     the accessor (current user)
     * @param resourceId the resource whose secret was read
     * @param secretId   the specific secret ciphertext that was read
     */
    public SecretAccess(String userId, String resourceId, String secretId) {
        this.userId = userId;
        this.resourceId = resourceId;
        this.secretId = secretId;
    }

    @PrePersist
    protected void onCreate() {
        // Same UTC convention as BaseEntity so the RFC3339 serializer appends the
        // correct +00:00 offset (see config/JacksonConfig).
        created = LocalDateTime.now(ZoneOffset.UTC);
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
