package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.FoldersRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for FoldersRelation entity operations (per-user folder trees).
 *
 * <p>
 * Bulk {@code @Modifying} updates mirror the PHP
 * FoldersRelationsTable::moveItemFrom / moveItemFor / updateAll helpers; like
 * CakePHP's updateAll they bypass entity callbacks (the {@code modified}
 * timestamp is not touched), which matches the reference behaviour.
 * </p>
 */
@Repository
public interface FoldersRelationRepository extends JpaRepository<FoldersRelation, String> {

    /** The item's relation row in one user's tree (unique per user+item). */
    Optional<FoldersRelation> findByUserIdAndForeignId(String userId, String foreignId);

    /** Same as above, additionally constrained by the foreign model. */
    Optional<FoldersRelation> findByUserIdAndForeignIdAndForeignModel(
            String userId, String foreignId, String foreignModel);

    /** All relation rows of an item across every user's tree. */
    List<FoldersRelation> findByForeignId(String foreignId);

    /** Direct children rows of a folder across every user's tree. */
    List<FoldersRelation> findByFolderParentId(String folderParentId);

    /** Direct children of a folder in one user's tree. */
    List<FoldersRelation> findByUserIdAndFolderParentId(String userId, String folderParentId);

    /**
     * Number of users seeing the item; == 1 means the item is "personal"
     * (PHP FoldersRelationsTable::isItemPersonal).
     */
    long countByForeignId(String foreignId);

    /**
     * Hard-delete every relation row of an item (item deletion cascade).
     * Caller must be @Transactional.
     */
    void deleteByForeignId(String foreignId);

    /**
     * Remove an item from one user's tree (access revocation cascade, PHP
     * FoldersRelationsRemoveItemFromUserTreeService). Caller must be
     * @Transactional.
     */
    void deleteByUserIdAndForeignId(String userId, String foreignId);

    /**
     * Move every direct child of a folder to the root in ONE user's tree
     * (used when the folder disappears from that user's tree after a share
     * revocation).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE FoldersRelation fr SET fr.folderParentId = NULL " +
            "WHERE fr.userId = :userId AND fr.folderParentId = :folderParentId")
    int moveUserChildrenToRoot(
            @Param("userId") String userId,
            @Param("folderParentId") String folderParentId);

    /** Users seeing the given item (one tree row each). */
    @Query("SELECT fr.userId FROM FoldersRelation fr WHERE fr.foreignId = :foreignId")
    List<String> findUserIdsByForeignId(@Param("foreignId") String foreignId);

    /**
     * Current non-root parents of an item in the given users' trees
     * (PHP getItemFoldersParentIdsInUsersTrees with excludeRoot=true).
     */
    @Query("SELECT DISTINCT fr.folderParentId FROM FoldersRelation fr " +
            "WHERE fr.foreignId = :foreignId AND fr.userId IN :userIds " +
            "AND fr.folderParentId IS NOT NULL")
    List<String> findParentIdsInUsersTrees(
            @Param("userIds") List<String> userIds,
            @Param("foreignId") String foreignId);

    /**
     * Move an item out of one parent (all users sharing that representation) —
     * PHP moveItemFrom with a single source folder.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE FoldersRelation fr SET fr.folderParentId = :newParentId " +
            "WHERE fr.foreignId = :foreignId AND fr.folderParentId = :fromParentId")
    int moveItemFromParent(
            @Param("foreignId") String foreignId,
            @Param("fromParentId") String fromParentId,
            @Param("newParentId") String newParentId);

    /**
     * Move an item out of several parents at once (conflict resolution to
     * root) — PHP moveItemFrom with a list of source folders.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE FoldersRelation fr SET fr.folderParentId = :newParentId " +
            "WHERE fr.foreignId = :foreignId AND fr.folderParentId IN :fromParentIds")
    int moveItemFromParents(
            @Param("foreignId") String foreignId,
            @Param("fromParentIds") List<String> fromParentIds,
            @Param("newParentId") String newParentId);

    /**
     * Move an item to a target location in the given users' trees —
     * PHP moveItemFor.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE FoldersRelation fr SET fr.folderParentId = :newParentId " +
            "WHERE fr.foreignId = :foreignId AND fr.userId IN :userIds")
    int moveItemForUsers(
            @Param("foreignId") String foreignId,
            @Param("userIds") List<String> userIds,
            @Param("newParentId") String newParentId);

    /**
     * Move every direct child of a folder to the root, in all users' trees —
     * PHP FoldersDeleteService::moveFolderContentToRoot.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE FoldersRelation fr SET fr.folderParentId = NULL " +
            "WHERE fr.folderParentId = :folderParentId")
    int moveChildrenToRoot(@Param("folderParentId") String folderParentId);
}
