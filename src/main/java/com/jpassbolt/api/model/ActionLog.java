package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * ActionLog Entity — one row per HTTP request that reaches a controller handler.
 *
 * <p>Port of the Passbolt Log plugin's {@code action_logs} table (operation audit log).
 * Written by {@link com.jpassbolt.api.config.ActionLogInterceptor} at
 * {@code afterCompletion}, when the response status is known. The PHP equivalent is built
 * from the {@code UserAction} singleton in the controller's after-filter.</p>
 *
 * <p>Columns:
 * <ul>
 *   <li>{@code id} — random UUID per request (the PHP {@code userActionId})</li>
 *   <li>{@code user_id} — the authenticated user, or {@code null} for guests/public endpoints</li>
 *   <li>{@code action_id} — FK into {@link Action} (the endpoint identity)</li>
 *   <li>{@code context} — {@code "<METHOD> <path>"} (concrete path, ≤255 ASCII), e.g. {@code "GET /resources/<uuid>.json"}</li>
 *   <li>{@code status} — {@code 1} when the HTTP status was exactly 200, else {@code 0} (mirrors PHP {@code (int)($code === 200)})</li>
 *   <li>{@code created} — UTC timestamp</li>
 * </ul></p>
 *
 * <p><b>Schema facts (do NOT change — MySQL {@code ddl-auto=validate}):</b> append-only,
 * only {@code created} (no {@code modified}); {@code user_id} is the one nullable column.
 * Hence it does not extend {@link BaseEntity}.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "action_logs")
public class ActionLog {

    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_ERROR = 0;

    @Id
    @Column(columnDefinition = "char(36)")
    private String id;

    @Column(name = "user_id", length = 36, columnDefinition = "char(36)")
    private String userId;

    @Column(name = "action_id", nullable = false, length = 36, columnDefinition = "char(36)")
    private String actionId;

    @Column(name = "context", nullable = false, length = 255)
    private String context;

    @Column(name = "status", nullable = false)
    private Integer status;

    @Column(name = "created", nullable = false, updatable = false)
    private LocalDateTime created;

    public ActionLog(String userId, String actionId, String context, int status) {
        this.userId = userId;
        this.actionId = actionId;
        this.context = context;
        this.status = status;
    }

    @PrePersist
    protected void onCreate() {
        created = LocalDateTime.now(ZoneOffset.UTC);
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
