package com.jpassbolt.api.service;

import com.jpassbolt.api.config.ActionLogProperties;
import com.jpassbolt.api.model.Action;
import com.jpassbolt.api.model.ActionLog;
import com.jpassbolt.api.repository.ActionLogRepository;
import com.jpassbolt.api.repository.ActionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes the operation audit log ({@code action_logs} + the {@code actions} dimension).
 *
 * <p>Port of the PHP {@code ActionLogsCreateService} / {@code ActionLogsTable::create} /
 * {@code ActionsTable::findOrCreateAction}. Invoked once per request from
 * {@link com.jpassbolt.api.config.ActionLogInterceptor} after the response status is known.</p>
 *
 * <p><b>Design notes:</b>
 * <ul>
 *   <li><b>Best-effort:</b> auditing must never affect the client response (the response is
 *       already committed by {@code afterCompletion} time), so every failure is logged and
 *       swallowed.</li>
 *   <li><b>No surrounding {@code @Transactional}:</b> each {@code save()} runs in its own
 *       short transaction. This is intentional — sharing one transaction would let a
 *       concurrency-induced duplicate-key on the {@code actions} insert mark the whole
 *       transaction rollback-only, which a caught exception cannot undo, breaking the
 *       subsequent {@code action_logs} insert. The action dimension row and the log row do
 *       not need to be atomic.</li>
 *   <li><b>Action cache:</b> {@link #knownActionIds} avoids a DB round-trip for the action
 *       on every request once it has been seen (mirrors PHP's cached actions table).</li>
 * </ul></p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionLogService {

    private final ActionLogProperties properties;
    private final ActionRepository actionRepository;
    private final ActionLogRepository actionLogRepository;

    /** Process-local set of action ids known to exist, to skip the existence check. */
    private final Set<String> knownActionIds = ConcurrentHashMap.newKeySet();

    /**
     * Record an audited action. Best-effort; never throws.
     *
     * @param userId     the authenticated user id, or {@code null} for guests/public endpoints
     * @param actionName the endpoint identity, {@code "<ControllerSimpleName>.<method>"}
     * @param context    {@code "<METHOD> <path>"} (will be ASCII-trimmed to 255 chars)
     * @param httpStatus the final HTTP status code of the response
     */
    public void logAction(String userId, String actionName, String context, int httpStatus) {
        if (!properties.isEnabled() || actionName == null) {
            return;
        }
        if (properties.getBlacklist() != null && properties.getBlacklist().contains(actionName)) {
            return;
        }
        try {
            String actionId = Action.idForName(actionName);
            ensureAction(actionId, actionName);
            // PHP computes status = (int)(code === 200), which is sound there only because
            // CakePHP's AppController::success() hardcodes HTTP 200 for EVERY success. In
            // JPassbolt one success path differs — ResourceController.createResource returns
            // 201 — so map the whole 2xx range to success; otherwise a successful create
            // would be recorded as a failure (status 0).
            int status = httpStatus >= 200 && httpStatus < 300 ? ActionLog.STATUS_SUCCESS : ActionLog.STATUS_ERROR;
            actionLogRepository.save(new ActionLog(userId, actionId, sanitizeContext(context), status));
        } catch (Exception e) {
            log.error("Failed to record action log (action={}, status={}): {}",
                    actionName, httpStatus, e.getMessage());
        }
    }

    /**
     * Find-or-create the {@link Action} for {@code actionId}. Idempotent and
     * concurrency-tolerant: a duplicate-key from a racing request is treated as success.
     */
    private void ensureAction(String actionId, String actionName) {
        if (knownActionIds.contains(actionId)) {
            return;
        }
        if (!actionRepository.existsById(actionId)) {
            try {
                actionRepository.save(new Action(actionId, actionName));
            } catch (DataIntegrityViolationException raced) {
                // Another request inserted the same action concurrently — fine.
            }
        }
        knownActionIds.add(actionId);
    }

    /**
     * The {@code context} column is {@code varchar(255)} ASCII. Paths are already ASCII;
     * cap the length defensively so an unusually long URL never fails the insert.
     */
    private String sanitizeContext(String context) {
        if (context == null) {
            return "";
        }
        return context.length() > 255 ? context.substring(0, 255) : context;
    }
}
