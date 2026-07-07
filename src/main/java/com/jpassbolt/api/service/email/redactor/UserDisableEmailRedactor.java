package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.Recipient;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.UserDisabledEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Notifies every administrator when a user is suspended — the port of PHP
 * {@code UserDisableEmailRedactor} (gate {@code send.admin.user.disable.user},
 * template {@code AD/user_disable}, subject "{0} has been suspended").
 *
 * <p>Fires on {@link UserDisabledEvent} only {@code AFTER_COMMIT} and on the
 * {@code mailExecutor} thread; the gate is checked first. Recipients are all
 * active admins via {@link RecipientResolver#resolveAllAdmins()}, which applies
 * the same not-disabled filter as PHP's {@code findAdmins()->find('notDisabled')}
 * — so a just-suspended admin drops out here (their own notice is the sibling
 * {@code AdminDisableEmailRedactor}) while a future-dated suspension keeps them
 * in, exactly like PHP. The acting admin is NOT excluded (PHP parity).</p>
 */
@Component
@RequiredArgsConstructor
public class UserDisableEmailRedactor {

    /** PHP UserDisableEmailRedactor::getNotificationSettingPath(). */
    private static final String SETTING_PATH = "send.admin.user.disable.user";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserDisabled(UserDisabledEvent event) {
        if (!settings.isEnabled(SETTING_PATH)) {
            return; // master gate off — send nothing
        }

        Set<Recipient> recipients = recipientResolver.resolveAllAdmins();
        if (recipients.isEmpty()) {
            return;
        }

        String userName = UserDeleteEmailRedactor.composeName(event.disabledFirstName(),
                event.disabledLastName(), event.disabledUsername());
        String link = templates.link("/app/users/view/" + event.disabledUserId());

        delivery.deliver(recipients, recipient -> {
            Map<String, Object> vars = new HashMap<>();
            vars.put("userName", userName);
            vars.put("userUsername", event.disabledUsername());
            vars.put("link", link);
            String subject = templates.subject("email.user.disable.subject",
                    recipient.locale(), userName);
            String html = templates.render("lu/user_disable", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }
}
