package com.jpassbolt.api.service;

import com.jpassbolt.api.dto.ResourceDto;
import com.jpassbolt.api.model.FoldersRelation;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.repository.FavoriteRepository;
import com.jpassbolt.api.repository.FolderRepository;
import com.jpassbolt.api.repository.FoldersRelationRepository;
import com.jpassbolt.api.repository.PermissionRepository;
import com.jpassbolt.api.repository.ResourceRepository;
import com.jpassbolt.api.repository.SecretRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for managing password resources and their associated secrets.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceService {

    private final ResourceRepository resourceRepository;
    private final SecretRepository secretRepository;
    private final PermissionRepository permissionRepository;
    private final FavoriteRepository favoriteRepository;
    private final FoldersRelationRepository foldersRelationRepository;
    private final FolderRepository folderRepository;
    private final MetadataValidationSupport metadataValidationSupport;
    private final MetadataTypesSettingsService metadataTypesSettingsService;

    /** PHP v5 resource undecryptable-metadata message (note: "can not"). */
    private static final String RESOURCE_METADATA_NOT_DECRYPTABLE =
            "The resource metadata provided can not be decrypted.";

    /** PHP v5 personal-resource-shared message. */
    private static final String RESOURCE_PERSONAL_SHARED =
            "A resource of type personal cannot be shared with other users or a group.";

    /**
     * Get all non-deleted resources that the user has at least READ access to.
     *
     * @param userId the requesting user's ID
     * @return list of accessible resources
     */
    @Transactional(readOnly = true)
    public List<Resource> getAccessibleResources(String userId) {
        List<String> resourceIds = permissionRepository.findAccessibleResourceIdsIncludingGroups(userId, Permission.READ);
        if (resourceIds.isEmpty()) {
            return List.of();
        }
        return resourceRepository.findAllById(resourceIds).stream()
                .filter(r -> !r.getDeleted())
                .collect(Collectors.toList());
    }

    /**
     * Get all non-deleted resources (no permission check — for backward compat /
     * admin).
     *
     * @return list of all non-deleted resources
     */
    @Transactional(readOnly = true)
    public List<Resource> getAllResources() {
        return resourceRepository.findByDeletedFalse();
    }

    /**
     * Get a resource by ID.
     *
     * @param id the resource ID
     * @return an Optional containing the resource if found and not deleted
     */
    @Transactional(readOnly = true)
    public Optional<Resource> getResourceById(String id) {
        return resourceRepository.findById(id)
                .filter(r -> !r.getDeleted());
    }

    /**
     * Create a new resource with its associated secrets.
     * Automatically creates OWNER permission for the creator.
     *
     * @param request the create request DTO
     * @param userId  the creator's user ID
     * @return the created resource
     */
    @Transactional
    public Resource createResource(ResourceDto.CreateRequest request, String userId) {
        // Validate the destination folder up-front (PHP Folders plugin
        // ResourcesAfterCreateService::validateParentFolder): a non-null
        // folder_parent_id must be a valid UUID, point to an existing folder, and
        // be writable (UPDATE) by the creator — otherwise 400, exactly as the
        // reference. A throw here rolls back the whole create transaction.
        if (request.getFolderParentId() != null) {
            validateFolderParent(request.getFolderParentId(), userId);
        }

        Resource resource = new Resource();
        if (request.getMetadata() != null) {
            // v5 metadata shape: validate (parse-only) + settings/key-type rules,
            // persist the metadata trio + resource_type_id, leave the v4 plaintext
            // columns (name/username/uri/description) null. Mirrors the PHP fusion:
            // the IsMetadataKeyTypeSharedOnSharedItemRule is an UPDATE-only rule
            // (the item has no permissions yet at create), so it is NOT run here.
            applyV5MetadataOnCreate(resource, request);
        } else {
            // v4 plaintext shape — EXACT existing behaviour, byte-for-byte.
            resource.setName(request.getName());
            resource.setUsername(request.getUsername());
            resource.setUri(request.getUri());
            resource.setDescription(request.getDescription());
            resource.setResourceTypeId(request.getResourceTypeId());
        }
        resource.setCreatedBy(userId);
        resource.setModifiedBy(userId);
        resource.setDeleted(false);

        Resource savedResource = resourceRepository.save(resource);

        // Auto-create OWNER permission for the creator
        Permission ownerPermission = new Permission();
        ownerPermission.setAco(Permission.RESOURCE_ACO);
        ownerPermission.setAcoForeignKey(savedResource.getId());
        ownerPermission.setAro(Permission.USER_ARO);
        ownerPermission.setAroForeignKey(userId);
        ownerPermission.setType(Permission.OWNER);
        permissionRepository.save(ownerPermission);

        // Create secrets if provided
        if (request.getSecrets() != null) {
            for (ResourceDto.CreateRequest.SecretData secretData : request.getSecrets()) {
                Secret secret = new Secret();
                secret.setResourceId(savedResource.getId());
                secret.setUserId(secretData.getUserId() != null ? secretData.getUserId() : userId);
                secret.setData(secretData.getData());
                secretRepository.save(secret);
            }
        }

        // Folder-tree integration (PHP Folders plugin ResourcesEventListener
        // afterResourceAdded): the resource enters the creator's tree, under
        // the requested folder_parent_id or at the root. Without this row the
        // move endpoint would 404 on freshly created resources.
        FoldersRelation relation = new FoldersRelation();
        relation.setForeignModel(FoldersRelation.FOREIGN_MODEL_RESOURCE);
        relation.setForeignId(savedResource.getId());
        relation.setUserId(userId);
        relation.setFolderParentId(request.getFolderParentId());
        foldersRelationRepository.save(relation);

        return savedResource;
    }

    /**
     * Validate a resource's destination folder at create time (PHP Folders plugin
     * {@code ResourcesAfterCreateService::validateParentFolder}). {@code folder_parent_id}
     * is not a column on resources — it seeds a {@code folders_relations} row — so
     * the checks live here rather than in bean validation. Messages mirror the
     * reference verbatim; all failures surface as 400 and roll back the create.
     */
    private void validateFolderParent(String folderParentId, String userId) {
        if (!isUuid(folderParentId)) {
            throw new com.jpassbolt.api.exception.PassboltApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "The folder parent identifier should be a valid UUID.");
        }
        if (!folderRepository.existsById(folderParentId)) {
            throw new com.jpassbolt.api.exception.PassboltApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "The folder parent does not exist.");
        }
        // Group-inclusive UPDATE check on the folder (PHP UserHasPermissionService).
        if (!permissionRepository.hasAccessIncludingGroups(
                FolderService.FOLDER_ACO, folderParentId, userId, Permission.UPDATE)) {
            throw new com.jpassbolt.api.exception.PassboltApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
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

    /**
     * v5 CREATE path: settings gate + parse-only metadata validation + key-type
     * rules, then persist the metadata trio + resource_type_id. Leaves the v4
     * plaintext columns null. Does NOT run the shared-on-shared rule (update-only
     * in PHP). All failures surface as 400 (controller wraps them as 400 too).
     */
    private void applyV5MetadataOnCreate(Resource resource, ResourceDto.CreateRequest request) {
        // Structural guards in PHP order (MetadataResourceDto::validateRequestPayload):
        // (1) partial-v5 (all three v5 fields required) BEFORE (2) mixed-payload
        // (no v4 fields). Both live in the shared MetadataValidationSupport so
        // ResourceService and FolderService enforce identical rules/messages/order.
        metadataValidationSupport.assertV5FieldsComplete(
                request.getMetadata(), request.getMetadataKeyId(), request.getMetadataKeyType());
        metadataValidationSupport.assertNoV4Fields(request.getName(), request.getUsername(),
                request.getUri(), request.getDescription());
        if (!metadataTypesSettingsService.isV5ResourceCreationAllowed()) {
            throw new com.jpassbolt.api.exception.PassboltApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "The settings selected by your administrator prevent from creating a V5 resource.");
        }
        metadataValidationSupport.assertParsableMetadataMessage(
                request.getMetadata(), RESOURCE_METADATA_NOT_DECRYPTABLE);
        metadataValidationSupport.assertMetadataKeyType(request.getMetadataKeyType());
        metadataValidationSupport.assertMetadataKeyTypeAllowedBySettings(request.getMetadataKeyType());
        metadataValidationSupport.assertMetadataKeyIdNotExpired(
                request.getMetadataKeyType(), request.getMetadataKeyId());

        resource.setMetadata(request.getMetadata());
        resource.setMetadataKeyId(request.getMetadataKeyId());
        resource.setMetadataKeyType(request.getMetadataKeyType());
        resource.setResourceTypeId(request.getResourceTypeId());
        // name/username/uri/description intentionally left null (v5).
    }

    /**
     * v5 UPDATE path: parse-only metadata validation + key-type rules + the
     * shared-on-shared rule against the EXISTING permissions of {@code id}, then
     * persist the metadata trio + resource_type_id. Update is NOT creation-gated
     * (PHP gates creation only). All failures surface as 400.
     */
    private void applyV5MetadataOnUpdate(Resource resource, ResourceDto.UpdateRequest request, String id) {
        // Structural guards in PHP order (MetadataResourceDto::validateRequestPayload):
        // (1) partial-v5 (all three v5 fields required) BEFORE (2) mixed-payload
        // (no v4 fields). Shared with FolderService for identical rules/order.
        metadataValidationSupport.assertV5FieldsComplete(
                request.getMetadata(), request.getMetadataKeyId(), request.getMetadataKeyType());
        metadataValidationSupport.assertNoV4Fields(request.getName(), request.getUsername(),
                request.getUri(), request.getDescription());
        metadataValidationSupport.assertParsableMetadataMessage(
                request.getMetadata(), RESOURCE_METADATA_NOT_DECRYPTABLE);
        metadataValidationSupport.assertMetadataKeyType(request.getMetadataKeyType());
        metadataValidationSupport.assertMetadataKeyTypeAllowedBySettings(request.getMetadataKeyType());
        metadataValidationSupport.assertMetadataKeyIdNotExpired(
                request.getMetadataKeyType(), request.getMetadataKeyId());
        metadataValidationSupport.assertKeyTypeSharedOnSharedItem(
                request.getMetadataKeyType(), id, RESOURCE_PERSONAL_SHARED);

        resource.setMetadata(request.getMetadata());
        resource.setMetadataKeyId(request.getMetadataKeyId());
        resource.setMetadataKeyType(request.getMetadataKeyType());
        if (request.getResourceTypeId() != null) {
            resource.setResourceTypeId(request.getResourceTypeId());
        }
        // Clear the stale v4 plaintext columns when upgrading a v4 row to v5
        // (PHP ResourcesUpdateService::patchEntity: name/username/uri/description = null).
        // Prevents a mixed/corrupt row that leaks plaintext alongside the blob.
        resource.setName(null);
        resource.setUsername(null);
        resource.setUri(null);
        resource.setDescription(null);
    }

    /**
     * Update an existing resource.
     *
     * @param id      the resource ID
     * @param request the update request DTO
     * @param userId  the requesting user's ID
     * @return an Optional containing the updated resource if found
     */
    @Transactional
    public Optional<Resource> updateResource(String id, ResourceDto.UpdateRequest request, String userId) {
        return resourceRepository.findById(id)
                .filter(r -> !r.getDeleted())
                .map(resource -> {
                    if (request.getName() != null) {
                        resource.setName(request.getName());
                    }
                    if (request.getUsername() != null) {
                        resource.setUsername(request.getUsername());
                    }
                    if (request.getUri() != null) {
                        resource.setUri(request.getUri());
                    }
                    if (request.getDescription() != null) {
                        resource.setDescription(request.getDescription());
                    }
                    if (request.getResourceTypeId() != null) {
                        resource.setResourceTypeId(request.getResourceTypeId());
                    }
                    if (request.getMetadata() != null) {
                        // v5 metadata update: validate (parse-only) + key-type rules,
                        // enforce the shared-on-shared rule against the existing
                        // permissions, then persist the metadata trio + resource_type_id.
                        // The v4 conditional setters above are no-ops when those fields
                        // are absent, so a pure-v5 request never touches them.
                        applyV5MetadataOnUpdate(resource, request, id);
                    }
                    resource.setModifiedBy(userId);

                    // Update secrets if provided
                    if (request.getSecrets() != null) {
                        for (ResourceDto.CreateRequest.SecretData secretData : request.getSecrets()) {
                            String targetUserId = secretData.getUserId() != null ? secretData.getUserId() : userId;
                            Optional<Secret> existingSecret = secretRepository.findByResourceIdAndUserId(id,
                                    targetUserId);
                            if (existingSecret.isPresent()) {
                                Secret secret = existingSecret.get();
                                secret.setData(secretData.getData());
                                secretRepository.save(secret);
                            } else {
                                Secret secret = new Secret();
                                secret.setResourceId(id);
                                secret.setUserId(targetUserId);
                                secret.setData(secretData.getData());
                                secretRepository.save(secret);
                            }
                        }
                    }

                    return resourceRepository.save(resource);
                });
    }

    /**
     * Soft delete a resource.
     *
     * @param id     the resource ID
     * @param userId the requesting user's ID
     * @return true if the resource was deleted, false if not found
     */
    @Transactional
    public boolean deleteResource(String id, String userId) {
        return resourceRepository.findById(id)
                .filter(r -> !r.getDeleted())
                .map(resource -> {
                    resource.setDeleted(true);
                    resource.setModifiedBy(userId);
                    resourceRepository.save(resource);
                    // Cascade: hard-delete favorites of a soft-deleted resource
                    // (PHP ResourcesTable::softDelete), same transaction.
                    favoriteRepository.deleteByForeignKey(id);
                    // Drop the resource from every user's folder tree (PHP
                    // ResourcesEventListener afterResourceSoftDeleted).
                    foldersRelationRepository.deleteByForeignId(id);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Get secrets for a resource.
     *
     * @param resourceId the resource ID
     * @return list of secrets
     */
    @Transactional(readOnly = true)
    public List<Secret> getSecretsForResource(String resourceId) {
        return secretRepository.findByResourceId(resourceId);
    }

    /**
     * Get secret for a specific user and resource.
     *
     * @param resourceId the resource ID
     * @param userId     the user ID
     * @return an Optional containing the secret if found
     */
    @Transactional(readOnly = true)
    public Optional<Secret> getSecretForUser(String resourceId, String userId) {
        return secretRepository.findByResourceIdAndUserId(resourceId, userId);
    }
}
