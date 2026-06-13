package com.jpassbolt.api.service;

import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.FoldersRelation;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.repository.FoldersRelationRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Moves an item (folder or resource) inside the per-user folder trees.
 * Port of PHP FoldersRelationsMoveItemInUserTreeService +
 * FoldersRelationsMoveController validations.
 *
 * <p>
 * Error mapping follows the reference implementation: invalid input and
 * insufficient move permissions are validation errors (400,
 * CustomValidationException in PHP); an item missing from the operator's tree
 * is a 404.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FoldersRelationsMoveService {

    /** Defensive bound when walking ancestor chains (corrupt data guard). */
    private static final int MAX_TREE_DEPTH = 128;

    private final FoldersRelationRepository foldersRelationRepository;
    private final PermissionRepository permissionRepository;

    /**
     * Move an item in the user trees.
     *
     * @param foreignModelParam path parameter, "folder" or "resource"
     *                          (case-insensitive)
     * @param foreignId         the item id to move
     * @param folderParentId    the destination folder id, null = root
     * @param userId            the operator's user id
     * @throws PassboltApiException 400 on validation/permission failure, 404
     *                              when the item is not in the operator's tree
     */
    @Transactional
    public void move(String foreignModelParam, String foreignId, String folderParentId, String userId) {
        String foreignModel = normalizeForeignModel(foreignModelParam);
        if (!isUuid(foreignId)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The object identifier should be a valid UUID.");
        }
        if (folderParentId != null && !isUuid(folderParentId)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The folder parent identifier should be a valid UUID.");
        }

        // The item must be in the operator's tree.
        FoldersRelation relation = foldersRelationRepository
                .findByUserIdAndForeignIdAndForeignModel(userId, foreignId, foreignModel)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND,
                        "The object to move does not exist."));

        // The destination folder must be in the operator's tree.
        if (folderParentId != null
                && foldersRelationRepository.findByUserIdAndForeignId(userId, folderParentId).isEmpty()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "Could not validate move data. The folder parent does not exist.");
        }

        String originalFolderParentId = relation.getFolderParentId();
        assertUserCanMoveOut(foreignModel, foreignId, originalFolderParentId, userId);
        assertUserCanMoveIn(foreignModel, foreignId, folderParentId, userId);
        if (FoldersRelation.FOREIGN_MODEL_FOLDER.equals(foreignModel) && folderParentId != null) {
            assertCycleFree(userId, foreignId, folderParentId);
        }

        performMove(foreignId, originalFolderParentId, folderParentId);
    }

    /**
     * PHP assertUserCanMoveOutOfFolder:
     * - always allowed from the root,
     * - always allowed out of a personal folder,
     * - otherwise needs UPDATE on the original parent AND on the item.
     */
    private void assertUserCanMoveOut(
            String foreignModel, String foreignId, String originalFolderParentId, String userId) {
        if (originalFolderParentId == null) {
            return;
        }
        if (isPersonal(originalFolderParentId)) {
            return;
        }
        // Group-inclusive checks (PHP PermissionsTable::hasAccess walks
        // groups_users) — direct User rows alone would void group-inherited
        // folder permissions.
        boolean canMoveOut = permissionRepository.hasAccessIncludingGroups(
                FolderService.FOLDER_ACO, originalFolderParentId, userId, Permission.UPDATE);
        if (!canMoveOut) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "You are not allowed to move this item out of its parent folder.");
        }
        boolean canMoveItem = permissionRepository.hasAccessIncludingGroups(
                foreignModel, foreignId, userId, Permission.UPDATE);
        if (!canMoveItem) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "You are not allowed to move this item.");
        }
    }

    /**
     * PHP assertUserCanMoveInFolder:
     * - always allowed to the root,
     * - always allowed into a personal folder,
     * - otherwise needs UPDATE on the destination folder AND on the item.
     */
    private void assertUserCanMoveIn(
            String foreignModel, String foreignId, String folderParentId, String userId) {
        if (folderParentId == null) {
            return;
        }
        if (isPersonal(folderParentId)) {
            return;
        }
        boolean canMoveIn = permissionRepository.hasAccessIncludingGroups(
                FolderService.FOLDER_ACO, folderParentId, userId, Permission.UPDATE);
        if (!canMoveIn) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "You are not allowed to create content into the parent folder.");
        }
        boolean canMoveItem = permissionRepository.hasAccessIncludingGroups(
                foreignModel, foreignId, userId, Permission.UPDATE);
        if (!canMoveItem) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "You are not allowed to move an item in read only into the parent folder.");
        }
    }

    /**
     * Reject moving a folder into itself or one of its descendants in the
     * operator's tree (PHP assertCycleFree, checked up-front here).
     */
    private void assertCycleFree(String userId, String movedFolderId, String destFolderId) {
        String cursor = destFolderId;
        int depth = 0;
        while (cursor != null && depth++ < MAX_TREE_DEPTH) {
            if (cursor.equals(movedFolderId)) {
                throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                        "The folder cannot be moved into one of its descendants.");
            }
            cursor = foldersRelationRepository.findByUserIdAndForeignId(userId, cursor)
                    .map(FoldersRelation::getFolderParentId)
                    .orElse(null);
        }
    }

    /**
     * PHP performMove:
     * - to root: move for every user sharing the operator's representation;
     * - else: move for every user seeing both item and destination, and move
     * conflicting representations to the root first.
     */
    private void performMove(String foreignId, String originalParentId, String destParentId) {
        if (destParentId == null) {
            if (originalParentId != null) {
                foldersRelationRepository.moveItemFromParent(foreignId, originalParentId, null);
            }
            return;
        }
        List<String> usersSeeingItem = foldersRelationRepository.findUserIdsByForeignId(foreignId);
        List<String> usersSeeingDest = foldersRelationRepository.findUserIdsByForeignId(destParentId);
        List<String> users = usersSeeingItem.stream()
                .distinct()
                .filter(usersSeeingDest::contains)
                .collect(Collectors.toList());
        if (users.isEmpty()) {
            return;
        }
        List<String> conflictedParents = foldersRelationRepository.findParentIdsInUsersTrees(users, foreignId);
        if (!conflictedParents.isEmpty()) {
            foldersRelationRepository.moveItemFromParents(foreignId, conflictedParents, null);
        }
        foldersRelationRepository.moveItemForUsers(foreignId, users, destParentId);
    }

    private String normalizeForeignModel(String foreignModelParam) {
        if (foreignModelParam != null) {
            String normalized = foreignModelParam.toLowerCase();
            if ("folder".equals(normalized)) {
                return FoldersRelation.FOREIGN_MODEL_FOLDER;
            }
            if ("resource".equals(normalized)) {
                return FoldersRelation.FOREIGN_MODEL_RESOURCE;
            }
        }
        throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                "The object type should be one of the following: Folder, Resource.");
    }

    private boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            java.util.UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isPersonal(String foreignId) {
        return foldersRelationRepository.countByForeignId(foreignId) == 1;
    }
}
