package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.Favorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Favorite entity operations.
 *
 * Note: favorites are HARD deleted (the table has no `deleted` column),
 * so there is intentionally no findByDeletedFalse() here.
 */
@Repository
public interface FavoriteRepository extends JpaRepository<Favorite, String> {

    /**
     * Check whether the user already favorited the given resource.
     * This is the application-layer uniqueness rule (the DB only has a
     * plain index on (foreign_key, user_id), no unique constraint),
     * mirroring the CakePHP `favorite_unique` isUnique rule.
     */
    boolean existsByUserIdAndForeignKey(String userId, String foreignKey);

    /**
     * Find a user's favorite for a specific resource
     * (used by share-revocation cleanup, see PHP ResourcesTable::deleteLostAccessFavorites).
     */
    Optional<Favorite> findByUserIdAndForeignKey(String userId, String foreignKey);

    /**
     * Find all favorites of a user for a given foreign model
     * (supports the resources index filter[is-favorite] / contain[favorite] linkage).
     */
    List<Favorite> findByUserIdAndForeignModel(String userId, String foreignModel);

    /**
     * Physically delete all favorites pointing at the given resource.
     * Used when a resource is soft-deleted (PHP ResourcesTable::softDelete).
     * Must be invoked within an active transaction (caller is @Transactional).
     */
    void deleteByForeignKey(String foreignKey);
}
