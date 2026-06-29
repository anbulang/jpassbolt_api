package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.Recipient;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Notifies every administrator that a guest just self-registered — the port of PHP
 * {@code SelfRegistrationAdminEmailRedactor} (gate
 * {@code send.admin.user.register.complete}, template {@code AD/user_register_self},
 * event {@code Model.Users.afterSelfRegister.success}).
 *
 * <p>Listens to the SAME {@link UserRegisteredEvent} that drives the new user's
 * setup mail ({@code UserRegisterEmailRedactor}), but short-circuits unless
 * {@link UserRegisteredEvent#selfRegistration()} is set — so an admin invite or a
 * recover-restart (both {@code adminId == null} for the latter) never triggers
 * this "a user created an account" notice. Fires only {@code AFTER_COMMIT} on the
 * {@code mailExecutor} thread; the gate is checked first.</p>
 *
 * <p>Recipients are all active admins ({@link RecipientResolver#resolveAllAdmins()},
 * which already drops disabled/soft-deleted ones) — the self-registered user is a
 * brand-new non-admin and is never in that set, so no actor exclusion is needed
 * (PHP sends to all not-disabled admins).</p>
 */
@Component
@RequiredArgsConstructor
public class SelfRegistrationAdminEmailRedactor {

    /** PHP SelfRegistrationAdminEmailRedactor::getNotificationSettingPath(). */
    private static final String SETTING_PATH = "send.admin.user.register.complete";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRegistered(UserRegisteredEvent event) {
        if (!event.selfRegistration()) {
            return; // only a genuine guest self-registration notifies admins
        }
        if (!settings.isEnabled(SETTING_PATH)) {
            return;
        }
        Set<Recipient> recipients = recipientResolver.resolveAllAdmins();
        if (recipients.isEmpty()) {
            return;
        }

        String firstName = (event.firstName() == null || event.firstName().isBlank())
                ? event.username() : event.firstName();
        String link = templates.link("/app/users/view/" + event.userId());

        delivery.deliver(recipients, recipient -> {
            Map<String, Object> vars = new HashMap<>();
            vars.put("firstName", firstName);
            vars.put("username", event.username());
            vars.put("link", link);
            String subject = templates.subject("email.selfregister.admin.subject",
                    recipient.locale(), firstName);
            String html = templates.render("lu/self_register_admin", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }
}
