package com.jpassbolt.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Action Entity — the {@code actions} dimension table (id ↔ action name).
 *
 * <p>Port of the Passbolt Log plugin's {@code actions} table. Each distinct endpoint
 * (controller + handler method) is recorded once; {@link ActionLog} rows then reference
 * it by {@code action_id} instead of repeating the name. Populated lazily via
 * find-or-create by {@link com.jpassbolt.api.service.ActionLogService}.</p>
 *
 * <p><b>Action identity.</b> PHP derives {@code action_id} from a CakePHP
 * {@code "Controller.action"} string via a name-based UUID. JPassbolt has no such Cake
 * action name, so it uses the Spring-native equivalent {@code "<ControllerSimpleName>.<method>"}
 * (e.g. {@code "SecretController.getSecretByResource"}) and the same name-based-UUID
 * scheme as the response envelope's {@code header.action}
 * ({@link com.jpassbolt.api.util.ApiResponse#actionFor}). This is a documented, principled
 * deviation: the exact UUID values differ from upstream Passbolt, but identity is stable
 * per endpoint and groups all calls to the same handler together (unlike hashing the
 * concrete request URL, which embeds per-instance UUIDs).</p>
 *
 * <p><b>Schema facts (do NOT change — MySQL {@code ddl-auto=validate}):</b> only
 * {@code id char(36)} and {@code name varchar(100)} (unique); no timestamps. Hence this
 * entity does not extend {@link BaseEntity}.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "actions")
public class Action {

    @Id
    @Column(columnDefinition = "char(36)")
    private String id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    public Action(String id, String name) {
        this.id = id;
        this.name = name;
    }

    /**
     * Deterministic action id for an action name (name-based UUID), matching the scheme
     * used for the response envelope's {@code header.action}. Idempotent: the same name
     * always maps to the same id, which is what makes find-or-create safe.
     */
    public static String idForName(String actionName) {
        String seed = actionName != null ? actionName : "Error.error";
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
