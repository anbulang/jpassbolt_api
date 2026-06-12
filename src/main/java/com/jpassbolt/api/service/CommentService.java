package com.jpassbolt.api.service;

import com.jpassbolt.api.dto.CommentDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Comment;
import com.jpassbolt.api.repository.CommentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service for managing plaintext comments attached to resources.
 *
 * Business rules (ported from the official Passbolt PHP implementation,
 * src/Service/Comments/*):
 * - content is mandatory, 1 to 256 characters (comments are NOT E2EE);
 * - parent_id, when provided, must reference an existing comment on the
 *   same resource (nested replies);
 * - only the comment creator can update it (403 otherwise);
 * - only the comment creator can delete it; a delete attempt by another
 *   user yields 404 to avoid disclosing the comment's existence (this
 *   mirrors CommentsDeleteService::_handleDeleteErrors);
 * - deletion is a hard delete (the comments table has no deleted column).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    /** Maximum comment content length (comments.content is varchar(256)). */
    public static final int MAX_CONTENT_LENGTH = 256;

    private final CommentRepository commentRepository;

    /**
     * Get all comments attached to a resource, most recently modified first.
     *
     * @param resourceId the resource ID
     * @return flat list of comments (threading is assembled by the caller)
     */
    @Transactional(readOnly = true)
    public List<Comment> getCommentsForResource(String resourceId) {
        return commentRepository.findByResourceId(resourceId);
    }

    /**
     * Add a comment (or a nested reply) to a resource.
     *
     * @param resourceId the resource the comment is attached to
     * @param request    the create request (content + optional parent_id)
     * @param userId     the author's user ID
     * @return the saved comment
     * @throws PassboltApiException 400 on validation failure
     */
    @Transactional
    public Comment addComment(String resourceId, CommentDto.CreateRequest request, String userId) {
        String content = validateContent(request.getContent());

        if (request.getParentId() != null) {
            boolean parentValid = commentRepository.findById(request.getParentId())
                    .filter(parent -> Comment.RESOURCE_FOREIGN_MODEL.equals(parent.getForeignModel())
                            && parent.getForeignKey().equals(resourceId))
                    .isPresent();
            if (!parentValid) {
                throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                        "The parent comment does not exist.");
            }
        }

        Comment comment = new Comment();
        comment.setParentId(request.getParentId());
        comment.setForeignKey(resourceId);
        comment.setForeignModel(Comment.RESOURCE_FOREIGN_MODEL);
        comment.setContent(content);
        comment.setCreatedBy(userId);
        comment.setModifiedBy(userId);
        comment.setUserId(userId);

        Comment saved = commentRepository.save(comment);
        log.debug("Comment {} added on resource {} by user {}", saved.getId(), resourceId, userId);
        return saved;
    }

    /**
     * Update a comment's content. Only the comment creator is allowed.
     *
     * @param commentId the comment ID
     * @param content   the new content
     * @param userId    the requesting user's ID
     * @return the updated comment
     * @throws PassboltApiException 404 if the comment does not exist,
     *                              403 if the user is not the creator,
     *                              400 on validation failure
     */
    @Transactional
    public Comment updateComment(String commentId, String content, String userId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "The comment does not exist."));

        if (!comment.getUserId().equals(userId)) {
            throw new PassboltApiException(HttpStatus.FORBIDDEN,
                    "You are not allowed to edit this comment.");
        }

        comment.setContent(validateContent(content));
        comment.setModifiedBy(userId);
        return commentRepository.save(comment);
    }

    /**
     * Hard delete a comment. Only the comment creator is allowed; a delete
     * attempt by another user returns 404 (existence is not disclosed),
     * matching the official Passbolt behavior.
     *
     * @param commentId the comment ID
     * @param userId    the requesting user's ID
     * @throws PassboltApiException 404 if not found or not the creator
     */
    @Transactional
    public void deleteComment(String commentId, String userId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "The comment does not exist."));

        if (!comment.getUserId().equals(userId)) {
            throw new PassboltApiException(HttpStatus.NOT_FOUND,
                    "The comment does not exist.");
        }

        commentRepository.delete(comment);
        log.debug("Comment {} deleted by user {}", commentId, userId);
    }

    /**
     * Validate the comment content: mandatory, 1 to 256 characters.
     *
     * @param content the raw content
     * @return the validated content
     * @throws PassboltApiException 400 if invalid
     */
    private String validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "A comment content is required.");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The content length should be between 1 and " + MAX_CONTENT_LENGTH + " characters.");
        }
        return content;
    }
}
