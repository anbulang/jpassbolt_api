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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sends a security notice to every administrator (except the recovering user) that
 * a user just completed account recovery, including the client IP and user agent —
 * the port of PHP {@code AccountRecoveryCompleteAdminEmailRedactor} (gate
 * {@code send.admin.user.recover.complete}, template {@code AD/recover_complete}).
 *
 * <p>Fires on {@link RecoverCompletedEvent} only {@code AFTER_COMMIT} and on the
 * {@code mailExecutor} thread; the gate is checked first. Recipients are all active
 * admins ({@link RecipientResolver#resolveAllAdmins()}) minus the recovering user
 * — who, being the actor, must be filtered out explicitly because
 * {@code resolveAllAdmins} does not exclude them (PHP {@code Users.id != $user->id}).</p>
 */
@Component
@RequiredArgsConstructor
public class RecoverCompleteAdminEmailRedactor {

    /** PHP AccountRecoveryCompleteAdminEmailRedactor::getNotificationSettingPath(). */
    private static final String SETTING_PATH = "send.admin.user.recover.complete";

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
        Set<Recipient> recipients = recipientResolver.resolveAllAdmins().stream()
                .filter(r -> !r.userId().equals(event.userId())) // exclude the recovering user (actor)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (recipients.isEmpty()) {
            return;
        }

        String firstName = (event.userFirstName() == null || event.userFirstName().isBlank())
                ? event.username() : event.userFirstName();
        String link = templates.link("/app/users/view/" + event.userId());

        delivery.deliver(recipients, recipient -> {
            Map<String, Object> vars = new HashMap<>();
            vars.put("firstName", firstName);
            vars.put("username", event.username());
            vars.put("clientIp", event.clientIp());
            vars.put("userAgent", event.userAgent());
            vars.put("link", link);
            String subject = templates.subject("email.recover.complete.admin.subject",
                    recipient.locale(), firstName);
            String html = templates.render("lu/recover_complete_admin", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }
}
