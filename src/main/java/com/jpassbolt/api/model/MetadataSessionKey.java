package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * MetadataSessionKey Entity - the v5 metadata "session key" cache for a single
 * user. Each row stores one OpenPGP MESSAGE (the user's session key blob)
 * encrypted to that user's own key. This is part of the zero-knowledge v5
 * metadata system: the server only stores and forwards the armored ciphertext
 * in {@code data}, it never decrypts it.
 *
 * Schema mirrors the official Passbolt migration
 * {@code V4100CreateMetadataSessionKeys}:
 * <ul>
 *   <li>{@code id} char(36) PK + {@code created}/{@code modified} datetime NOT NULL
 *       (inherited from {@link BaseEntity});</li>
 *   <li>{@code user_id} uuid NOT NULL;</li>
 *   <li>{@code data} mediumtext (= {@code MysqlAdapter::TEXT_MEDIUM}) NOT NULL;</li>
 *   <li>index on {@code user_id} (NOT unique).</li>
 * </ul>
 *
 * The official migration adds only a (non-unique) index on {@code user_id};
 * the "one active session key per user" rule is enforced at the service layer,
 * NOT by a DB unique constraint. There is no {@code deleted} column: deletion is
 * a hard delete, matching the official behavior.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "metadata_session_keys", indexes = {
        @Index(name = "idx_metadata_session_keys_user_id", columnList = "user_id")
})
public class MetadataSessionKey extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String userId;

    /**
     * Encrypted OpenPGP MESSAGE (the user's session key blob). Stored as
     * mediumtext to match {@code MysqlAdapter::TEXT_MEDIUM}. Server never
     * decrypts this value.
     */
    @Column(name = "data", nullable = false, columnDefinition = "mediumtext")
    private String data;

    // The official metadata_session_keys table defines no foreign key
    // constraints (primary key + plain index only), so constraint generation
    // is disabled to keep the H2 (create-drop) schema aligned with the MySQL
    // reference schema.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User user;
}
