package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.Recipient;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.MfaUserSettingsResetEvent;
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
 * Notifies a user that their multi-factor authentication settings were reset — the
 * port of PHP {@code MfaUserSettingsResetEmailRedactor} (templates
 * {@code LU/mfa_user_settings_reset_self} / {@code LU/mfa_user_settings_reset_admin}).
 * Like its PHP counterpart this redactor is <b>always-on</b>:
 * {@code getNotificationSettingPath()} returns null, so there is no
 * {@code EmailNotificationSettings} gate and it cannot be disabled (a security
 * notice, not a courtesy email) — hence no {@code EmailNotificationSettingsService}
 * dependency, matching the always-on {@link SelfRegistrationSettingsAdminEmailRedactor}.
 *
 * <p>Fires on {@link MfaUserSettingsResetEvent} only {@code AFTER_COMMIT} and on the
 * {@code mailExecutor} thread. The sole recipient is the affected user; resolving
 * them through {@link RecipientResolver} also enforces the PHP {@code !$user->isDisabled()}
 * guard for free (the resolver drops soft-deleted / disabled users), so a reset on a
 * disabled account sends nothing. When the actor differs from the target it is an
 * administrator reset — the actor is re-resolved to a display name and the
 * admin-variant subject/body is used; otherwise it is a self-reset.</p>
 */
@Component
@RequiredArgsConstructor
public class MfaUserSettingsResetEmailRedactor {

    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMfaReset(MfaUserSettingsResetEvent event) {
        Optional<Recipient> resolved = recipientResolver.resolveUser(event.targetUserId());
        if (resolved.isEmpty()) {
            return; // disabled/deleted target → no email (PHP isDisabled guard)
        }
        Recipient to = resolved.get();

        boolean selfReset = event.targetUserId().equals(event.actorId());
        // The admin display name is only needed (and only resolved) for the admin variant.
        String adminName = selfReset ? "" : recipientResolver.resolveUser(event.actorId())
                .map(Recipient::fullName).orElse("");
        // PHP links to the app root ("Log in passbolt"): after a reset the user re-authenticates
        // (and may re-enroll) from the client; there is no deep link to a now-empty setup.
        String link = templates.link("");

        delivery.deliver(List.of(to), recipient -> {
            Map<String, Object> vars = new HashMap<>();
            vars.put("selfReset", selfReset);
            vars.put("adminName", adminName);
            vars.put("link", link);
            String subject = selfReset
                    ? templates.subject("email.mfa.reset.subject.self", recipient.locale())
                    : templates.subject("email.mfa.reset.subject.admin", recipient.locale());
            String html = templates.render("lu/mfa_user_settings_reset", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }
}
