package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.Recipient;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.ResourceUpdatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Emails everyone with access when a resource is updated — the port of PHP
 * {@code ResourceUpdateEmailRedactor} (gate {@code send.password.update}, default
 * ON).
 *
 * <p>Fires on {@link ResourceUpdatedEvent} only {@code AFTER_COMMIT} and on the
 * {@code mailExecutor} thread. Recipients are re-resolved through
 * {@link RecipientResolver#resolveUsersWithAccessToResource} — an update removes
 * neither the resource nor its permissions, so "who has access" is still
 * answerable post-commit, matching PHP's {@code has-access} query. The operator is
 * kept and receives the "You edited …" wording; everyone else receives "{actor}
 * edited …". Following PHP, the update copy speaks of a "resource" (v4 names it,
 * v5 is generic). The four {@code show_*} settings (all default-off) gate the v4
 * plaintext disclosures and each recipient's <em>own</em> re-written ciphertext
 * (never another user's).</p>
 */
@Component
@RequiredArgsConstructor
public class ResourceUpdateEmailRedactor {

    /** PHP ResourceUpdateEmailRedactor::getNotificationSettingPath(). */
    private static final String SETTING_PATH = "send.password.update";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResourceUpdated(ResourceUpdatedEvent event) {
        if (!settings.isEnabled(SETTING_PATH)) {
            return;
        }

        Set<Recipient> recipients = recipientResolver.resolveUsersWithAccessToResource(event.resourceId());
        if (recipients.isEmpty()) {
            return;
        }

        String actorFirstName = RedactorSupport.actorFirstName(recipientResolver, event.actorId());
        boolean named = event.resourceName() != null && !event.resourceName().isEmpty();

        Map<String, Object> effective = settings.get();
        boolean showUsername = RedactorSupport.isOn(effective, "show_username");
        boolean showUri = RedactorSupport.isOn(effective, "show_uri");
        boolean showDescription = RedactorSupport.isOn(effective, "show_description");
        boolean showSecret = RedactorSupport.isOn(effective, "show_secret");

        String link = templates.link("/app/passwords/view/" + event.resourceId());

        delivery.deliver(recipients, recipient -> {
            boolean isOperator = recipient.userId().equals(event.actorId());
            String title = templates.subject("email.resource.update.title", recipient.locale());
            String subject = subject(recipient, isOperator, named, actorFirstName, event.resourceName());
            String intro = intro(recipient, isOperator, named, actorFirstName, event.resourceName());

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
            vars.put("secret", showSecret && event.secretsByUserId() != null
                    ? event.secretsByUserId().get(recipient.userId()) : null);

            String html = templates.render("lu/resource_update", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }

    private String subject(Recipient r, boolean isOperator, boolean named,
            String actorFirstName, String resourceName) {
        if (isOperator) {
            return named
                    ? templates.subject("email.resource.update.subject.self.named", r.locale(), resourceName)
                    : templates.subject("email.resource.update.subject.self.generic", r.locale());
        }
        return named
                ? templates.subject("email.resource.update.subject.other.named", r.locale(), actorFirstName, resourceName)
                : templates.subject("email.resource.update.subject.other.generic", r.locale(), actorFirstName);
    }

    private String intro(Recipient r, boolean isOperator, boolean named,
            String actorFirstName, String resourceName) {
        if (isOperator) {
            return named
                    ? templates.subject("email.resource.update.intro.self.named", r.locale(), resourceName)
                    : templates.subject("email.resource.update.intro.self.generic", r.locale());
        }
        return named
                ? templates.subject("email.resource.update.intro.other.named", r.locale(), actorFirstName, resourceName)
                : templates.subject("email.resource.update.intro.other.generic", r.locale(), actorFirstName);
    }
}
