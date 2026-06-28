package com.jpassbolt.api.service.email;

import java.util.Locale;

/**
 * A resolved notification recipient: the aggregate of the three places Passbolt
 * keeps a person's mailable identity — the {@code users} row (email == username),
 * their {@code profiles} row (first/last name) and their effective locale
 * (account_settings → organization → default, resolved via
 * {@link com.jpassbolt.api.service.AccountLocaleService}).
 *
 * <p>Produced exclusively by {@link RecipientResolver}, which already drops
 * soft-deleted / disabled users, so a {@code Recipient} is always safe to mail.
 * The {@code locale} drives per-recipient copy selection in
 * {@link EmailTemplateService}.</p>
 *
 * @param userId    the recipient's user id (used to exclude the actor, dedupe, etc.)
 * @param email     the destination address (== username in Passbolt)
 * @param locale    the recipient's effective locale, for localized copy
 * @param firstName the profile first name, or {@code null} when no profile exists
 * @param lastName  the profile last name, or {@code null} when no profile exists
 */
public record Recipient(String userId, String email, Locale locale,
                        String firstName, String lastName) {

    /** Display name for greetings; falls back to the email when no profile exists. */
    public String fullName() {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        String name = (first + " " + last).trim();
        return name.isEmpty() ? email : name;
    }
}
