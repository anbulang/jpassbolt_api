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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Notifies every administrator when an administrator account is deleted — the port
 * of PHP {@code AdminDeleteEmailRedactor} (always-on: {@code getNotificationSettingPath()}
 * returns {@code null}; template {@code AD/admin_deleted}).
 *
 * <p>Fires on {@link UserDeletedEvent} only {@code AFTER_COMMIT} and only when the
 * deleted user actually was an admin (PHP {@code $deletedUser->role->isAdmin()}).
 * Recipients are all active admins ({@link RecipientResolver#resolveAllAdmins()},
 * which already drops the now-soft-deleted user and disabled admins). To avoid a
 * double notification, admins who manage one of the affected groups — and who
 * therefore already receive the {@code UserDeleteEmailRedactor} email — are removed
 * when {@code send.group.user.delete} is enabled (PHP
 * {@code getAdministrators()} dedup). The acting admin is kept and receives a
 * "you deleted …" self-variant.</p>
 *
 * <p>This redactor has no {@code EmailNotificationSettings} gate of its own (PHP's
 * always-on redactor). PHP additionally guards its registration behind two
 * non-Core {@code Configure} flags ({@code passbolt.email.send.admin.user.delete.admin}
 * / {@code .user}) that are not part of the 25-key settings schema; this port ships
 * the admin notification always-on and omits the separate "email the deleted user
 * themselves" branch (the deleted user is soft-deleted and dropped by the resolver
 * anyway).</p>
 */
@Component
@RequiredArgsConstructor
public class AdminDeleteEmailRedactor {

    /** Dedup gate: PHP getAdministrators() filters out managers only when this is on. */
    private static final String GROUP_USER_DELETE_PATH = "send.group.user.delete";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;
    private final GroupUserRepository groupUserRepository;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserDeleted(UserDeletedEvent event) {
        if (!event.deletedUserIsAdmin()) {
            return; // PHP: only fires when the deleted user was an admin
        }

        // All active admins, minus the deleted user themselves (PHP explicitly
        // filters Users.id != deletedUser->id — defensive even though the resolver
        // already drops the now-soft-deleted user).
        Set<Recipient> recipients = recipientResolver.resolveAllAdmins().stream()
                .filter(r -> !r.userId().equals(event.deletedUserId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // Dedup vs UserDeleteEmailRedactor: a manager of an affected group already
        // got that email, so drop them here (PHP getAdministrators dedup), but only
        // when the group-manager email actually fired (gate on + groups present).
        if (event.groupsIds() != null && !event.groupsIds().isEmpty()
                && settings.isEnabled(GROUP_USER_DELETE_PATH)) {
            Set<String> managerIds = new HashSet<>(
                    groupUserRepository.findManagerUserIdsByGroupIdIn(event.groupsIds()));
            recipients = recipients.stream()
                    .filter(r -> !managerIds.contains(r.userId()))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        if (recipients.isEmpty()) {
            return;
        }

        String actorName = recipientResolver.resolveUser(event.actorId())
                .map(Recipient::fullName).orElse("");
        String deletedName = UserDeleteEmailRedactor.composeName(event.deletedFirstName(),
                event.deletedLastName(), event.deletedUsername());
        String link = templates.link("/app/users");

        delivery.deliver(recipients, recipient -> {
            boolean isSelf = recipient.userId().equals(event.actorId());
            Map<String, Object> vars = new HashMap<>();
            vars.put("actorName", actorName);
            vars.put("deletedName", deletedName);
            vars.put("deletedUsername", event.deletedUsername());
            vars.put("isSelf", isSelf);
            vars.put("link", link);
            String subject = isSelf
                    ? templates.subject("email.admin.delete.subject.self", recipient.locale(), deletedName)
                    : templates.subject("email.admin.delete.subject", recipient.locale(),
                            actorName, deletedName);
            String html = templates.render("lu/admin_delete", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }
}
