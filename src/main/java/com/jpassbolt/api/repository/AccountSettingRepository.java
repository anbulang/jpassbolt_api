package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.AccountSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
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
     * Read-only derived finder mirroring the PHP
     * {@code AccountSettingsTable::findIndex(userId, whitelist)}: returns the
     * caller's settings rows whose {@code property} is in the given set (e.g.
     * {@code ['theme','locale']}). Looked up by the {@code (user_id, property)}
     * pair instead of the UUIDv5-derived {@code property_id}, consistent with
     * {@link #findFirstByUserIdAndProperty}. Not a schema change — a plain
     * derived query over the existing columns.
     */
    List<AccountSetting> findByUserIdAndPropertyIn(String userId, Collection<String> properties);

    /**
     * Derived delete — the caller must run inside a transaction
     * (e.g. an {@code @Transactional} service method).
     */
    void deleteByUserIdAndProperty(String userId, String property);
}
