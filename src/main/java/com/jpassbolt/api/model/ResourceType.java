package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ResourceType entity mapped to the {@code resource_types} table.
 *
 * <p>Mirrors the Passbolt v4 schema exactly (MySQL profile runs with
 * ddl-auto=validate):</p>
 * <ul>
 *   <li>{@code definition} stores the raw JSON Schema string as persisted in
 *       the database; deserialization to a JSON object happens at the
 *       controller/DTO boundary only.</li>
 *   <li>{@code deleted} is a nullable DATETIME soft-delete timestamp
 *       (null = active), unlike the Boolean soft-delete flag used by
 *       Resource/User/GpgKey.</li>
 * </ul>
 *
 * <p>Slug constants ported from the PHP reference implementation
 * ({@code plugins/PassboltCe/ResourceTypes/src/Model/Entity/ResourceType.php}).</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "resource_types")
public class ResourceType extends BaseEntity {

    /** v4 resource type slugs. */
    public static final String SLUG_PASSWORD_STRING = "password-string";
    public static final String SLUG_PASSWORD_AND_DESCRIPTION = "password-and-description";
    public static final String SLUG_STANDALONE_TOTP = "totp";
    public static final String SLUG_PASSWORD_DESCRIPTION_TOTP = "password-description-totp";

    /**
     * v5 resource type slugs, excluded from the v4 index endpoint.
     * Mirrors PHP ResourceType::V5_RESOURCE_TYPE_SLUGS (6 entries).
     */
    public static final List<String> V5_RESOURCE_TYPE_SLUGS = List.of(
            "v5-password-string",
            "v5-default",
            "v5-totp-standalone",
            "v5-default-with-totp",
            "v5-custom-fields",
            "v5-note");

    @Column(name = "slug", length = 64, nullable = false, unique = true)
    private String slug;

    @Column(name = "name", length = 64, nullable = false)
    private String name;

    /** DB column is CHAR(255), not VARCHAR — columnDefinition required for ddl-auto=validate. */
    @Column(name = "description", columnDefinition = "char(255)")
    private String description;

    /** Raw JSON Schema string as stored in the database (TEXT column). */
    @Column(name = "definition", columnDefinition = "text")
    private String definition;

    /** Soft-delete timestamp: null = active, non-null = deletion time. */
    @Column(name = "deleted")
    private LocalDateTime deleted;
}
