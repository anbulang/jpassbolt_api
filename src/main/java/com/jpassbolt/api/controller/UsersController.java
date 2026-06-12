package com.jpassbolt.api.controller;

import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * UsersController provides REST endpoints for user management.
 * Essential for the sharing workflow — users need to know who they can share
 * with.
 *
 * Note on mappings: no class-level @RequestMapping. With Boot 3's
 * PathPatternParser, a class-level "/users" combined with a method-level
 * ".json" yields "/users/.json" (NOT "/users.json"), so the official
 * plugin's suffixed URLs would 404. Full method-level paths avoid that.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class UsersController {

        private final UserRepository userRepository;

        /**
         * GET /users.json
         * Returns all active, non-deleted users.
         */
        @GetMapping({ "/users", "/users.json" })
        public ResponseEntity<Map<String, Object>> getAllUsers() {
                List<User> users = userRepository.findAll().stream()
                                .filter(u -> Boolean.TRUE.equals(u.getActive()) && !Boolean.TRUE.equals(u.getDeleted()))
                                .collect(Collectors.toList());

                List<Map<String, Object>> userList = users.stream()
                                .map(this::toUserMap)
                                .collect(Collectors.toList());

                return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                                userList, "/users.json"));
        }

        /**
         * GET /users/{id}.json
         * Returns a single user by ID.
         */
        @GetMapping({ "/users/{id}", "/users/{id}.json" })
        public ResponseEntity<Map<String, Object>> getUser(@PathVariable String id) {
                return userRepository.findById(id)
                                .filter(u -> Boolean.TRUE.equals(u.getActive()) && !Boolean.TRUE.equals(u.getDeleted()))
                                .map(user -> ResponseEntity.ok(createResponse("success",
                                                "The operation was successful.", toUserMap(user),
                                                "/users/" + id + ".json")))
                                .orElse(ResponseEntity.status(404)
                                                .body(createResponse("error", "The user does not exist.", null,
                                                                "/users/" + id + ".json")));
        }

        private Map<String, Object> toUserMap(User user) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", user.getId());
                map.put("username", user.getUsername());
                map.put("role_id", user.getRoleId());
                map.put("active", user.getActive());
                map.put("created", user.getCreated());
                map.put("modified", user.getModified());
                return map;
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
}
