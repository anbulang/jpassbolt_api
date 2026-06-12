package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.Folder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for Folder entity operations.
 *
 * <p>
 * Note: folders have NO {@code deleted} column (hard delete, per official
 * schema), so there is intentionally no {@code findByDeletedFalse()} here.
 * Visibility filtering is permission-based (aco = "Folder").
 * </p>
 */
@Repository
public interface FolderRepository extends JpaRepository<Folder, String> {
}
