package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for Comment entity operations.
 * Comments are hard-deleted (no soft-delete column on the comments table),
 * so no deleted-filtering finder is needed.
 */
@Repository
public interface CommentRepository extends JpaRepository<Comment, String> {

        /**
         * Find all comments for a foreign model instance, most recently
         * modified first (matches the official Passbolt ordering).
         */
        List<Comment> findByForeignModelAndForeignKeyOrderByModifiedDesc(String foreignModel, String foreignKey);

        /**
         * Find all direct replies to a comment.
         */
        List<Comment> findByParentId(String parentId);

        /**
         * Find all comments attached to a resource, most recently modified first.
         */
        default List<Comment> findByResourceId(String resourceId) {
                return findByForeignModelAndForeignKeyOrderByModifiedDesc(Comment.RESOURCE_FOREIGN_MODEL, resourceId);
        }
}
