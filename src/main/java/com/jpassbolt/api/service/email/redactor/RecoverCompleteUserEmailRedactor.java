package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.Recipient;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.RecoverCompletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Confirms a completed account recovery to the recovering user, including the
 * client IP and user agent — the port of PHP
 * {@code AccountRecoveryCompleteUserEmailRedactor} (gate
 * {@code send.user.recoverComplete}, template {@code AN/user_recover_complete}).
 *
 * <p>Fires on {@link RecoverCompletedEvent} only {@code AFTER_COMMIT} and on the
 * {@code mailExecutor} thread; the gate is checked first. The sole recipient is the
 * recovering user — this is the one redactor whose recipient IS the actor, so the
 * actor is deliberately NOT filtered out (the sibling admin redactor handles the
 * admins and excludes this user).</p>
 */
@Component
@RequiredArgsConstructor
public class RecoverCompleteUserEmailRedactor {

    /**
     * PHP AccountRecoveryCompleteUserEmailRedactor::getNotificationSettingPath() —
     * the camelCase {@code recoverComplete} token survives the '.'→'_' translation
     * unchanged (stored key {@code send_user_recoverComplete}).
     */
    private static final String SETTING_PATH = "send.user.recoverComplete";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecoverCompleted(RecoverCompletedEvent event) {
        if (!settings.isEnabled(SETTING_PATH)) {
            return;
        }
        Optional<Recipient> resolved = recipientResolver.resolveUser(event.userId());
        if (resolved.isEmpty()) {
            return;
        }
        Recipient to = resolved.get();
        String link = templates.link("/app/users/view/" + event.userId());

        delivery.deliver(List.of(to), recipient -> {
            Map<String, Object> vars = new HashMap<>();
            vars.put("clientIp", event.clientIp());
            vars.put("userAgent", event.userAgent());
            vars.put("link", link);
            String subject = templates.subject("email.recover.complete.user.subject", recipient.locale());
            String html = templates.render("lu/recover_complete_user", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }
}
