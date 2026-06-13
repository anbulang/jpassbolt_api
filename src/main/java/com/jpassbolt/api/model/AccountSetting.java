package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Entity mapping the {@code account_settings} table.
 *
 * <p>
 * Mirrors the Passbolt {@code AccountSettings} model. Each row stores a
 * per-user setting as a property/value pair; the MFA cluster stores the
 * user's MFA configuration under {@code property = "mfa"} with a JSON value
 * like
 * {@code {"providers":["totp"],"totp":{"otpProvisioningUri":"otpauth://...","verified":"..."}}}
 * (see PHP {@code MfaAccountSettings}).
 * </p>
 *
 * <p>
 * Note: {@code property_id} is NOT a foreign key — Passbolt derives it as a
 * UUIDv5 of the property name. The Java implementation derives it with
 * {@code UUID.nameUUIDFromBytes} (UUIDv3, internally consistent but different
 * from the PHP value) and always queries by the {@code (user_id, property)}
 * pair instead.
 * </p>
 *
 * <p>
 * This table has no {@code deleted} column, so the soft-delete pattern does
 * not apply. No {@code @ManyToOne} navigation is declared on purpose (same
 * as {@link AuthenticationToken}): Hibernate would generate an H2 foreign key
 * constraint to {@code users} under create-drop, and leftover rows would then
 * break {@code userRepository.deleteAll()} in unrelated test classes.
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "account_settings")
public class AccountSetting extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String userId;

    @Column(name = "property_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String propertyId;

    @Column(name = "property", nullable = false, length = 256)
    private String property;

    /**
     * The setting value. Column name is a SQL reserved word in H2 2.x, hence
     * the backtick escaping so Hibernate quotes it per dialect (backticks on
     * MySQL, double quotes on H2 — required for the create-drop profiles).
     */
    @Column(name = "`value`", nullable = false, columnDefinition = "text")
    private String value;
}
