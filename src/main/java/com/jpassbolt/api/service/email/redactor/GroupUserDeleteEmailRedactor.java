package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.Recipient;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.GroupMemberSnapshot;
import com.jpassbolt.api.service.email.event.GroupMembershipChangedEvent;
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
 * Emails each member removed from a group on an update — the port of PHP
 * {@code GroupUserDeleteEmailRedactor} (gate {@code send.group.user.delete}, the
 * key it shares with {@code UserDeleteEmailRedactor}; template
 * {@code LU/group_user_delete}).
 *
 * <p>Fires only {@code AFTER_COMMIT} on the {@code mailExecutor} thread; the gate
 * is checked first. Recipients are the removed members (snapshotted before the
 * delete), resolved through {@link RecipientResolver}. PHP's update path does not
 * filter the operator, so a manager who removes themselves still gets the notice.</p>
 */
@Component
@RequiredArgsConstructor
public class GroupUserDeleteEmailRedactor {

    /** PHP GroupUserDeleteEmailRedactor::getNotificationSettingPath() — shared with UserDelete. */
    private static final String SETTING_PATH = "send.group.user.delete";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGroupMembershipChanged(GroupMembershipChangedEvent event) {
        if (!settings.isEnabled(SETTING_PATH)) {
            return;
        }
        if (event.removed() == null || event.removed().isEmpty()) {
            return;
        }

        Set<String> removedIds = event.removed().stream()
                .map(GroupMemberSnapshot::userId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Recipient> recipients = new LinkedHashSet<>(recipientResolver.resolveUsers(removedIds));
        if (recipients.isEmpty()) {
            return;
        }

        String actorName = recipientResolver.resolveUser(event.actorId())
                .map(Recipient::fullName).orElse("");
        String link = templates.link("/app/groups");

        delivery.deliver(recipients, recipient -> {
            Map<String, Object> vars = new HashMap<>();
            vars.put("actorName", actorName);
            vars.put("groupName", event.groupName());
            vars.put("link", link);
            String subject = templates.subject("email.group.user.delete.subject", recipient.locale(),
                    actorName, event.groupName());
            String html = templates.render("lu/group_user_delete", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }
}
