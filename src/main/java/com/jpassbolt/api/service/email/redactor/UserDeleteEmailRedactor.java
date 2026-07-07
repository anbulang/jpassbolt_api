package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.repository.GroupUserRepository;
import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.Recipient;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.UserDeletedEvent;
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
 * Notifies the managers of the groups a deleted user belonged to — the port of
 * PHP {@code UserDeleteEmailRedactor} (gate {@code send.group.user.delete},
 * template {@code GM/user_delete}).
 *
 * <p>Fires on {@link UserDeletedEvent} only {@code AFTER_COMMIT} and on the
 * {@code mailExecutor} thread; the gate is checked first. Recipients are the
 * managers ({@code is_admin}) of the event's {@code groupsIds} (the not-only-member
 * groups the user belonged to), resolved through {@link RecipientResolver} which
 * drops the now-soft-deleted user and any disabled members. The actor is NOT
 * excluded here (PHP parity): when the deleted user was an admin, an actor who
 * also manages an affected group is de-duplicated out of the sibling
 * {@code AdminDeleteEmailRedactor} instead.</p>
 */
@Component
@RequiredArgsConstructor
public class UserDeleteEmailRedactor {

    /** PHP UserDeleteEmailRedactor::getNotificationSettingPath() — shared with GroupUserDelete. */
    private static final String SETTING_PATH = "send.group.user.delete";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;
    private final GroupUserRepository groupUserRepository;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserDeleted(UserDeletedEvent event) {
        if (!settings.isEnabled(SETTING_PATH)) {
            return; // master gate off — send nothing
        }
        if (event.groupsIds() == null || event.groupsIds().isEmpty()) {
            return; // user belonged to no shared group (PHP empty($groupsIds) guard)
        }

        Set<Recipient> recipients = recipientResolver
                .resolveUsers(groupUserRepository.findManagerUserIdsByGroupIdIn(event.groupsIds())).stream()
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (recipients.isEmpty()) {
            return;
        }

        String actorName = recipientResolver.resolveUser(event.actorId())
                .map(Recipient::fullName).orElse("");
        String deletedName = composeName(event.deletedFirstName(),
                event.deletedLastName(), event.deletedUsername());
        String link = templates.link("/app/users");

        delivery.deliver(recipients, recipient -> {
            Map<String, Object> vars = new HashMap<>();
            vars.put("actorName", actorName);
            vars.put("deletedName", deletedName);
            vars.put("deletedUsername", event.deletedUsername());
            vars.put("link", link);
            String subject = templates.subject("email.user.delete.subject", recipient.locale(),
                    actorName, deletedName);
            String html = templates.render("lu/user_delete", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }

    /** Display name from snapshotted first/last, falling back to the username. */
    static String composeName(String first, String last, String fallback) {
        String f = first == null ? "" : first.trim();
        String l = last == null ? "" : last.trim();
        String name = (f + " " + l).trim();
        return name.isEmpty() ? fallback : name;
    }
}
