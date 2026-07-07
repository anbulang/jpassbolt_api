package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.Recipient;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.SelfRegistrationSettingsChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Notifies every administrator that the self-registration policy changed — the
 * port of PHP {@code SelfRegistrationSettingsAdminEmailRedactor} (template
 * {@code Admin/settings_update}). Like its PHP counterpart this redactor is
 * <b>always-on</b>: {@code getNotificationSettingPath()} returns null, so there is
 * no {@code EmailNotificationSettings} gate and it cannot be disabled.
 *
 * <p>Fires on {@link SelfRegistrationSettingsChangedEvent} only {@code AFTER_COMMIT}
 * and on the {@code mailExecutor} thread. Recipients are all active admins
 * ({@link RecipientResolver#resolveAllAdmins()}); the acting admin is kept and
 * receives a "you edited …" self-variant subject/body. The body states whether
 * self-registration is now enabled (provider present) or disabled, and lists the
 * allowed domains when enabled.</p>
 */
@Component
@RequiredArgsConstructor
public class SelfRegistrationSettingsAdminEmailRedactor {

    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSettingsChanged(SelfRegistrationSettingsChangedEvent event) {
        Set<Recipient> recipients = recipientResolver.resolveAllAdmins();
        if (recipients.isEmpty()) {
            return;
        }

        boolean enabled = event.provider() != null;
        List<String> domains = event.allowedDomains() == null ? List.of() : event.allowedDomains();
        String actorName = recipientResolver.resolveUser(event.actorId())
                .map(Recipient::fullName).orElse("");
        String link = templates.link("/app/administration/self-registration");

        delivery.deliver(recipients, recipient -> {
            boolean isSelf = recipient.userId().equals(event.actorId());
            Map<String, Object> vars = new HashMap<>();
            vars.put("actorName", actorName);
            vars.put("isSelf", isSelf);
            vars.put("enabled", enabled);
            vars.put("allowedDomains", domains);
            vars.put("link", link);
            String subject = isSelf
                    ? templates.subject("email.selfregister.settings.subject.self", recipient.locale())
                    : templates.subject("email.selfregister.settings.subject", recipient.locale(), actorName);
            String html = templates.render("lu/self_register_settings", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }
}
