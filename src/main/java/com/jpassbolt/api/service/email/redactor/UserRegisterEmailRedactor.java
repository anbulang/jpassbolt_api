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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Emails a newly-created user their setup link — the port of PHP
 * {@code UserRegisterEmailRedactor} (gate {@code send.user.create}, template
 * {@code AN/user_register_admin}).
 *
 * <p>Fires on {@link UserRegisteredEvent} only {@code AFTER_COMMIT} (no mail on a
 * rolled-back create) and on the {@code mailExecutor} thread. The gate is checked
 * first — PHP {@code EmailSubscriptionDispatcher} short-circuit.</p>
 *
 * <p>The sole recipient is the invited user, re-resolved through
 * {@link RecipientResolver} (which deliberately KEEPS inactive users — a setup
 * invite goes precisely to a not-yet-active user — while still dropping
 * soft-deleted / time-disabled ones and supplying the locale). The admin actor is
 * only resolved to a display name for the body and is never mailed; on the
 * self-driven recover-restart branch {@code adminId} is {@code null} and the body
 * omits the admin attribution line. A disabled invitee is skipped (PHP
 * {@code !user->isDisabled()}).</p>
 */
@Component
@RequiredArgsConstructor
public class UserRegisterEmailRedactor {

    /** PHP UserRegisterEmailRedactor::getNotificationSettingPath(). */
    private static final String SETTING_PATH = "send.user.create";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRegistered(UserRegisteredEvent event) {
        if (!settings.isEnabled(SETTING_PATH)) {
            return; // master gate off — send nothing
        }
        if (event.disabled()) {
            return; // PHP !user->isDisabled() guard
        }

        Optional<Recipient> resolved = recipientResolver.resolveUser(event.userId());
        if (resolved.isEmpty()) {
            return; // invited user soft-deleted/time-disabled concurrently
        }
        Recipient to = resolved.get();

        String adminName = event.adminId() == null ? null
                : recipientResolver.resolveUser(event.adminId()).map(Recipient::fullName).orElse(null);
        boolean hasAdmin = adminName != null && !adminName.isBlank();
        String firstName = (event.firstName() == null || event.firstName().isBlank())
                ? to.fullName() : event.firstName();
        String link = templates.link("/setup/" + event.userId() + "/" + event.token());

        delivery.deliver(List.of(to), recipient -> {
            Map<String, Object> vars = new HashMap<>();
            vars.put("firstName", firstName);
            vars.put("adminName", adminName);
            vars.put("hasAdmin", hasAdmin);
            vars.put("link", link);
            String subject = templates.subject("email.invite.subject", recipient.locale(), firstName);
            String html = templates.render("lu/user_register", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }
}
