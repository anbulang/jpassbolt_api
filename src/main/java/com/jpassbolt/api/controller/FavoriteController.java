package com.jpassbolt.api.controller;

import com.jpassbolt.api.dto.FavoriteDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Favorite;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.UserRepository;
import com.jpassbolt.api.service.FavoriteService;
import com.jpassbolt.api.util.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * FavoriteController provides REST endpoints for marking/unmarking resources as favorite.
 *
 * Dual registration of singular and plural base paths is mandatory:
 * - the OpenAPI spec (and contract tests) use the SINGULAR /favorite/... paths;
 * - the PHP routes (config/routes.php) and the official browser plugin use the
 *   PLURAL /favorites/... paths.
 */
@Slf4j
@RestController
@RequestMapping({ "/favorites", "/favorite" })
@RequiredArgsConstructor
public class FavoriteController {

    /** Strict UUID check (java.util.UUID.fromString is too lenient). */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Only path segment allowed by the PHP routes: lowercase "resource". */
    private static final String FOREIGN_MODEL_RESOURCE_PATH = "resource";

    private final FavoriteService favoriteService;
    private final UserRepository userRepository;

    /**
     * POST /favorites/{foreignModel}/{foreignId}.json (and singular /favorite/...)
     * Marks a resource as favorite for the current user.
     * No request body (the plugin may send an empty body with a JSON Content-Type,
     * so this method must NOT declare @RequestBody).
     */
    @PostMapping("/{foreignModel}/{foreignId}.json")
    public ResponseEntity<Map<String, Object>> addFavorite(
            @PathVariable String foreignModel,
            @PathVariable String foreignId) {
        String userId = getCurrentUserId();

        // PHP only registers the /favorites/resource/{foreignId} route; any other
        // foreignModel value never matches a route and yields a 404.
        if (!FOREIGN_MODEL_RESOURCE_PATH.equals(foreignModel)) {
            throw new PassboltApiException(HttpStatus.NOT_FOUND, "The resource does not exist.");
        }

        if (!UUID_PATTERN.matcher(foreignId).matches()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The resource identifier should be a valid UUID.");
        }

        Favorite favorite = favoriteService.addFavorite(userId, foreignId);
        return ResponseEntity.ok(createResponse("success", "The resource was marked as favorite.",
                toResponseDto(favorite), "/favorites/resource/" + foreignId + ".json"));
    }

    /**
     * DELETE /favorites/{favoriteId}.json (and singular /favorite/...)
     * Deletes a favorite. Only the favorite's owner may delete it (non-owner → 404).
     * Successful response body is literal JSON null (spec nullBody).
     */
    @DeleteMapping("/{favoriteId}.json")
    public ResponseEntity<Map<String, Object>> deleteFavorite(@PathVariable String favoriteId) {
        String userId = getCurrentUserId();

        if (!UUID_PATTERN.matcher(favoriteId).matches()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST, "The favorite id is not valid.");
        }

        favoriteService.deleteFavorite(favoriteId, userId);
        return ResponseEntity.ok(createResponse("success", "The favorite was deleted.",
                null, "/favorites/" + favoriteId + ".json"));
    }

    private FavoriteDto.Response toResponseDto(Favorite favorite) {
        return FavoriteDto.Response.builder()
                .id(favorite.getId())
                .userId(favorite.getUserId())
                .foreignKey(favorite.getForeignKey())
                .foreignModel(favorite.getForeignModel())
                .created(favorite.getCreated())
                .modified(favorite.getModified())
                .build();
    }

    /**
     * Local copy of the standard response envelope with ONE deliberate deviation
     * from ResourceController.createResponse: when body is null the literal JSON
     * null is emitted instead of an empty object. The OpenAPI nullBody response
     * (DELETE favorite) requires "body": null, and PHP success() without a result
     * also outputs "body": null — plugin compatibility depends on it.
     */
    private Map<String, Object> createResponse(String status, String message, Object body, String url) {
        // 迁移到共享信封工具：补 action(uuid) 等 spec required 字段，保留 body 透传（含 null）特例。
        return ApiResponse.passthrough(status, message, body, url);
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
