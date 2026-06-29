package com.jpassbolt.api.repository;

import com.jpassbolt.api.model.ActionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for the append-only {@code action_logs} operation audit table. Writes go
 * through {@link com.jpassbolt.api.service.ActionLogService}; the finders serve the
 * (CE-unexposed) Activity-log read side and tests.
 */
@Repository
public interface ActionLogRepository extends JpaRepository<ActionLog, String> {

    List<ActionLog> findByUserId(String userId);

    List<ActionLog> findByActionId(String actionId);

    long countByActionId(String actionId);
}
