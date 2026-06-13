package com.jpassbolt.api.controller;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.SecretRepository;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * SecretController provides REST endpoints for viewing secrets.
 * All endpoints enforce permission-based access control.
 */
@Slf4j
@RestController
@RequestMapping("/secrets")
@RequiredArgsConstructor
public class SecretController {

        private final SecretRepository secretRepository;
        private final UserRepository userRepository;
        private final PermissionRepository permissionRepository;

        /**
         * GET /secrets/resource/{resourceId}.json
         * Returns the secret for a specific resource. Requires READ permission.
         */
        @GetMapping(value = { "/resource/{resourceId}", "/resource/{resourceId}.json" })
        public ResponseEntity<Map<String, Object>> getSecretByResource(@PathVariable String resourceId) {
                String userId = getCurrentUserId();

                // Check READ permission
                if (!permissionRepository.userHasAccessIncludingGroups(resourceId, userId, Permission.READ)) {
                        return ResponseEntity.status(403)
                                        .body(createResponse("error", "You are not authorized to access this secret.",
                                                        null, "/secrets/resource/" + resourceId + ".json"));
                }

                Optional<Secret> secretOpt = secretRepository.findByResourceIdAndUserId(resourceId, userId);

                if (secretOpt.isEmpty()) {
                        return ResponseEntity.status(404)
                                        .body(createResponse("error", "The secret does not exist.",
                                                        null, "/secrets/resource/" + resourceId + ".json"));
                }

                Secret secret = secretOpt.get();

                Map<String, Object> secretData = new LinkedHashMap<>();
                secretData.put("id", secret.getId());
                secretData.put("user_id", secret.getUserId());
                secretData.put("resource_id", secret.getResourceId());
                secretData.put("data", secret.getData());
                secretData.put("created", secret.getCreated());
                secretData.put("modified", secret.getModified());

                return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                secretData, "/secrets/resource/" + resourceId + ".json"));
        }

        /**
         * PUT /secrets/resource/{resourceId}.json
         * Updates the secret for a specific resource. Requires UPDATE permission.
         */
        @PutMapping(value = { "/resource/{resourceId}", "/resource/{resourceId}.json" })
        public ResponseEntity<Map<String, Object>> updateSecret(
                        @PathVariable String resourceId,
                        @RequestBody Map<String, String> request) {
                String userId = getCurrentUserId();

                // Check UPDATE permission
                if (!permissionRepository.userHasAccessIncludingGroups(resourceId, userId, Permission.UPDATE)) {
                        return ResponseEntity.status(403)
                                        .body(createResponse("error", "You are not authorized to update this secret.",
                                                        null, "/secrets/resource/" + resourceId + ".json"));
                }

                Optional<Secret> secretOpt = secretRepository.findByResourceIdAndUserId(resourceId, userId);

                if (secretOpt.isEmpty()) {
                        return ResponseEntity.status(404)
                                        .body(createResponse("error", "The secret does not exist.",
                                                        null, "/secrets/resource/" + resourceId + ".json"));
                }

                Secret secret = secretOpt.get();
                String newData = request.get("data");

                if (newData == null || newData.isEmpty()) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error", "The secret data is required.",
                                                        null, "/secrets/resource/" + resourceId + ".json"));
                }

                secret.setData(newData);
                secretRepository.save(secret);

                Map<String, Object> secretData = new LinkedHashMap<>();
                secretData.put("id", secret.getId());
                secretData.put("user_id", secret.getUserId());
                secretData.put("resource_id", secret.getResourceId());
                secretData.put("data", secret.getData());
                secretData.put("created", secret.getCreated());
                secretData.put("modified", secret.getModified());

                return ResponseEntity.ok(createResponse("success", "The secret was updated.",
                                secretData, "/secrets/resource/" + resourceId + ".json"));
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

        private Map<String, Object> createResponse(String status, String message, Object body, String url) {
                // 迁移到共享信封工具：补 action(uuid) 等 spec required 字段，保留原 200/400 code 语义。
                return ApiResponse.withCode(status, message, body, "success".equals(status) ? 200 : 400, url);
        }
}
