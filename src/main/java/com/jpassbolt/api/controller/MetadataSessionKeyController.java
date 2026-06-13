package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.MetadataSessionKeyDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.MetadataSessionKey;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.MetadataSessionKeyService;
import com.jpassbolt.api.util.ApiResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MetadataSessionKeyController exposes the per-user cache of v5 metadata
 * <em>session keys</em>: OpenPGP-encrypted session key blobs the client stores
 * and refreshes server-side. Part of the zero-knowledge v5 metadata system —
 * the server only stores and forwards the armored ciphertext in {@code data};
 * it never decrypts it (Iron Law 1: parse-only Bouncy Castle validation lives
 * in the service layer).
 *
 * <p>Endpoints (権威定義 = OpenAPI tag "Metadata session key"):</p>
 * <ul>
 *   <li>{@code GET  /metadata/session-keys.json} — index of the current user's
 *       session keys ({@code metadataSessionKeyIndexAndView[]}).</li>
 *   <li>{@code POST /metadata/session-keys.json} — add a session key
 *       ({@code e2eeDataOnly} body, {@code metadataSessionKeyIndexAndView}
 *       response).</li>
 *   <li>{@code POST /metadata/session-key/{sessionKeyId}.json} — update one
 *       ({@code e2eeDataModified} body + response; 409 on optimistic-lock
 *       mismatch).</li>
 *   <li>{@code DELETE /metadata/session-key/{sessionKeyId}.json} — delete one
 *       (null body; 404 when not owned).</li>
 * </ul>
 *
 * <p>All operations are scoped to the requesting user's own rows. A foreign or
 * missing session key id yields 404 (existence is never disclosed), enforced in
 * {@link MetadataSessionKeyService}. JWT protection is provided by the global
 * SecurityConfig ({@code /metadata/**} falls through to
 * {@code anyRequest().authenticated()}); these endpoints carry no admin gate.</p>
 */
@Slf4j
@RestController
@RequestMapping("/metadata")
@RequiredArgsConstructor
public class MetadataSessionKeyController {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final MetadataSessionKeyService metadataSessionKeyService;
    private final UserRepository userRepository;

    /**
     * GET /metadata/session-keys.json
     * Returns the current user's cached session keys (never another user's),
     * most recently modified first. Response body is an array of
     * metadataSessionKeyIndexAndView.
     */
    @GetMapping("/session-keys.json")
    public ResponseEntity<Map<String, Object>> index() {
        String url = "/metadata/session-keys.json";
        String userId = getCurrentUserId();

        List<MetadataSessionKeyDto.Response> body = metadataSessionKeyService.findByUser(userId).stream()
                .map(this::toResponseDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(createResponse("success", "The operation was successful.", body, url));
    }

    /**
     * POST /metadata/session-keys.json
     * Adds a session key for the current user. The {@code data} blob is
     * validated as a parsable OpenPGP MESSAGE (never decrypted) before being
     * stored. Response body is the created metadataSessionKeyIndexAndView.
     */
    @PostMapping("/session-keys.json")
    public ResponseEntity<Map<String, Object>> add(
            @RequestBody MetadataSessionKeyDto.CreateRequest request) {
        String url = "/metadata/session-keys.json";
        String userId = getCurrentUserId();

        String data = request != null ? request.getData() : null;
        MetadataSessionKey created = metadataSessionKeyService.create(userId, data);

        return ResponseEntity.ok(createResponse("success", "The operation was successful.",
                toResponseDto(created), url));
    }

    /**
     * POST /metadata/session-key/{sessionKeyId}.json
     * Updates the current user's session key data with an optimistic-lock check
     * against the client-supplied {@code modified} timestamp. Foreign/missing id
     * -> 404; stale {@code modified} -> 409 (modifiedDateIsNotMatching). Response
     * body is the e2eeDataModified shape ({@code data, modified}).
     */
    @PostMapping("/session-key/{sessionKeyId}.json")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String sessionKeyId,
            @RequestBody MetadataSessionKeyDto.UpdateRequest request) {
        String url = "/metadata/session-key/" + sessionKeyId + ".json";
        String userId = getCurrentUserId();

        if (!isUuid(sessionKeyId)) {
            return ResponseEntity.status(400)
                    .body(createResponse("error",
                            "The metadata session key id is not valid.", null, url));
        }

        String data = request != null ? request.getData() : null;
        MetadataSessionKey updated = metadataSessionKeyService.update(sessionKeyId, userId, data,
                request != null ? request.getModified() : null);

        MetadataSessionKeyDto.ModifiedResponse body = MetadataSessionKeyDto.ModifiedResponse.builder()
                .data(updated.getData())
                .modified(updated.getModified())
                .build();

        return ResponseEntity.ok(createResponse("success", "The operation was successful.", body, url));
    }

    /**
     * DELETE /metadata/session-key/{sessionKeyId}.json
     * Hard deletes the current user's session key. A foreign or missing id
     * yields 404 (existence is not disclosed). Response is the nullBody envelope.
     */
    @DeleteMapping("/session-key/{sessionKeyId}.json")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String sessionKeyId) {
        String url = "/metadata/session-key/" + sessionKeyId + ".json";
        String userId = getCurrentUserId();

        if (!isUuid(sessionKeyId)) {
            return ResponseEntity.status(400)
                    .body(createResponse("error",
                            "The metadata session key id is not valid.", null, url));
        }

        metadataSessionKeyService.delete(sessionKeyId, userId);

        // OpenAPI: DELETE responds with responses/nullBody (body is JSON null).
        return ResponseEntity.ok(ApiResponse.nullBody("success", "The operation was successful.", url));
    }

    /**
     * Map a session key entity to the metadataSessionKeyIndexAndView response
     * shape ({@code id, user_id, data, created, modified}). Transport only.
     */
    private MetadataSessionKeyDto.Response toResponseDto(MetadataSessionKey sessionKey) {
        return MetadataSessionKeyDto.Response.builder()
                .id(sessionKey.getId())
                .userId(sessionKey.getUserId())
                .data(sessionKey.getData())
                .created(sessionKey.getCreated())
                .modified(sessionKey.getModified())
                .build();
    }

    private boolean isUuid(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches();
    }

    private Map<String, Object> createResponse(String status, String message, Object body, String url) {
        // 共享信封工具：补 action(uuid) 等 spec required 字段，保留 200/400 code 语义。
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
