package com.jpassbolt.api.controller;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.PermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ShareController provides REST endpoints for sharing resources.
 */
@Slf4j
@RestController
@RequestMapping("/share")
@RequiredArgsConstructor
public class ShareController {

        private final PermissionService permissionService;
        private final UserRepository userRepository;

        /**
         * PUT /share/resource/{resourceId}.json
         * Share a resource with other users by updating permissions and providing
         * encrypted secrets.
         */
        @PutMapping(value = { "/resource/{resourceId}", "/resource/{resourceId}.json" })
        public ResponseEntity<Map<String, Object>> share(
                        @PathVariable String resourceId,
                        @RequestBody Map<String, Object> request) {
                String userId = getCurrentUserId();

                try {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> permissions = (List<Map<String, Object>>) request.getOrDefault(
                                        "permissions",
                                        List.of());
                        @SuppressWarnings("unchecked")
                        List<Map<String, String>> secrets = (List<Map<String, String>>) request.getOrDefault("secrets",
                                        List.of());

                        permissionService.share(resourceId, userId, permissions, secrets);

                        return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                        null, "/share/resource/" + resourceId + ".json"));
                } catch (SecurityException e) {
                        return ResponseEntity.status(403)
                                        .body(createResponse("error", e.getMessage(), null,
                                                        "/share/resource/" + resourceId + ".json"));
                } catch (IllegalArgumentException e) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error", e.getMessage(), null,
                                                        "/share/resource/" + resourceId + ".json"));
                } catch (IllegalStateException e) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error", e.getMessage(), null,
                                                        "/share/resource/" + resourceId + ".json"));
                }
        }

        /**
         * POST /share/simulate/{resourceId}.json
         * Simulate sharing a resource (dry run). Returns which users would be
         * added/removed.
         */
        @PostMapping(value = { "/simulate/{resourceId}", "/simulate/{resourceId}.json" })
        public ResponseEntity<Map<String, Object>> simulate(
                        @PathVariable String resourceId,
                        @RequestBody Map<String, Object> request) {
                String userId = getCurrentUserId();

                try {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> permissions = (List<Map<String, Object>>) request.getOrDefault(
                                        "permissions",
                                        List.of());

                        Map<String, List<String>> dryRunResult = permissionService.shareDryRun(resourceId, userId,
                                        permissions);

                        // Format output to match Passbolt structure
                        Map<String, Object> changes = new LinkedHashMap<>();
                        List<Map<String, Object>> added = dryRunResult.get("added").stream()
                                        .map(uid -> {
                                                Map<String, Object> entry = new LinkedHashMap<>();
                                                entry.put("User", Map.of("id", uid));
                                                return entry;
                                        })
                                        .collect(Collectors.toList());
                        List<Map<String, Object>> removed = dryRunResult.get("deleted").stream()
                                        .map(uid -> {
                                                Map<String, Object> entry = new LinkedHashMap<>();
                                                entry.put("User", Map.of("id", uid));
                                                return entry;
                                        })
                                        .collect(Collectors.toList());
                        changes.put("added", added);
                        changes.put("removed", removed);

                        Map<String, Object> body = Map.of("changes", changes);

                        return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                        body, "/share/simulate/" + resourceId + ".json"));
                } catch (SecurityException e) {
                        return ResponseEntity.status(403)
                                        .body(createResponse("error", e.getMessage(), null,
                                                        "/share/simulate/" + resourceId + ".json"));
                } catch (IllegalArgumentException e) {
                        return ResponseEntity.status(400)
                                        .body(createResponse("error", e.getMessage(), null,
                                                        "/share/simulate/" + resourceId + ".json"));
                }
        }

        /**
         * GET /share/resource/{resourceId}/permissions.json
         * Get all permissions for a resource.
         */
        @GetMapping(value = { "/resource/{resourceId}/permissions", "/resource/{resourceId}/permissions.json" })
        public ResponseEntity<Map<String, Object>> getPermissions(@PathVariable String resourceId) {
                List<Permission> permissions = permissionService.getResourcePermissions(resourceId);

                List<Map<String, Object>> permList = permissions.stream()
                                .map(p -> {
                                        Map<String, Object> m = new LinkedHashMap<>();
                                        m.put("id", p.getId());
                                        m.put("aco", p.getAco());
                                        m.put("aco_foreign_key", p.getAcoForeignKey());
                                        m.put("aro", p.getAro());
                                        m.put("aro_foreign_key", p.getAroForeignKey());
                                        m.put("type", p.getType());
                                        m.put("created", p.getCreated());
                                        m.put("modified", p.getModified());
                                        return m;
                                })
                                .collect(Collectors.toList());

                return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                permList, "/share/resource/" + resourceId + "/permissions.json"));
        }

        /**
         * Get the current authenticated user's ID.
         */
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

        /**
         * Create a Passbolt-style response body.
         */
        private Map<String, Object> createResponse(String status, String message, Object body, String url) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("header", Map.of(
                                "id", UUID.randomUUID().toString(),
                                "status", status,
                                "servertime", System.currentTimeMillis() / 1000,
                                "code", "success".equals(status) ? 200 : 400,
                                "message", message,
                                "url", url));
                response.put("body", body != null ? body : new LinkedHashMap<>());
                return response;
        }
}
