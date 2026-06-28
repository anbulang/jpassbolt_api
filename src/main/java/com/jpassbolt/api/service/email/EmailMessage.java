package com.jpassbolt.api.service.email;

/**
 * Immutable value object for a single outbound email, already rendered.
 *
 * <p>The event-driven notification layer (the {@code redactor} package, added in
 * the next phase) resolves recipients and renders the localized HTML via
 * {@link EmailTemplateService}, then hands a fully-formed {@code EmailMessage} to
 * {@link MailService#send(EmailMessage)}. Keeping rendering out of
 * {@link MailService} preserves its single responsibility (transport +
 * best-effort delivery) and lets a future {@code email_queue}-backed
 * implementation persist exactly these three fields with no change to callers.</p>
 *
 * @param recipient the destination address (a user's username == email in Passbolt)
 * @param subject   the localized subject line
 * @param html      the localized HTML body (sent as {@code text/html})
 */
public record EmailMessage(String recipient, String subject, String html) {
}
