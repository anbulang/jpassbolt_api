package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.service.email.Recipient;
import com.jpassbolt.api.service.email.RecipientResolver;

import java.util.Map;

/**
 * Small shared helper for the folder and resource notification redactors.
 *
 * <p>PHP's redactors build their subjects from {@code operator.profile
 * .first_name}. This centralises that lookup so every redactor resolves the
 * actor's display first name identically: the profile first name when present,
 * else the {@link Recipient#fullName()} fallback (which itself falls back to the
 * email when no profile exists), else an empty string when the actor cannot be
 * resolved at all.</p>
 */
final class RedactorSupport {

    private RedactorSupport() {
    }

    /** The actor's first name for subject/body copy, with graceful fallbacks. */
    static String actorFirstName(RecipientResolver resolver, String actorId) {
        return resolver.resolveUser(actorId)
                .map(r -> {
                    String first = r.firstName();
                    return (first != null && !first.isBlank()) ? first : r.fullName();
                })
                .orElse("");
    }

    /**
     * True when a {@code show_*} content gate is enabled in the effective
     * notification settings map (default-off — a missing/non-true value is off).
     * Mirrors PHP's {@code getConfig('show.username')} truthiness check.
     */
    static boolean isOn(Map<String, Object> settings, String key) {
        return Boolean.TRUE.equals(settings.get(key));
    }
}
