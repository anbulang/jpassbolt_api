package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.Recipient;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.FolderCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emails the creator a "you added the folder X" confirmation — the port of PHP
 * {@code CreateFolderEmailRedactor} (gate {@code send.folder.create}, default
 * OFF).
 *
 * <p>Fires on {@link FolderCreatedEvent} only {@code AFTER_COMMIT} (no mail on a
 * rolled-back create) and on the {@code mailExecutor} thread (off the request
 * path). The gate is checked first — PHP's {@code EmailSubscriptionDispatcher}
 * short-circuit, which skips all recipient/body work when the setting is off.</p>
 *
 * <p>The single recipient is the creator (the event actor), re-resolved through
 * {@link RecipientResolver} so a since-disabled/deleted creator is dropped. v4
 * folders carry the plaintext name (named wording); v5 folders have a {@code null}
 * name (generic wording), mirroring PHP's {@code isV5} branch.</p>
 */
@Component
@RequiredArgsConstructor
public class CreateFolderEmailRedactor {

    /** PHP CreateFolderEmailRedactor::getNotificationSettingPath(). */
    private static final String SETTING_PATH = "send.folder.create";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFolderCreated(FolderCreatedEvent event) {
        if (!settings.isEnabled(SETTING_PATH)) {
            return; // master gate off — send nothing (PHP isRedactorActive short-circuit)
        }

        Recipient creator = recipientResolver.resolveUser(event.actorId()).orElse(null);
        if (creator == null) {
            return; // creator since disabled/deleted — nothing to send
        }

        boolean named = event.folderName() != null && !event.folderName().isEmpty();
        String link = templates.link("/app/folders/view/" + event.folderId());

        delivery.deliver(List.of(creator), recipient -> {
            String title = templates.subject("email.folder.create.title", recipient.locale());
            String subject = named
                    ? templates.subject("email.folder.create.subject.named", recipient.locale(), event.folderName())
                    : templates.subject("email.folder.create.subject.generic", recipient.locale());
            String intro = named
                    ? templates.subject("email.folder.create.intro.named", recipient.locale(), event.folderName())
                    : templates.subject("email.folder.create.intro.generic", recipient.locale());

            Map<String, Object> vars = new HashMap<>();
            vars.put("title", title);
            vars.put("intro", intro);
            vars.put("link", link);
            String html = templates.render("lu/folder_create", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }
}
