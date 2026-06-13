package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.AccountSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for {@link AccountSetting} entities.
 *
 * <p>
 * Settings are looked up by the {@code (user_id, property)} pair (compare
 * with the PHP {@code AccountSettingsTable::getFirstPropertyOrFail}) instead
 * of the UUIDv5-derived {@code property_id}. The table has no {@code deleted}
 * column, so there is no soft-delete filtering here.
 * </p>
 */
@Repository
public interface AccountSettingRepository extends JpaRepository<AccountSetting, String> {

    Optional<AccountSetting> findFirstByUserIdAndProperty(String userId, String property);

    /**
     * Derived delete — the caller must run inside a transaction
     * (e.g. an {@code @Transactional} service method).
     */
    void deleteByUserIdAndProperty(String userId, String property);
}
