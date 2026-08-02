package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.Recipient;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.FolderSharedEvent;
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
 * Emails each user newly granted access to a folder — the port of PHP
 * {@code ShareFolderEmailRedactor} (gate {@code send.folder.share}, default ON).
 *
 * <p>Fires on {@link FolderSharedEvent} only {@code AFTER_COMMIT} and on the
 * {@code mailExecutor} thread. Recipients are the event's newly-added users,
 * re-resolved through {@link RecipientResolver} (dropping disabled/deleted users)
 * and defensively filtered to exclude the actor. Subject/body name the folder for
 * v4 and use generic wording for v5, mirroring PHP's {@code isV5} branch.</p>
 */
@Component
@RequiredArgsConstructor
public class ShareFolderEmailRedactor {

    /** PHP ShareFolderEmailRedactor::getNotificationSettingPath(). */
    private static final String SETTING_PATH = "send.folder.share";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFolderShared(FolderSharedEvent event) {
        if (!settings.isEnabled(SETTING_PATH)) {
            return;
        }
        if (event.addedUserIds() == null || event.addedUserIds().isEmpty()) {
            return; // revoke-only share notifies no one
        }

        Set<Recipient> recipients = recipientResolver.resolveUsers(event.addedUserIds()).stream()
                .filter(r -> !r.userId().equals(event.actorId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (recipients.isEmpty()) {
            return;
        }

        String actorFirstName = RedactorSupport.actorFirstName(recipientResolver, event.actorId());
        boolean named = event.folderName() != null && !event.folderName().isEmpty();
        String link = templates.link("/app/folders/view/" + event.folderId());

        delivery.deliver(recipients, recipient -> {
            String title = templates.subject("email.folder.share.title", recipient.locale());
            String subject = named
                    ? templates.subject("email.folder.share.subject.named", recipient.locale(),
                            actorFirstName, event.folderName())
                    : templates.subject("email.folder.share.subject.generic", recipient.locale(), actorFirstName);
            String intro = named
                    ? templates.subject("email.folder.share.intro.named", recipient.locale(),
                            actorFirstName, event.folderName())
                    : templates.subject("email.folder.share.intro.generic", recipient.locale(), actorFirstName);

            Map<String, Object> vars = new HashMap<>();
            vars.put("title", title);
            vars.put("intro", intro);
            vars.put("link", link);
            String html = templates.render("lu/folder_share", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }
}
