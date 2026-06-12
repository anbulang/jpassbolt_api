package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Favorite Entity - Represents a user's favorite (starred) resource.
 *
 * Maps the Passbolt `favorites` table column by column (MySQL ddl-auto=validate):
 * - id char(36) PK + created/modified datetime: provided by {@link BaseEntity}
 * - user_id char(36) NULLABLE (business code always fills the current user)
 * - foreign_key char(36) NOT NULL (the favorited resource id)
 * - foreign_model varchar(36) NOT NULL (always "Resource", capitalized)
 *
 * Important schema facts (do NOT change):
 * - No `deleted` column: favorites are HARD deleted (unlike Resource/User soft delete).
 * - No `created_by`/`modified_by` audit columns.
 * - Only a plain index KEY(foreign_key, user_id) — uniqueness of (user_id, foreign_key)
 *   is enforced at the application layer (FavoriteService), mirroring the CakePHP
 *   `favorite_unique` isUnique rule. Do not add a DB unique constraint.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@Entity
@Table(name = "favorites")
public class Favorite extends BaseEntity {

    /**
     * The only foreign model supported by Passbolt favorites.
     * Note: stored/serialized capitalized ("Resource"), while the URL path segment
     * uses lowercase ("resource") — see PHP FavoritesTable::ALLOWED_FOREIGN_MODELS.
     */
    public static final String FOREIGN_MODEL_RESOURCE = "Resource";

    @Column(name = "user_id", length = 36, columnDefinition = "char(36)")
    private String userId;

    @Column(name = "foreign_key", nullable = false, length = 36, columnDefinition = "char(36)")
    private String foreignKey;

    @Column(name = "foreign_model", nullable = false, length = 36)
    private String foreignModel;

    /**
     * Convenience constructor for creating a new favorite.
     *
     * @param userId       the owning user's id
     * @param foreignKey   the favorited resource id
     * @param foreignModel the foreign model name (use {@link #FOREIGN_MODEL_RESOURCE})
     */
    public Favorite(String userId, String foreignKey, String foreignModel) {
        this.userId = userId;
        this.foreignKey = foreignKey;
        this.foreignModel = foreignModel;
    }
}
