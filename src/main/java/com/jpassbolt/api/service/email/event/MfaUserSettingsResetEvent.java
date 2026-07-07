package com.jpassbolt.api.service.email.event;

/**
 * Published by {@code MfaService.resetUserMfaSettings} when a user's MFA account
 * settings are wiped, consumed by {@code MfaUserSettingsResetEmailRedactor} to
 * notify the affected user — the port of PHP
 * {@code MfaUserSettingsDeleteService::MFA_USER_ACCOUNT_SETTINGS_DELETE_EVENT}.
 *
 * <p>An immutable scalar snapshot (not a JPA entity): the listener runs on an
 * {@code AFTER_COMMIT} async thread where the {@code account_settings} row is
 * already deleted, so the two ids the redactor needs are captured here at publish
 * time. The redactor compares them to choose the self-reset vs admin-reset variant
 * (PHP {@code MfaUserSettingsResetEmailRedactor::createEmail}: {@code $user->id !==
 * $uac->getId()}); for the admin variant it re-resolves {@link #actorId} to a
 * display name.</p>
 *
 * <p>The event is published ONLY when a settings row actually existed (PHP dispatches
 * inside {@code disableUserSettings}, which is reached only after
 * {@code MfaAccountSettings::get} found a row) — a reset against a user with no MFA
 * settings sends nothing.</p>
 *
 * @param targetUserId the user whose MFA settings were reset (the email recipient)
 * @param actorId      the user who performed the reset; equals {@code targetUserId}
 *                     for a self-reset, otherwise an administrator
 */
public record MfaUserSettingsResetEvent(
        String targetUserId,
        String actorId) {
}
