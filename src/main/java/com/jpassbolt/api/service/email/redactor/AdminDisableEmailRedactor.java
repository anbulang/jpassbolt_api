package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.service.AccountLocaleService;
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
import java.util.List;
import java.util.Map;

/**
 * Notifies a suspended administrator that their own account was disabled — the
 * port of PHP {@code AdminDisableEmailRedactor} (gate
 * {@code send.admin.user.disable.admin}, template {@code AD/admin_disable},
 * subject "Your account has been suspended").
 *
 * <p>Fires on {@link UserDisabledEvent} only {@code AFTER_COMMIT}, on the
 * {@code mailExecutor} thread, and only when the suspended user is an admin.
 * The sole recipient IS the now-disabled user, whom {@link RecipientResolver}
 * would drop — PHP works around its equivalent filter by faking
 * {@code disabled = tomorrow} before sending; here the recipient is built from
 * the event's scalar snapshot instead (locale still resolved normally). The
 * body points the suspended admin at the operator's address ("contact your
 * admin", a mailto in PHP).</p>
 */
@Component
@RequiredArgsConstructor
public class AdminDisableEmailRedactor {

    /** PHP AdminDisableEmailRedactor::getNotificationSettingPath(). */
    private static final String SETTING_PATH = "send.admin.user.disable.admin";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final AccountLocaleService accountLocaleService;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserDisabled(UserDisabledEvent event) {
        if (!event.disabledUserIsAdmin()) {
            return; // PHP: only fires when the suspended user is an admin
        }
        if (!settings.isEnabled(SETTING_PATH)) {
            return; // master gate off — send nothing
        }

        // Bypass the resolver's disabled filter on purpose (see class doc).
        Recipient to = new Recipient(
                event.disabledUserId(),
                event.disabledUsername(),
                accountLocaleService.toJavaLocale(
                        accountLocaleService.getUserLocale(event.disabledUserId())),
                event.disabledFirstName(),
                event.disabledLastName());

        // PHP hands the template the operator's username (mailto target).
        String operatorEmail = recipientResolver.resolveUser(event.actorId())
                .map(Recipient::email).orElse("");

        delivery.deliver(List.of(to), recipient -> {
            Map<String, Object> vars = new HashMap<>();
            vars.put("operatorEmail", operatorEmail);
            vars.put("link", "mailto:" + operatorEmail);
            String subject = templates.subject("email.admin.disable.subject", recipient.locale());
            String html = templates.render("lu/admin_disable", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }
}
