package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.Action;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for the {@code actions} dimension table. Lookups are by primary key (the
 * deterministic action id); writes are find-or-create from {@link com.jpassbolt.api.service.ActionLogService}.
 */
@Repository
public interface ActionRepository extends JpaRepository<Action, String> {
}
