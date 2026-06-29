package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.model.GroupUser;
import com.jpassbolt.api.repository.GroupUserRepository;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Emails the OTHER current managers of a group a summary of an update — the port
 * of PHP {@code GroupUpdateAdminSummaryEmailRedactor} (gate
 * {@code send.group.manager.update}, template {@code GM/group_user_update}).
 *
 * <p>Fires only {@code AFTER_COMMIT} on the {@code mailExecutor} thread; the gate
 * is checked first and the redactor short-circuits when nothing changed.
 * Recipients are the group's CURRENT managers (resolved post-commit) minus everyone
 * affected by this change (added ∪ removed ∪ updated) and minus the actor — exactly
 * PHP {@code getGroupManagers(excludeUsersIds = affected ∪ modifiedBy)}. The body
 * lists the added / updated / removed members with a {@code (manager)} marker.</p>
 */
@Component
@RequiredArgsConstructor
public class GroupUpdateAdminSummaryEmailRedactor {

    /** PHP GroupUpdateAdminSummaryEmailRedactor::getNotificationSettingPath(). */
    private static final String SETTING_PATH = "send.group.manager.update";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;
    private final GroupUserRepository groupUserRepository;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGroupMembershipChanged(GroupMembershipChangedEvent event) {
        if (!settings.isEnabled(SETTING_PATH)) {
            return;
        }
        boolean nothingChanged = isEmpty(event.added()) && isEmpty(event.removed())
                && isEmpty(event.updated());
        if (nothingChanged) {
            return;
        }

        Set<String> affected = new LinkedHashSet<>();
        collectIds(event.added(), affected);
        collectIds(event.removed(), affected);
        collectIds(event.updated(), affected);

        // Current managers (post-commit) minus the affected members and the actor.
        List<String> managerIds = groupUserRepository.findByGroupIdAndIsAdminTrue(event.groupId()).stream()
                .map(GroupUser::getUserId).collect(Collectors.toList());
        Set<Recipient> recipients = recipientResolver.resolveUsers(managerIds).stream()
                .filter(r -> !affected.contains(r.userId()) && !r.userId().equals(event.actorId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (recipients.isEmpty()) {
            return;
        }

        String actorName = recipientResolver.resolveUser(event.actorId())
                .map(Recipient::fullName).orElse("");
        // Display names for everyone impacted (one batched resolve).
        Map<String, String> names = recipientResolver.resolveUsers(affected).stream()
                .collect(Collectors.toMap(Recipient::userId, Recipient::fullName, (a, b) -> a));
        List<Map<String, Object>> addedUsers = describe(event.added(), names);
        List<Map<String, Object>> updatedUsers = describe(event.updated(), names);
        List<Map<String, Object>> removedUsers = describe(event.removed(), names);
        String link = templates.link("/app/groups");

        delivery.deliver(recipients, recipient -> {
            Map<String, Object> vars = new HashMap<>();
            vars.put("actorName", actorName);
            vars.put("groupName", event.groupName());
            vars.put("addedUsers", addedUsers);
            vars.put("updatedUsers", updatedUsers);
            vars.put("removedUsers", removedUsers);
            vars.put("link", link);
            String subject = templates.subject("email.group.manager.update.subject", recipient.locale(),
                    actorName, event.groupName());
            String html = templates.render("lu/group_summary", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }

    private static boolean isEmpty(List<GroupMemberSnapshot> list) {
        return list == null || list.isEmpty();
    }

    private static void collectIds(List<GroupMemberSnapshot> list, Set<String> into) {
        if (list != null) {
            list.forEach(snap -> into.add(snap.userId()));
        }
    }

    /** Render a member bucket as a list of {name, manager} maps for the template. */
    private static List<Map<String, Object>> describe(List<GroupMemberSnapshot> snaps,
            Map<String, String> names) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (snaps != null) {
            for (GroupMemberSnapshot snap : snaps) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("name", names.getOrDefault(snap.userId(), snap.userId()));
                entry.put("manager", snap.isAdmin());
                out.add(entry);
            }
        }
        return out;
    }
}
