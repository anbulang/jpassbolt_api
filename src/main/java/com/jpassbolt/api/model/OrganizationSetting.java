package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Entity mapping the {@code organization_settings} table.
 *
 * <p>
 * Mirrors the Passbolt {@code OrganizationSettings} model. Each row stores a
 * single organization-wide setting as a property/value pair (e.g. the
 * organization locale).
 * </p>
 *
 * <p>
 * Note: {@code property_id} is NOT a foreign key — Passbolt derives it as a
 * UUIDv5 of the property name (see PHP {@code OrganizationSettingsTable}).
 * The Java implementation reads settings by the {@code property} column
 * instead and never recomputes that derivation.
 * </p>
 *
 * <p>
 * This table has no {@code deleted} column, so the soft-delete pattern does
 * not apply here.
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "organization_settings")
public class OrganizationSetting extends BaseEntity {

    @Column(name = "property_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String propertyId;

    @Column(name = "property", nullable = false, length = 256)
    private String property;

    /**
     * The setting value. Column name is a SQL reserved word, hence the
     * backtick escaping so Hibernate quotes it per dialect (required for the
     * H2 create-drop profiles).
     */
    @Column(name = "`value`", nullable = false, columnDefinition = "text")
    private String value;

    @Column(name = "created_by", nullable = false, length = 36, columnDefinition = "char(36)")
    private String createdBy;

    @Column(name = "modified_by", nullable = false, length = 36, columnDefinition = "char(36)")
    private String modifiedBy;
}
