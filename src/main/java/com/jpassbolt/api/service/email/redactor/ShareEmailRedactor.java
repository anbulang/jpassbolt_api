package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.Recipient;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.ResourceSharedEvent;
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
 * Emails each user newly granted access to a resource — the port of PHP
 * {@code ShareEmailRedactor} (gate {@code send.password.share}, content gates
 * {@code show.username/uri/description/secret}).
 *
 * <p>Fires on {@link ResourceSharedEvent} only {@code AFTER_COMMIT} (no mail on a
 * rolled-back share) and on the {@code mailExecutor} thread (off the request
 * path). The gate is checked first — exactly PHP's
 * {@code EmailSubscriptionDispatcher} short-circuit, which skips recipient/body
 * work entirely when the setting is off.</p>
 *
 * <p>Recipients are the event's newly-added users, re-resolved through
 * {@link RecipientResolver} (which drops disabled/soft-deleted users and supplies
 * each one's locale) and defensively filtered to exclude the actor. The four
 * {@code show_*} settings (all default-off) gate whether the body reveals the v4
 * plaintext username / URI / description and the per-recipient secret ciphertext;
 * by default the email carries only the resource name and a deep link.</p>
 */
@Component
@RequiredArgsConstructor
public class ShareEmailRedactor {

    /** PHP ShareEmailRedactor::getNotificationSettingPath(). */
    private static final String SETTING_PATH = "send.password.share";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResourceShared(ResourceSharedEvent event) {
        if (!settings.isEnabled(SETTING_PATH)) {
            return; // master gate off — send nothing (PHP isRedactorActive short-circuit)
        }
        if (event.addedUserIds() == null || event.addedUserIds().isEmpty()) {
            return; // revoke-only / type-change-only share notifies no one
        }

        Set<Recipient> recipients = recipientResolver.resolveUsers(event.addedUserIds()).stream()
                .filter(r -> !r.userId().equals(event.actorId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (recipients.isEmpty()) {
            return;
        }

        // PHP uses the operator's FIRST name in both the subject
        // (ShareEmailRedactor::createShareEmail, `$owner->profile->first_name`)
        // and the body (templates/email/html/LU/resource_share.php). This used to
        // read fullName, which put "Ada Lovelace shared…" next to the six folder /
        // resource CUD mails' "Ada edited…" in the same inbox.
        String actorName = RedactorSupport.actorFirstName(recipientResolver, event.actorId());

        // v4 rows carry the plaintext name (named wording); a v5 row's name lives
        // in encrypted metadata and arrives null (generic wording) — the same
        // branch the folder/resource CUD redactors take.
        boolean named = event.resourceName() != null && !event.resourceName().isEmpty();

        Map<String, Object> effective = settings.get();
        boolean showUsername = isOn(effective, "show_username");
        boolean showUri = isOn(effective, "show_uri");
        boolean showDescription = isOn(effective, "show_description");
        boolean showSecret = isOn(effective, "show_secret");

        String link = templates.link("/app/passwords/view/" + event.resourceId());

        delivery.deliver(recipients, recipient -> {
            Map<String, Object> vars = new HashMap<>();
            vars.put("actorName", actorName);
            vars.put("resourceName", event.resourceName());
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

            String subject = named
                    ? templates.subject("email.resource.share.subject.named", recipient.locale(),
                            actorName, event.resourceName())
                    : templates.subject("email.resource.share.subject.generic", recipient.locale(), actorName);
            String html = templates.render("lu/resource_share", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }

    private static boolean isOn(Map<String, Object> settings, String key) {
        return Boolean.TRUE.equals(settings.get(key));
    }
}
