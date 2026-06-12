package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Avatar Entity - Stores the avatar image bytes for a user profile.
 * <p>
 * The {@code data} column holds the original medium-size (200x200) JPEG bytes
 * uploaded by the user; the small format is derived on the fly at read time.
 * <p>
 * Notes on the mapping:
 * <ul>
 * <li>No {@code @ManyToOne} navigation track for {@code profile_id}: there is
 * no Profile entity on the Java side yet (profiles table belongs to the
 * users-crud cluster) and the DDL declares no foreign key constraint, only a
 * plain index.</li>
 * <li>No {@code deleted} column exists, so the soft-delete pattern does not
 * apply to this table.</li>
 * <li>{@code columnDefinition = "blob"} is mandatory: without it Hibernate's
 * MySQL dialect may validate {@code byte[]} as longblob/varbinary and fail
 * the {@code ddl-auto=validate} startup check against the existing table.</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "avatars")
public class Avatar extends BaseEntity {

    @Column(name = "profile_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String profileId;

    @Lob
    @Column(name = "data", columnDefinition = "blob")
    private byte[] data;
}
