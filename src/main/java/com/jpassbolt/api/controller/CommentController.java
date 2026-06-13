package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.CommentDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Comment;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.CommentService;
import com.jpassbolt.api.service.ResourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * CommentController provides REST endpoints for managing plaintext comments
 * attached to resources. Reading and adding comments requires READ access on
 * the resource; updating/deleting a comment is restricted to its creator.
 */
@Slf4j
@RestController
@RequestMapping("/comments")
@RequiredArgsConstructor
public class CommentController {

        private static final Pattern UUID_PATTERN = Pattern.compile(
                        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

        private final CommentService commentService;
        private final ResourceService resourceService;
        private final UserRepository userRepository;
        private final PermissionRepository permissionRepository;

        /**
         * GET /comments/resource/{resourceId}.json
         * Returns the threaded comments of a resource (most recently modified
         * first). Requires READ permission on the resource.
         * Supports contain[creator]=1 / contain[modifier]=1 to embed the
         * creator / modifier user objects.
         */
        @GetMapping("/resource/{resourceId}.json")
        public ResponseEntity<Map<String, Object>> getComments(
                        @PathVariable String resourceId,
                        @RequestParam(name = "contain[creator]", required = false) String containCreator,
                        @RequestParam(name = "contain[modifier]", required = false) String containModifier) {
                String url = "/comments/resource/" + resourceId + ".json";
                String userId = getCurrentUserId();

                if (!isUuid(resourceId)) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error",
                                                        "The resource identifier should be a valid UUID.", null, url));
                }
                if (resourceService.getResourceById(resourceId).isEmpty()) {
                        return ResponseEntity.status(404)
                                        .body(createResponse("error", "The resource does not exist.", null, url));
                }
                if (!permissionRepository.userHasAccessIncludingGroups(resourceId, userId, Permission.READ)) {
                        return ResponseEntity.status(403)
                                        .body(createResponse("error",
                                                        "You are not authorized to access this resource.", null, url));
                }

                List<Comment> comments = commentService.getCommentsForResource(resourceId);
                List<CommentDto.Response> responseList = buildThreadedResponses(comments,
                                isTruthy(containCreator), isTruthy(containModifier));

                return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                responseList, url));
        }

        /**
         * POST /comments/resource/{resourceId}.json
         * Adds a comment (or a nested reply via parent_id) to a resource.
         * Requires READ permission on the resource.
         */
        @PostMapping("/resource/{resourceId}.json")
        public ResponseEntity<Map<String, Object>> addComment(
                        @PathVariable String resourceId,
                        @RequestBody CommentDto.CreateRequest request) {
                String url = "/comments/resource/" + resourceId + ".json";
                String userId = getCurrentUserId();

                if (!isUuid(resourceId)) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error",
                                                        "The resource identifier should be a valid UUID.", null, url));
                }
                if (resourceService.getResourceById(resourceId).isEmpty()) {
                        return ResponseEntity.status(404)
                                        .body(createResponse("error", "The resource does not exist.", null, url));
                }
                if (!permissionRepository.userHasAccessIncludingGroups(resourceId, userId, Permission.READ)) {
                        return ResponseEntity.status(403)
                                        .body(createResponse("error",
                                                        "You are not authorized to access this resource.", null, url));
                }

                Comment comment = commentService.addComment(resourceId, request, userId);
                return ResponseEntity.ok(createResponse("success", "The comment was successfully added.",
                                toResponseDto(comment, null, null), url));
        }

        /**
         * PUT /comments/{commentId}.json
         * Updates a comment's content. Only the comment creator is allowed.
         */
        @PutMapping("/{commentId}.json")
        public ResponseEntity<Map<String, Object>> updateComment(
                        @PathVariable String commentId,
                        @RequestBody CommentDto.UpdateRequest request) {
                String url = "/comments/" + commentId + ".json";
                String userId = getCurrentUserId();

                if (!isUuid(commentId)) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error", "The comment id is not valid.", null, url));
                }

                Comment comment = commentService.updateComment(commentId, request.getContent(), userId);
                return ResponseEntity.ok(createResponse("success", "The comment was successfully updated.",
                                toResponseDto(comment, null, null), url));
        }

        /**
         * DELETE /comments/{commentId}.json
         * Hard deletes a comment. Only the comment creator is allowed; an
         * attempt by another user yields 404 (existence is not disclosed).
         */
        @DeleteMapping("/{commentId}.json")
        public ResponseEntity<Map<String, Object>> deleteComment(@PathVariable String commentId) {
                String url = "/comments/" + commentId + ".json";
                String userId = getCurrentUserId();

                if (!isUuid(commentId)) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error", "The comment id is not valid.", null, url));
                }

                commentService.deleteComment(commentId, userId);
                return ResponseEntity.ok(createResponse("success", "The comment was deleted.", null, url));
        }

        /**
         * Assemble the threaded comment tree: top-level comments (parent_id null)
         * with their nested replies as children. Orphaned replies (whose parent
         * was deleted) are not returned, matching the threaded finder behavior
         * of the official implementation.
         */
        private List<CommentDto.Response> buildThreadedResponses(List<Comment> comments,
                        boolean withCreator, boolean withModifier) {
                Map<String, User> userMap = loadUsers(comments, withCreator, withModifier);
                Map<String, List<Comment>> byParent = comments.stream()
                                .filter(c -> c.getParentId() != null)
                                .collect(Collectors.groupingBy(Comment::getParentId));

                return comments.stream()
                                .filter(c -> c.getParentId() == null)
                                .map(c -> toThreadedResponseDto(c, byParent, userMap, withCreator, withModifier))
                                .collect(Collectors.toList());
        }

        private CommentDto.Response toThreadedResponseDto(Comment comment,
                        Map<String, List<Comment>> byParent, Map<String, User> userMap,
                        boolean withCreator, boolean withModifier) {
                CommentDto.Response response = toResponseDto(comment,
                                withCreator ? userMap.get(comment.getCreatedBy()) : null,
                                withModifier ? userMap.get(comment.getModifiedBy()) : null);
                List<Comment> replies = byParent.getOrDefault(comment.getId(), List.of());
                response.setChildren(replies.stream()
                                .map(reply -> toThreadedResponseDto(reply, byParent, userMap, withCreator,
                                                withModifier))
                                .collect(Collectors.toList()));
                return response;
        }

        /**
         * Bulk-load the users referenced as creator/modifier by the comments,
         * avoiding lazy-loading outside of a transaction.
         */
        private Map<String, User> loadUsers(List<Comment> comments, boolean withCreator, boolean withModifier) {
                if (!withCreator && !withModifier) {
                        return Map.of();
                }
                Set<String> userIds = new HashSet<>();
                for (Comment comment : comments) {
                        if (withCreator) {
                                userIds.add(comment.getCreatedBy());
                        }
                        if (withModifier) {
                                userIds.add(comment.getModifiedBy());
                        }
                }
                if (userIds.isEmpty()) {
                        return Map.of();
                }
                return userRepository.findAllById(userIds).stream()
                                .collect(Collectors.toMap(User::getId, Function.identity()));
        }

        private CommentDto.Response toResponseDto(Comment comment, User creator, User modifier) {
                return CommentDto.Response.builder()
                                .id(comment.getId())
                                .parentId(comment.getParentId())
                                .foreignKey(comment.getForeignKey())
                                .foreignModel(comment.getForeignModel())
                                .content(comment.getContent())
                                .created(comment.getCreated())
                                .modified(comment.getModified())
                                .createdBy(comment.getCreatedBy())
                                .modifiedBy(comment.getModifiedBy())
                                .userId(comment.getUserId())
                                .creator(creator != null ? toUserResponseDto(creator) : null)
                                .modifier(modifier != null ? toUserResponseDto(modifier) : null)
                                .build();
        }

        private CommentDto.UserResponse toUserResponseDto(User user) {
                return CommentDto.UserResponse.builder()
                                .id(user.getId())
                                .roleId(user.getRoleId())
                                .username(user.getUsername())
                                .active(user.getActive())
                                .deleted(user.getDeleted())
                                .created(user.getCreated())
                                .modified(user.getModified())
                                .disabled(user.getDisabled())
                                .build();
        }

        private boolean isTruthy(String value) {
                return "1".equals(value) || "true".equalsIgnoreCase(value);
        }

        private boolean isUuid(String value) {
                return value != null && UUID_PATTERN.matcher(value).matches();
        }

        private Map<String, Object> createResponse(String status, String message, Object body, String url) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("header", Map.of(
                                "id", java.util.UUID.randomUUID().toString(),
                                "status", status,
                                "servertime", System.currentTimeMillis() / 1000,
                                "code", "success".equals(status) ? 200 : 400,
                                "message", message,
                                "url", url));
                response.put("body", body != null ? body : new LinkedHashMap<>());
                return response;
        }

        private String getCurrentUserId() {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || auth.getName() == null) {
                        throw new PassboltApiException(HttpStatus.UNAUTHORIZED, "No authenticated user");
                }
                String username = auth.getName();
                Optional<User> user = userRepository.findByUsername(username);
                return user.map(User::getId)
                                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                                                "User not found: " + username));
        }
}
