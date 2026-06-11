package com.jpassbolt.api.service;

import com.jpassbolt.api.dto.ResourceDto;
import com.jpassbolt.api.model.Permission;
import com.jpassbolt.api.model.Resource;
import com.jpassbolt.api.model.Secret;
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

    /**
     * Get all non-deleted resources that the user has at least READ access to.
     *
     * @param userId the requesting user's ID
     * @return list of accessible resources
     */
    @Transactional(readOnly = true)
    public List<Resource> getAccessibleResources(String userId) {
        List<String> resourceIds = permissionRepository.findAccessibleResourceIds(userId, Permission.READ);
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
        Resource resource = new Resource();
        resource.setName(request.getName());
        resource.setUsername(request.getUsername());
        resource.setUri(request.getUri());
        resource.setDescription(request.getDescription());
        resource.setResourceTypeId(request.getResourceTypeId());
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

        return savedResource;
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
