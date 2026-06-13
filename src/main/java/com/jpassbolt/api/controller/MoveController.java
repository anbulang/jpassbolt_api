package com.jpassbolt.api.controller;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.FoldersRelationsMoveService;
import com.jpassbolt.api.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * MoveController — PUT|POST /move/{foreignModel}/{foreignId}.json
 * (PHP FoldersRelationsMoveController; the route is registered for both PUT
 * and POST in the reference implementation).
 *
 * Body: {"folder_parent_id": "<uuid>"} or {"folder_parent_id": null} (root).
 * The key itself is required; success returns a null body.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MoveController {

    private final FoldersRelationsMoveService foldersRelationsMoveService;
    private final UserRepository userRepository;

    /**
     * Move an element (folder or resource) in the current user's tree.
     */
    @RequestMapping(value = "/move/{foreignModel}/{foreignId}.json",
            method = { RequestMethod.PUT, RequestMethod.POST })
    public ResponseEntity<Map<String, Object>> move(
            @PathVariable String foreignModel,
            @PathVariable String foreignId,
            @RequestBody(required = false) Map<String, Object> body) {
        String userId = getCurrentUserId();
        String url = "/move/" + foreignModel + "/" + foreignId + ".json";

        // PHP: the folder_parent_id key must be present (null allowed = root).
        if (body == null || !body.containsKey("folder_parent_id")) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "Could not validate move data. A folder parent identifier is required.");
        }
        Object rawFolderParentId = body.get("folder_parent_id");
        if (rawFolderParentId != null && !(rawFolderParentId instanceof String)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The folder parent identifier should be a valid UUID.");
        }

        foldersRelationsMoveService.move(foreignModel, foreignId, (String) rawFolderParentId, userId);

        return ResponseEntity.ok(createResponse("success", "The object has been moved successfully.",
                null, url));
    }

    private Map<String, Object> createResponse(String status, String message, Object body, String url) {
        // 迁移到共享信封工具：补 action(uuid) 等 spec required 字段，保留原 200/400 code 与 null→{} 语义。
        return ApiResponse.withCode(status, message, body, "success".equals(status) ? 200 : 400, url);
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
