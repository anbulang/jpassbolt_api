package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ResourceRepository extends JpaRepository<Resource, String> {

    List<Resource> findByDeletedFalse();

    List<Resource> findByCreatedBy(String userId);

    /**
     * Count non soft-deleted resources of a given resource type. Used by
     * {@code ResourceTypeService.deleteResourceType} to block soft-deleting a
     * resource type while live resources still reference it (ported from PHP
     * {@code ResourceTypesDeleteService}).
     */
    long countByResourceTypeIdAndDeletedFalse(String resourceTypeId);
}
