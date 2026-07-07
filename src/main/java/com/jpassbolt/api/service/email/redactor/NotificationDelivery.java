package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.MailService;
import com.jpassbolt.api.service.email.Recipient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * Shared fan-out for the notification redactors: render one email per recipient
 * and hand each to {@link MailService}, isolating per-recipient failures so one
 * bad render/address never blocks the rest of the batch — the port of PHP
 * {@code EmailSubscriptionDispatcher}'s per-email {@code try/catch} loop.
 *
 * <p>{@link MailService#send(EmailMessage)} already swallows transport failures
 * (best-effort delivery), so this loop only needs to guard the
 * <em>render</em> step (a missing message key, a template error) per recipient.
 * A redactor first checks its notification-setting gate and resolves recipients,
 * then calls {@link #deliver} to produce and post the messages.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDelivery {

    private final MailService mailService;

    /**
     * Render and send one email per recipient, swallowing per-recipient errors.
     *
     * @param recipients the already-resolved, already-filtered recipients
     * @param renderer   builds the {@link EmailMessage} for one recipient (in
     *                   that recipient's locale); may return {@code null} to skip
     */
    public void deliver(Collection<Recipient> recipients, RecipientRenderer renderer) {
        for (Recipient recipient : recipients) {
            try {
                EmailMessage message = renderer.render(recipient);
                if (message != null) {
                    mailService.send(message);
                }
            } catch (Exception e) {
                // Per-recipient isolation: a render failure for one recipient
                // must not abort the others (mirrors PHP per-email try/catch).
                log.warn("Skipped notification to {}: {}", recipient.email(), e.toString());
            }
        }
    }

    /** Renders the email for a single recipient (in the recipient's locale). */
    @FunctionalInterface
    public interface RecipientRenderer {
        EmailMessage render(Recipient recipient) throws Exception;
    }
}
