package com.jpassbolt.api.service.email.event;

/**
 * Published by {@code CommentService.addComment()} after a comment commits,
 * consumed by {@code CommentAddEmailRedactor} to notify everyone with access to
 * the commented resource (minus the commenter). Port of PHP
 * {@code CommentsAddService}'s {@code addPost.success} event consumed by
 * {@code CommentAddEmailRedactor}.
 *
 * <p>Like PHP (which puts only the comment on the event), this is a thin
 * snapshot: the resource name and the commenter's display name are re-resolved in
 * the listener (the resource and commenter both survive a comment-add, so a
 * re-query is safe). The comment {@link #content} is carried because it is the
 * only field the (default-off) {@code show_comment} body block needs and the row,
 * though persisted, is plaintext and cheap to carry.</p>
 *
 * @param commentId  the saved comment id (traceability/logging)
 * @param resourceId the commented resource (recipient resolution + deep link)
 * @param actorId    the commenter (excluded from recipients)
 * @param content    the plaintext comment (rendered only when {@code show_comment})
 */
public record ResourceCommentedEvent(
        String commentId,
        String resourceId,
        String actorId,
        String content) {
}
