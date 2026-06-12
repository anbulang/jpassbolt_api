package com.jpassbolt.api.service;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Favorite;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.repository.FavoriteRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service for managing favorites (starred resources).
 * Ported from PHP FavoritesAddService / FavoritesDeleteService.
 *
 * Key semantics (do NOT "fix" them to match other controllers):
 * - Resource missing, soft-deleted, or no READ access all map to 404
 *   "The resource does not exist." (anti-enumeration, PHP HasResourceAccessRule),
 *   never 403.
 * - Deleting someone else's favorite is also 404 (PHP is_owner rule maps to
 *   NotFoundException), never 403.
 * - Favorites are hard-deleted; the table has no `deleted` column.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final ResourceRepository resourceRepository;
    private final PermissionRepository permissionRepository;

    /**
     * Mark a resource as favorite for the given user.
     * Validation order mirrors PHP FavoritesAddService:
     * resource exists → not soft-deleted → user has READ access → not already favorited.
     *
     * @param userId    the current user's id
     * @param foreignId the resource id (already validated as UUID by the controller)
     * @return the persisted Favorite
     */
    @Transactional
    public Favorite addFavorite(String userId, String foreignId) {
        // resource_exists + resource_is_not_soft_deleted + has_resource_access
        // all collapse into the same 404 (anti-enumeration).
        boolean resourceVisible = resourceRepository.findById(foreignId)
                .filter(r -> !r.getDeleted())
                .isPresent();
        if (!resourceVisible || !permissionRepository.userHasAccess(foreignId, userId, Permission.READ)) {
            throw new PassboltApiException(HttpStatus.NOT_FOUND, "The resource does not exist.");
        }

        // favorite_unique: application-layer check, the DB has no unique constraint.
        if (favoriteRepository.existsByUserIdAndForeignKey(userId, foreignId)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "This record is already marked as favorite.");
        }

        Favorite favorite = new Favorite(userId, foreignId, Favorite.FOREIGN_MODEL_RESOURCE);
        return favoriteRepository.save(favorite);
    }

    /**
     * Delete a favorite. Only the owner of the favorite row may delete it;
     * a non-owner gets the same 404 as a missing row (PHP is_owner rule).
     * This is a physical delete — favorites have no soft-delete column.
     *
     * @param favoriteId the favorite id (already validated as UUID by the controller)
     * @param userId     the current user's id
     */
    @Transactional
    public void deleteFavorite(String favoriteId, String userId) {
        Favorite favorite = favoriteRepository.findById(favoriteId)
                .filter(f -> userId.equals(f.getUserId()))
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "The favorite does not exist."));
        favoriteRepository.delete(favorite);
    }

    /**
     * Get all of a user's resource favorites keyed by resource id (foreign_key).
     * Supports the resources index filter[is-favorite] / contain[favorite] linkage.
     *
     * @param userId the user's id
     * @return map of resource id → Favorite
     */
    @Transactional(readOnly = true)
    public Map<String, Favorite> getFavoritesByResourceId(String userId) {
        return favoriteRepository
                .findByUserIdAndForeignModel(userId, Favorite.FOREIGN_MODEL_RESOURCE)
                .stream()
                .collect(Collectors.toMap(Favorite::getForeignKey, Function.identity(), (a, b) -> a));
    }
}
