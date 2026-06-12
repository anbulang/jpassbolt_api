package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.OrganizationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for {@link OrganizationSetting} entities.
 *
 * <p>
 * Settings are looked up by their {@code property} name (e.g. "locale")
 * instead of the UUIDv5-derived {@code property_id} used by the PHP
 * implementation — equivalent for read-only access and far more robust.
 * The table has no {@code deleted} column, so there is no soft-delete
 * filtering here.
 * </p>
 */
@Repository
public interface OrganizationSettingRepository extends JpaRepository<OrganizationSetting, String> {

    Optional<OrganizationSetting> findByProperty(String property);
}
