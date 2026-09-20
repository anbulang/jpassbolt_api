package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.Recipient;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.ResourceCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emails the creator a "you added the password X" confirmation — the port of PHP
 * {@code ResourceCreateEmailRedactor} (gate {@code send.password.create}, default
 * OFF).
 *
 * <p>Fires on {@link ResourceCreatedEvent} only {@code AFTER_COMMIT} and on the
 * {@code mailExecutor} thread; the gate is checked first (PHP's dispatcher
 * short-circuit). The single recipient is the creator (the event actor),
 * re-resolved through {@link RecipientResolver} so a since-disabled/deleted
 * creator is dropped. The four {@code show_*} settings (all default-off) gate
 * whether the body reveals the v4 plaintext username / URI / description and the
 * creator's own ciphertext; by default the email carries only the resource name
 * and a deep link. v5 resources use the generic (nameless) wording.</p>
 */
@Component
@RequiredArgsConstructor
public class ResourceCreateEmailRedactor {

    /** PHP ResourceCreateEmailRedactor::getNotificationSettingPath(). */
    private static final String SETTING_PATH = "send.password.create";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResourceCreated(ResourceCreatedEvent event) {
        if (!settings.isEnabled(SETTING_PATH)) {
            return;
        }

        Recipient creator = recipientResolver.resolveUser(event.actorId()).orElse(null);
        if (creator == null) {
            return; // creator since disabled/deleted — nothing to send
        }

        Map<String, Object> effective = settings.get();
        boolean showUsername = RedactorSupport.isOn(effective, "show_username");
        boolean showUri = RedactorSupport.isOn(effective, "show_uri");
        boolean showDescription = RedactorSupport.isOn(effective, "show_description");
        boolean showSecret = RedactorSupport.isOn(effective, "show_secret");

        boolean named = event.resourceName() != null && !event.resourceName().isEmpty();
        String link = templates.link("/app/passwords/view/" + event.resourceId());

        delivery.deliver(List.of(creator), recipient -> {
            String title = templates.subject("email.resource.create.title", recipient.locale());
            String subject = named
                    ? templates.subject("email.resource.create.subject.named", recipient.locale(), event.resourceName())
                    : templates.subject("email.resource.create.subject.generic", recipient.locale());
            String intro = named
                    ? templates.subject("email.resource.create.intro.named", recipient.locale(), event.resourceName())
                    : templates.subject("email.resource.create.intro.generic", recipient.locale());

            Map<String, Object> vars = new HashMap<>();
            vars.put("title", title);
            vars.put("intro", intro);
            vars.put("link", link);
            vars.put("showUsername", showUsername);
            vars.put("resourceUsername", event.resourceUsername());
            vars.put("showUri", showUri);
            vars.put("resourceUri", event.resourceUri());
            vars.put("showDescription", showDescription);
            vars.put("resourceDescription", event.resourceDescription());
            vars.put("showSecret", showSecret);
            vars.put("secret", showSecret ? event.creatorSecret() : null);

            String html = templates.render("lu/resource_create", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }
}
