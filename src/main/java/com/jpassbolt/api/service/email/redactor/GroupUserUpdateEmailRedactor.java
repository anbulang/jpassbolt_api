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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Emails each member whose manager role changed on a group update — the port of
 * PHP {@code GroupUserUpdateEmailRedactor} (gate {@code send.group.user.update},
 * template {@code LU/group_user_update}).
 *
 * <p>Fires only {@code AFTER_COMMIT} on the {@code mailExecutor} thread; the gate
 * is checked first. Recipients are the role-changed members, resolved through
 * {@link RecipientResolver}. PHP's update path does not filter the operator, so a
 * manager who changes their own role still gets the notice. The per-recipient
 * {@code isManager} flag (their new role) selects the promoted/demoted copy.</p>
 */
@Component
@RequiredArgsConstructor
public class GroupUserUpdateEmailRedactor {

    /** PHP GroupUserUpdateEmailRedactor::getNotificationSettingPath(). */
    private static final String SETTING_PATH = "send.group.user.update";

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
        if (event.updated() == null || event.updated().isEmpty()) {
            return;
        }

        Map<String, Boolean> isManagerByUserId = event.updated().stream().collect(Collectors.toMap(
                GroupMemberSnapshot::userId, GroupMemberSnapshot::isAdmin, (a, b) -> a, LinkedHashMap::new));

        Set<Recipient> recipients =
                new LinkedHashSet<>(recipientResolver.resolveUsers(isManagerByUserId.keySet()));
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
            vars.put("isManager", isManagerByUserId.getOrDefault(recipient.userId(), false));
            vars.put("link", link);
            String subject = templates.subject("email.group.user.update.subject", recipient.locale(),
                    actorName, event.groupName());
            String html = templates.render("lu/group_user_update", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }
}
