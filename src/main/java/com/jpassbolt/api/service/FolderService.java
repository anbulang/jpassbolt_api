package com.jpassbolt.api.service;

import com.jpassbolt.api.dto.FolderDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.Folder;
import com.jpassbolt.api.model.FoldersRelation;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.repository.FolderRepository;
import com.jpassbolt.api.repository.FoldersRelationRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for managing folders (PHP plugin Passbolt/Folders, v4 behaviour).
 *
 * <p>
 * Folders are ACOs (permissions.aco = "Folder"). The hierarchy lives in
 * folders_relations, maintained per user. Folders are HARD deleted (no deleted
 * column in the official schema).
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FolderService {

    /** ACO type for folders (PHP PermissionsTable::FOLDER_ACO). */
    public static final String FOLDER_ACO = "Folder";

    /** Max folder name length (folders.name varchar(256)). */
    private static final int NAME_MAX_LENGTH = 256;

    /** Item-specific 400 message for an undecryptable folder metadata blob (PHP verbatim, note "cannot"). */
    private static final String FOLDER_METADATA_UNDECRYPTABLE = "The folder metadata provided cannot be decrypted.";

    /** Item-specific 400 message for a personal (user_key) folder that is shared (PHP verbatim). */
    private static final String FOLDER_PERSONAL_SHARED =
            "A folder of type personal cannot be shared with other users or a group.";

    private final FolderRepository folderRepository;
    private final FoldersRelationRepository foldersRelationRepository;
    private final PermissionRepository permissionRepository;
    private final ResourceService resourceService;
    private final MetadataValidationSupport metadataValidationSupport;
    private final MetadataTypesSettingsService metadataTypesSettingsService;

    /**
     * Get all folders the user has at least READ access to (aco = "Folder"),
     * directly or through one of their groups (PHP findAllByAro with
     * checkGroupsUsers=true).
     *
     * @param userId the requesting user's ID
     * @return list of accessible folders
     */
    @Transactional(readOnly = true)
    public List<Folder> getAccessibleFolders(String userId) {
        List<String> folderIds = permissionRepository
                .findAccessibleAcoIdsIncludingGroups(FOLDER_ACO, userId, Permission.READ);
        if (folderIds.isEmpty()) {
            return List.of();
        }
        return folderRepository.findAllById(folderIds);
    }

    /**
     * Get a folder by ID.
     *
     * @param id the folder ID
     * @return an Optional containing the folder if found
     */
    @Transactional(readOnly = true)
    public Optional<Folder> getFolderById(String id) {
        return folderRepository.findById(id);
    }

    /**
     * Check if a user has at least the given permission level on a folder,
     * directly or through one of their groups (PHP PermissionsTable::hasAccess
     * → findHighestByAcoAndAro with checkGroupsUsers=true).
     *
     * @param folderId the folder ID
     * @param userId   the user ID
     * @param minType  minimum permission type (READ/UPDATE/OWNER)
     * @return true when access is granted
     */
    @Transactional(readOnly = true)
    public boolean userHasFolderAccess(String folderId, String userId, int minType) {
        return permissionRepository.hasAccessIncludingGroups(FOLDER_ACO, folderId, userId, minType);
    }

    /**
     * The parent of a folder in the given user's tree (null = root or no
     * relation row).
     *
     * @param folderId the folder ID
     * @param userId   the user ID
     * @return the parent folder id or null
     */
    @Transactional(readOnly = true)
    public String getFolderParentIdForUser(String folderId, String userId) {
        return foldersRelationRepository.findByUserIdAndForeignId(userId, folderId)
                .map(FoldersRelation::getFolderParentId)
                .orElse(null);
    }

    /**
     * An item is personal when exactly one user sees it
     * (PHP FoldersRelationsTable::isItemPersonal).
     *
     * @param foreignId the item ID
     * @return true when personal
     */
    @Transactional(readOnly = true)
    public boolean isPersonal(String foreignId) {
        return foldersRelationRepository.countByForeignId(foreignId) == 1;
    }

    /**
     * Create a folder: folder row + OWNER permission (aco = "Folder") + relation
     * row in the creator's tree, in one transaction
     * (PHP FoldersCreateService::create).
     *
     * @param request the create request (name + optional folder_parent_id)
     * @param userId  the creator's user ID
     * @return the created folder
     * @throws PassboltApiException 400 on validation failure (including a parent
     *                              folder the user cannot write into, per PHP
     *                              ValidationException semantics)
     */
    @Transactional
    public Folder createFolder(FolderDto.CreateRequest request, String userId) {
        boolean isV5 = request.getMetadata() != null;

        Folder folder = new Folder();
        if (isV5) {
            // v5 metadata path. Mixed-payload + partial-v5 + settings gate + the
            // parse-only/key-type/expiry/settings asserts ported from PHP. The
            // metadata blob is stored VERBATIM (zero-knowledge, never decrypted)
            // and the v4 plaintext name column is left null.
            applyV5Metadata(request.getName(), request.getMetadata(),
                    request.getMetadataKeyId(), request.getMetadataKeyType(), folder, true);
        } else {
            // v4 plaintext path — UNCHANGED.
            validateName(request.getName());
            folder.setName(request.getName());
        }

        String folderParentId = request.getFolderParentId();
        if (folderParentId != null) {
            validateParentFolder(folderParentId, userId);
        }

        folder.setCreatedBy(userId);
        folder.setModifiedBy(userId);
        Folder saved = folderRepository.save(folder);

        Permission ownerPermission = new Permission();
        ownerPermission.setAco(FOLDER_ACO);
        ownerPermission.setAcoForeignKey(saved.getId());
        ownerPermission.setAro(Permission.USER_ARO);
        ownerPermission.setAroForeignKey(userId);
        ownerPermission.setType(Permission.OWNER);
        permissionRepository.save(ownerPermission);

        FoldersRelation relation = new FoldersRelation();
        relation.setForeignModel(FoldersRelation.FOREIGN_MODEL_FOLDER);
        relation.setForeignId(saved.getId());
        relation.setUserId(userId);
        relation.setFolderParentId(folderParentId);
        foldersRelationRepository.save(relation);

        return saved;
    }

    /**
     * Rename a folder (PHP FoldersUpdateService::update, v4: name only).
     *
     * @param request the update request (name)
     * @param userId  the requesting user's ID
     * @return the updated folder
     * @throws PassboltApiException 404 when the folder is not visible to the
     *                              user, 403 when visible but not writable, 400
     *                              on validation failure
     */
    @Transactional
    public Folder updateFolder(String id, FolderDto.UpdateRequest request, String userId) {
        // PHP: no permission at all -> NotFound; below UPDATE -> Forbidden.
        if (!userHasFolderAccess(id, userId, Permission.READ)) {
            throw new PassboltApiException(HttpStatus.NOT_FOUND, "The folder does not exist.");
        }
        if (!userHasFolderAccess(id, userId, Permission.UPDATE)) {
            throw new PassboltApiException(HttpStatus.FORBIDDEN, "You are not allowed to update this folder.");
        }
        Folder folder = folderRepository.findById(id)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND, "The folder does not exist."));

        if (request.getMetadata() != null) {
            // v5 metadata path. Same structural/parse/key asserts as create, plus
            // the update-only IsMetadataKeyTypeSharedOnSharedItemRule (a personal
            // user_key folder may not already be shared). Settings creation-gate
            // is NOT applied on update (PHP only gates creation).
            applyV5Metadata(request.getName(), request.getMetadata(),
                    request.getMetadataKeyId(), request.getMetadataKeyType(), folder, false);
            metadataValidationSupport.assertKeyTypeSharedOnSharedItem(
                    request.getMetadataKeyType(), id, FOLDER_PERSONAL_SHARED);
        } else {
            // v4 plaintext path — UNCHANGED.
            validateName(request.getName());
            folder.setName(request.getName());
        }
        folder.setModifiedBy(userId);
        return folderRepository.save(folder);
    }

    /**
     * Apply the v5 metadata trio to a folder (create + update share this).
     *
     * <p>
     * Structural rules ported from PHP {@code MetadataFolderDto::validate} /
     * {@code FoldersTable::validationV5}: a v5 payload may not also carry the v4
     * {@code name} (mixed payload) and must carry all three metadata fields
     * (partial-v5). On create only, creation is gated by the
     * {@code metadataTypes} org setting ({@code allow_creation_of_v5_folders});
     * modeled as 400 for cross-endpoint consistency. Update is NOT
     * creation-gated (PHP only gates creation). The blob itself is validated
     * PARSE-ONLY (zero-knowledge, never decrypted) and stored verbatim —
     * {@code name} stays null.
     * </p>
     *
     * @param applyCreationGate {@code true} on create (apply the v5-folder
     *                          creation settings gate), {@code false} on update
     */
    private void applyV5Metadata(String name, String metadata, String metadataKeyId,
            String metadataKeyType, Folder folder, boolean applyCreationGate) {
        // Structural guards in PHP order (MetadataFolderDto::validate):
        // (1) partial-v5 (all three v5 fields required) BEFORE (2) mixed-payload
        // (no v4 fields). Shared with ResourceService for identical rules/order;
        // folders only have the v4 name field (username/uri/description = null).
        metadataValidationSupport.assertV5FieldsComplete(metadata, metadataKeyId, metadataKeyType);
        metadataValidationSupport.assertNoV4Fields(name, null, null, null);
        // Settings creation-gate (allow_creation_of_v5_folders) — create only.
        // Update is NOT creation-gated (PHP only gates creation).
        if (applyCreationGate && !metadataTypesSettingsService.isV5FolderCreationAllowed()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The settings selected by your administrator prevent from creating a V5 folder.");
        }
        metadataValidationSupport.assertParsableMetadataMessage(metadata, FOLDER_METADATA_UNDECRYPTABLE);
        metadataValidationSupport.assertMetadataKeyType(metadataKeyType);
        metadataValidationSupport.assertMetadataKeyTypeAllowedBySettings(metadataKeyType);
        metadataValidationSupport.assertMetadataKeyIdNotExpired(metadataKeyType, metadataKeyId);

        folder.setMetadata(metadata);
        folder.setMetadataKeyId(metadataKeyId);
        folder.setMetadataKeyType(metadataKeyType);
        // v5: clear the v4 plaintext name column. On create it is already null;
        // on update of a v4 row this nulls the stale plaintext name, matching PHP
        // FoldersUpdateService::patchEntity ($data['name'] = null). Without this a
        // v5 update would leave a mixed/corrupt row leaking the old plaintext name.
        folder.setName(null);
    }

    /**
     * Delete a folder (PHP FoldersDeleteService::delete). Hard delete of the
     * folder row, its relations and its permissions. Content handling:
     * cascade=false moves children to the root; cascade=true deletes children
     * the user can write to (folders recursively, resources soft-deleted) and
     * moves the rest to the root.
     *
     * @param id      the folder ID
     * @param cascade delete the folder content too
     * @param userId  the requesting user's ID
     * @throws PassboltApiException 404 when the folder does not exist, 403 when
     *                              the user has no UPDATE permission
     */
    @Transactional
    public void deleteFolder(String id, boolean cascade, String userId) {
        Folder folder = folderRepository.findById(id)
                .orElseThrow(() -> new PassboltApiException(HttpStatus.NOT_FOUND, "The folder does not exist."));
        if (!userHasFolderAccess(id, userId, Permission.UPDATE)) {
            throw new PassboltApiException(HttpStatus.FORBIDDEN, "You are not allowed to delete this folder.");
        }
        deleteFolderNode(folder, cascade, userId);
    }

    private void deleteFolderNode(Folder folder, boolean cascade, String userId) {
        if (cascade) {
            deleteChildrenOrMoveToRoot(folder, userId);
        } else {
            foldersRelationRepository.moveChildrenToRoot(folder.getId());
        }
        folderRepository.deleteById(folder.getId());
        foldersRelationRepository.deleteByForeignId(folder.getId());
        permissionRepository.deleteByAcoForeignKey(folder.getId());
    }

    private void deleteChildrenOrMoveToRoot(Folder folder, String userId) {
        // The same child appears once per user seeing it: dedupe by foreign_id.
        Map<String, String> children = new LinkedHashMap<>(); // foreignId -> foreignModel
        for (FoldersRelation rel : foldersRelationRepository.findByFolderParentId(folder.getId())) {
            children.putIfAbsent(rel.getForeignId(), rel.getForeignModel());
        }
        for (Map.Entry<String, String> child : children.entrySet()) {
            String childId = child.getKey();
            String childModel = child.getValue();
            boolean canDelete = permissionRepository.hasAccessIncludingGroups(
                    childModel, childId, userId, Permission.UPDATE);
            if (!canDelete) {
                // Not writable: move the child back to the root instead.
                foldersRelationRepository.moveItemFromParent(childId, folder.getId(), null);
                continue;
            }
            if (FoldersRelation.FOREIGN_MODEL_FOLDER.equals(childModel)) {
                folderRepository.findById(childId)
                        .ifPresent(childFolder -> deleteFolderNode(childFolder, true, userId));
            } else if (FoldersRelation.FOREIGN_MODEL_RESOURCE.equals(childModel)) {
                // Soft delete (reuses the favorites cascade) + drop tree rows.
                resourceService.deleteResource(childId, userId);
                foldersRelationRepository.deleteByForeignId(childId);
            }
        }
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "Could not validate folder data. The name is required.");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "Could not validate folder data. The name length should be maximum 256 characters.");
        }
    }

    private void validateParentFolder(String folderParentId, String userId) {
        if (!isUuid(folderParentId)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The folder parent id should be a valid UUID.");
        }
        if (!folderRepository.existsById(folderParentId)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "The folder parent must exist.");
        }
        // PHP: a parent the user cannot write into is a validation error (400).
        if (!userHasFolderAccess(folderParentId, userId, Permission.UPDATE)) {
            throw new PassboltApiException(HttpStatus.BAD_REQUEST,
                    "You are not allowed to create content into the parent folder.");
        }
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
}
