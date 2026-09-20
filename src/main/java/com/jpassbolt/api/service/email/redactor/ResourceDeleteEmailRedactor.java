package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.Recipient;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.ResourceDeletedEvent;
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
 * Emails everyone who had access (except the deleter) when a resource is deleted —
 * the port of PHP {@code ResourceDeleteEmailRedactor} (gate
 * {@code send.password.delete}, default ON).
 *
 * <p>Fires on {@link ResourceDeletedEvent} only {@code AFTER_COMMIT} and on the
 * {@code mailExecutor} thread. The delete is a soft delete — the permission rows
 * survive — so recipients are re-resolved through
 * {@link RecipientResolver#resolveUsersWithAccessToResource} and the actor is
 * filtered out here (PHP removes the deleter from {@code $users} before the
 * redactor runs); disabled users are dropped by the resolver. There is no self
 * variant and no {@code show_secret} block — the password is gone; only the
 * default-off username / URI / description disclosures apply, and the email
 * carries no live deep link.</p>
 */
@Component
@RequiredArgsConstructor
public class ResourceDeleteEmailRedactor {

    /** PHP ResourceDeleteEmailRedactor::getNotificationSettingPath(). */
    private static final String SETTING_PATH = "send.password.delete";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResourceDeleted(ResourceDeletedEvent event) {
        if (!settings.isEnabled(SETTING_PATH)) {
            return;
        }

        Set<Recipient> recipients = recipientResolver.resolveUsersWithAccessToResource(event.resourceId()).stream()
                .filter(r -> !r.userId().equals(event.actorId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (recipients.isEmpty()) {
            return;
        }

        String actorFirstName = RedactorSupport.actorFirstName(recipientResolver, event.actorId());
        boolean named = event.resourceName() != null && !event.resourceName().isEmpty();

        Map<String, Object> effective = settings.get();
        boolean showUsername = RedactorSupport.isOn(effective, "show_username");
        boolean showUri = RedactorSupport.isOn(effective, "show_uri");
        boolean showDescription = RedactorSupport.isOn(effective, "show_description");

        delivery.deliver(recipients, recipient -> {
            String title = templates.subject("email.resource.delete.title", recipient.locale());
            String subject = named
                    ? templates.subject("email.resource.delete.subject.named", recipient.locale(),
                            actorFirstName, event.resourceName())
                    : templates.subject("email.resource.delete.subject.generic", recipient.locale(), actorFirstName);
            String intro = named
                    ? templates.subject("email.resource.delete.intro.named", recipient.locale(),
                            actorFirstName, event.resourceName())
                    : templates.subject("email.resource.delete.intro.generic", recipient.locale(), actorFirstName);

            Map<String, Object> vars = new HashMap<>();
            vars.put("title", title);
            vars.put("intro", intro);
            // No link: the password is gone. The template omits the CTA when link is null.
            vars.put("link", null);
            vars.put("showUsername", showUsername);
            vars.put("resourceUsername", event.resourceUsername());
            vars.put("showUri", showUri);
            vars.put("resourceUri", event.resourceUri());
            vars.put("showDescription", showDescription);
            vars.put("resourceDescription", event.resourceDescription());
            // show_secret intentionally absent on delete (no secret to reveal).
            vars.put("showSecret", false);
            vars.put("secret", null);

            String html = templates.render("lu/resource_delete", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }
}
