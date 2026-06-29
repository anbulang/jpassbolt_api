package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.Recipient;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.GroupCreatedEvent;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Emails each member newly added to a group — on creation AND on update — the port
 * of PHP {@code GroupUserAddEmailRedactor} (gate {@code send.group.user.add},
 * template {@code LU/group_user_add}). PHP subscribes the single redactor to both
 * {@code GROUP_CREATE_SUCCESS} and {@code UPDATE_SUCCESS}; this port mirrors that
 * with two listener methods sharing one render path.
 *
 * <p>Fires only {@code AFTER_COMMIT} on the {@code mailExecutor} thread; the gate
 * is checked first. Recipients are the added members, resolved through
 * {@link RecipientResolver} and minus the actor (on create, the actor is the
 * creator, whom PHP also skips). The per-recipient {@code isManager} flag (their
 * new role) selects the extra "you are a group manager" line.</p>
 */
@Component
@RequiredArgsConstructor
public class GroupUserAddEmailRedactor {

    /** PHP GroupUserAddEmailRedactor::getNotificationSettingPath(). */
    private static final String SETTING_PATH = "send.group.user.add";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGroupCreated(GroupCreatedEvent event) {
        emailAddedMembers(event.groupName(), event.actorId(), event.members());
    }

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGroupMembershipChanged(GroupMembershipChangedEvent event) {
        emailAddedMembers(event.groupName(), event.actorId(), event.added());
    }

    private void emailAddedMembers(String groupName, String actorId, List<GroupMemberSnapshot> added) {
        if (!settings.isEnabled(SETTING_PATH)) {
            return;
        }
        if (added == null || added.isEmpty()) {
            return;
        }

        Map<String, Boolean> isManagerByUserId = added.stream().collect(Collectors.toMap(
                GroupMemberSnapshot::userId, GroupMemberSnapshot::isAdmin, (a, b) -> a, LinkedHashMap::new));

        Set<Recipient> recipients = recipientResolver.resolveUsers(isManagerByUserId.keySet()).stream()
                .filter(r -> !r.userId().equals(actorId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (recipients.isEmpty()) {
            return;
        }

        String actorName = recipientResolver.resolveUser(actorId).map(Recipient::fullName).orElse("");
        String link = templates.link("/app/groups");

        delivery.deliver(recipients, recipient -> {
            Map<String, Object> vars = new HashMap<>();
            vars.put("actorName", actorName);
            vars.put("groupName", groupName);
            vars.put("isManager", isManagerByUserId.getOrDefault(recipient.userId(), false));
            vars.put("link", link);
            String subject = templates.subject("email.group.user.add.subject", recipient.locale(),
                    actorName, groupName);
            String html = templates.render("lu/group_user_add", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }
}
