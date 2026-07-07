package com.jpassbolt.api.service.email.event;

import java.util.List;

/**
 * Published by {@code SelfRegistrationService.save} (on a settings create/update)
 * and {@code SelfRegistrationService.delete} (on a settings removal), consumed by
 * {@code SelfRegistrationSettingsAdminEmailRedactor} to notify every administrator
 * that the self-registration policy changed — the port of PHP
 * {@code SelfRegistrationSetSettingsService::SELF_REGISTRATION_SETTINGS_UPDATE_EVENT_NAME}.
 *
 * <p>An immutable scalar snapshot (not a JPA entity): the listener runs on an
 * {@code AFTER_COMMIT} async thread where the {@code organization_settings} row is
 * detached, so the effective provider + allowed domains and the acting admin are
 * captured here at publish time.</p>
 *
 * @param provider       the new provider ({@code email_domains}), or {@code null} when self-registration was disabled (settings deleted)
 * @param allowedDomains the new allow-list, or an empty list when disabled
 * @param actorId        the admin who changed the settings (gets a "you edited" self-variant)
 */
public record SelfRegistrationSettingsChangedEvent(
        String provider,
        List<String> allowedDomains,
        String actorId) {
}
