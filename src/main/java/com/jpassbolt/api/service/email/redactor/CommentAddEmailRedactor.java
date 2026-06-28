package com.jpassbolt.api.service.email.redactor;

import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.service.EmailNotificationSettingsService;
import com.jpassbolt.api.service.email.EmailMessage;
import com.jpassbolt.api.service.email.EmailTemplateService;
import com.jpassbolt.api.service.email.Recipient;
import com.jpassbolt.api.service.email.RecipientResolver;
import com.jpassbolt.api.service.email.event.ResourceCommentedEvent;
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
 * Notifies everyone with access to a resource that a comment was added — the port
 * of PHP {@code CommentAddEmailRedactor} (gate {@code send.comment.add}, content
 * gate {@code show.comment}).
 *
 * <p>Recipients are all users with access to the commented resource (direct or via
 * group), resolved through {@link RecipientResolver} and minus the commenter. The
 * resource name and commenter display name are re-resolved here (both survive a
 * comment-add); the comment text appears in the body only when {@code show_comment}
 * is on (default off). For a v5 resource (no plaintext name) the subject/intro
 * fall back to a nameless variant.</p>
 */
@Component
@RequiredArgsConstructor
public class CommentAddEmailRedactor {

    private static final String SETTING_PATH = "send.comment.add";

    private final EmailNotificationSettingsService settings;
    private final RecipientResolver recipientResolver;
    private final EmailTemplateService templates;
    private final NotificationDelivery delivery;
    private final ResourceRepository resourceRepository;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResourceCommented(ResourceCommentedEvent event) {
        if (!settings.isEnabled(SETTING_PATH)) {
            return;
        }

        Set<Recipient> recipients = recipientResolver
                .resolveUsersWithAccessToResource(event.resourceId()).stream()
                .filter(r -> !r.userId().equals(event.actorId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (recipients.isEmpty()) {
            return;
        }

        String actorName = recipientResolver.resolveUser(event.actorId())
                .map(Recipient::fullName).orElse("");
        String resourceName = resourceRepository.findById(event.resourceId())
                .map(Resource::getName).orElse(null);
        boolean hasName = resourceName != null && !resourceName.isEmpty();
        boolean showComment = Boolean.TRUE.equals(settings.get().get("show_comment"));

        String link = templates.link("/app/passwords/view/" + event.resourceId());
        String subjectKey = hasName ? "email.comment.add.subject" : "email.comment.add.subject.noname";

        delivery.deliver(recipients, recipient -> {
            Map<String, Object> vars = new HashMap<>();
            vars.put("actorName", actorName);
            vars.put("resourceName", resourceName);
            vars.put("link", link);
            vars.put("showComment", showComment);
            vars.put("comment", showComment ? event.content() : null);

            String subject = hasName
                    ? templates.subject(subjectKey, recipient.locale(), actorName, resourceName)
                    : templates.subject(subjectKey, recipient.locale(), actorName);
            String html = templates.render("lu/comment_add", recipient.locale(), vars);
            return new EmailMessage(recipient.email(), subject, html);
        });
    }
}
