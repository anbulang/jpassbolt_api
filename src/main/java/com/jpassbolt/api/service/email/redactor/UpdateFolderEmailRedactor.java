package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.Recipient;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.FolderUpdatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Emails everyone with access when a folder is updated — the port of PHP
 * {@code UpdateFolderEmailRedactor} (gate {@code send.folder.update}, default
 * ON).
 *
 * <p>Fires on {@link FolderUpdatedEvent} only {@code AFTER_COMMIT} and on the
 * {@code mailExecutor} thread. Recipients are re-resolved through
 * {@link RecipientResolver#resolveUsersWithAccessToFolder} — an update removes
 * neither the folder nor its permissions, so "who has access" is still answerable
 * post-commit, matching PHP's {@code getUsersIdsHavingAccessTo}. The operator is
 * kept in the recipient set and receives the "You edited …" wording; everyone
 * else receives "{actor} edited …" ({@link RedactorSupport#actorFirstName}).
 * v4 folders name the folder, v5 folders use the generic wording.</p>
 */
@Component
@RequiredArgsConstructor
public class UpdateFolderEmailRedactor {

    /** PHP UpdateFolderEmailRedactor::getNotificationSettingPath(). */
    private static final String SETTING_PATH = "send.folder.update";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFolderUpdated(FolderUpdatedEvent event) {
        if (!settings.isEnabled(SETTING_PATH)) {
            return;
        }

        Set<Recipient> recipients = recipientResolver.resolveUsersWithAccessToFolder(event.folderId());
        if (recipients.isEmpty()) {
            return;
        }

        String actorFirstName = RedactorSupport.actorFirstName(recipientResolver, event.actorId());
        boolean named = event.folderName() != null && !event.folderName().isEmpty();
        String link = templates.link("/app/folders/view/" + event.folderId());

        delivery.deliver(recipients, recipient -> {
            boolean isOperator = recipient.userId().equals(event.actorId());
            String title = templates.subject("email.folder.update.title", recipient.locale());
            String subject = folderSubject(recipient, isOperator, named, actorFirstName, event.folderName());
            String intro = folderIntro(recipient, isOperator, named, actorFirstName, event.folderName());

            Map<String, Object> vars = new HashMap<>();
            vars.put("title", title);
            vars.put("intro", intro);
            vars.put("link", link);
            String html = templates.render("lu/folder_update", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }

    private String folderSubject(Recipient r, boolean isOperator, boolean named,
            String actorFirstName, String folderName) {
        if (isOperator) {
            return named
                    ? templates.subject("email.folder.update.subject.self.named", r.locale(), folderName)
                    : templates.subject("email.folder.update.subject.self.generic", r.locale());
        }
        return named
                ? templates.subject("email.folder.update.subject.other.named", r.locale(), actorFirstName, folderName)
                : templates.subject("email.folder.update.subject.other.generic", r.locale(), actorFirstName);
    }

    private String folderIntro(Recipient r, boolean isOperator, boolean named,
            String actorFirstName, String folderName) {
        if (isOperator) {
            return named
                    ? templates.subject("email.folder.update.intro.self.named", r.locale(), folderName)
                    : templates.subject("email.folder.update.intro.self.generic", r.locale());
        }
        return named
                ? templates.subject("email.folder.update.intro.other.named", r.locale(), actorFirstName, folderName)
                : templates.subject("email.folder.update.intro.other.generic", r.locale(), actorFirstName);
    }
}
