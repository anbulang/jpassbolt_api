package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for Group entity operations.
 */
@Repository
public interface GroupRepository extends JpaRepository<Group, String> {

    /**
     * Find all non-deleted groups (soft-delete filter).
     */
    List<Group> findByDeletedFalse();

    /**
     * Find all non-deleted groups ordered by name (Passbolt index ordering).
     */
    List<Group> findByDeletedFalseOrderByNameAsc();

    /**
     * Check whether a non-deleted group already uses the given name.
     */
    boolean existsByNameAndDeletedFalse(String name);
}
