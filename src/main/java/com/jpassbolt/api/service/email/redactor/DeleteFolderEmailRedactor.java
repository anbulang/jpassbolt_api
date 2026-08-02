package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.Recipient;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.FolderDeletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Emails everyone who had access when a folder is deleted — the port of PHP
 * {@code DeleteFolderEmailRedactor} (gate {@code send.folder.delete}, default
 * ON).
 *
 * <p>Fires on {@link FolderDeletedEvent} only {@code AFTER_COMMIT} and on the
 * {@code mailExecutor} thread. Unlike the update redactor, recipients are NOT
 * re-resolved from permissions — the delete already removed them — but come from
 * {@link FolderDeletedEvent#recipientUserIds}, the set snapshotted in the service
 * before deletion (PHP hands the same {@code $users} to the event). The operator
 * is kept and gets the "You deleted …" wording; everyone else gets "{actor}
 * deleted …". The folder no longer exists, so there is no deep link — the email
 * carries only the (snapshotted) name.</p>
 */
@Component
@RequiredArgsConstructor
public class DeleteFolderEmailRedactor {

    /** PHP DeleteFolderEmailRedactor::getNotificationSettingPath(). */
    private static final String SETTING_PATH = "send.folder.delete";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFolderDeleted(FolderDeletedEvent event) {
        if (!settings.isEnabled(SETTING_PATH)) {
            return;
        }
        if (event.recipientUserIds() == null || event.recipientUserIds().isEmpty()) {
            return;
        }

        Set<Recipient> recipients = recipientResolver.resolveUsers(event.recipientUserIds());
        if (recipients.isEmpty()) {
            return;
        }

        String actorFirstName = RedactorSupport.actorFirstName(recipientResolver, event.actorId());
        boolean named = event.folderName() != null && !event.folderName().isEmpty();

        delivery.deliver(recipients, recipient -> {
            boolean isOperator = recipient.userId().equals(event.actorId());
            String title = templates.subject("email.folder.delete.title", recipient.locale());
            String subject = folderSubject(recipient, isOperator, named, actorFirstName, event.folderName());
            String intro = folderIntro(recipient, isOperator, named, actorFirstName, event.folderName());

            Map<String, Object> vars = new HashMap<>();
            vars.put("title", title);
            vars.put("intro", intro);
            // No link: the folder is gone. The template omits the CTA when link is null.
            vars.put("link", null);
            String html = templates.render("lu/folder_delete", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }

    private String folderSubject(Recipient r, boolean isOperator, boolean named,
            String actorFirstName, String folderName) {
        if (isOperator) {
            return named
                    ? templates.subject("email.folder.delete.subject.self.named", r.locale(), folderName)
                    : templates.subject("email.folder.delete.subject.self.generic", r.locale());
        }
        return named
                ? templates.subject("email.folder.delete.subject.other.named", r.locale(), actorFirstName, folderName)
                : templates.subject("email.folder.delete.subject.other.generic", r.locale(), actorFirstName);
    }

    private String folderIntro(Recipient r, boolean isOperator, boolean named,
            String actorFirstName, String folderName) {
        if (isOperator) {
            return named
                    ? templates.subject("email.folder.delete.intro.self.named", r.locale(), folderName)
                    : templates.subject("email.folder.delete.intro.self.generic", r.locale());
        }
        return named
                ? templates.subject("email.folder.delete.intro.other.named", r.locale(), actorFirstName, folderName)
                : templates.subject("email.folder.delete.intro.other.generic", r.locale(), actorFirstName);
    }
}
