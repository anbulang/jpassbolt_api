package com.jpassbolt.api.service;

import com.jpassbolt.api.config.ActionLogProperties;
import com.jpassbolt.api.model.Action;
import com.jpassbolt.api.model.ActionLog;
import com.jpassbolt.api.repository.ActionLogRepository;
import com.jpassbolt.api.repository.ActionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for {@link ActionLogService}: the enable flag and blacklist gate
 * writes, status maps PHP-style ({@code 1} iff HTTP 200), find-or-create is idempotent and
 * cached, context is capped to the column width, and failures never propagate.
 */
class ActionLogServiceTest {

    private final ActionRepository actionRepository = mock(ActionRepository.class);
    private final ActionLogRepository actionLogRepository = mock(ActionLogRepository.class);

    private ActionLogService service(ActionLogProperties props) {
        return new ActionLogService(props, actionRepository, actionLogRepository);
    }

    private static ActionLogProperties props(boolean enabled, List<String> blacklist) {
        ActionLogProperties p = new ActionLogProperties();
        p.setEnabled(enabled);
        p.setBlacklist(blacklist);
        return p;
    }

    @Test
    void disabledLogsNothing() {
        service(props(false, List.of())).logAction("u", "ResourceController.getAllResources", "GET /resources.json", 200);
        verify(actionLogRepository, never()).save(any());
        verify(actionRepository, never()).save(any());
    }

    @Test
    void blacklistedActionIsNotLogged() {
        service(props(true, List.of("HealthcheckController.status")))
                .logAction(null, "HealthcheckController.status", "GET /healthcheck/status.json", 200);
        verify(actionLogRepository, never()).save(any());
    }

    @Test
    void nullActionNameIsNotLogged() {
        service(props(true, List.of())).logAction("u", null, "GET /x", 200);
        verify(actionLogRepository, never()).save(any());
    }

    @Test
    void status200MapsToSuccessAndRecordsUserActionAndId() {
        when(actionRepository.existsById(any())).thenReturn(true);
        service(props(true, List.of()))
                .logAction("user-1", "ResourceController.getResource", "GET /resources/x.json", 200);

        ArgumentCaptor<ActionLog> captor = ArgumentCaptor.forClass(ActionLog.class);
        verify(actionLogRepository, times(1)).save(captor.capture());
        ActionLog log = captor.getValue();
        assertThat(log.getStatus()).isEqualTo(ActionLog.STATUS_SUCCESS);
        assertThat(log.getUserId()).isEqualTo("user-1");
        assertThat(log.getContext()).isEqualTo("GET /resources/x.json");
        assertThat(log.getActionId()).isEqualTo(Action.idForName("ResourceController.getResource"));
    }

    @Test
    void nonNotOkStatusMapsToError() {
        when(actionRepository.existsById(any())).thenReturn(true);
        service(props(true, List.of()))
                .logAction("u", "ResourceController.getResource", "GET /resources/x.json", 404);

        ArgumentCaptor<ActionLog> captor = ArgumentCaptor.forClass(ActionLog.class);
        verify(actionLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ActionLog.STATUS_ERROR);
    }

    @Test
    void created201MapsToSuccess() {
        // Regression sentinel: ResourceController.createResource returns HTTP 201; a
        // successful create must be recorded as success (1), not failure. The whole 2xx
        // range maps to success.
        when(actionRepository.existsById(any())).thenReturn(true);
        service(props(true, List.of()))
                .logAction("u", "ResourceController.createResource", "POST /resources.json", 201);

        ArgumentCaptor<ActionLog> captor = ArgumentCaptor.forClass(ActionLog.class);
        verify(actionLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ActionLog.STATUS_SUCCESS);
    }

    @Test
    void anonymousRequestIsLoggedWithNullUserId() {
        // Guest/public endpoints have no authenticated user; the action_logs.user_id column
        // is nullable and must accept null without dropping the row.
        when(actionRepository.existsById(any())).thenReturn(true);
        service(props(true, List.of()))
                .logAction(null, "SettingsController.getSettings", "GET /settings.json", 200);

        ArgumentCaptor<ActionLog> captor = ArgumentCaptor.forClass(ActionLog.class);
        verify(actionLogRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
    }

    @Test
    void defaultBlacklistContainsTheExpectedPollingEndpoints() {
        assertThat(new ActionLogProperties().getBlacklist())
                .containsExactly("AuthController.isAuthenticated", "HealthcheckController.status");
    }

    @Test
    void defaultBlacklistGatesIsAuthenticatedPolling() {
        // Use the real default props (no override) to prove the shipped blacklist works.
        new ActionLogService(new ActionLogProperties(), actionRepository, actionLogRepository)
                .logAction(null, "AuthController.isAuthenticated", "GET /auth/is-authenticated.json", 200);
        verify(actionLogRepository, never()).save(any());
        verify(actionRepository, never()).save(any());
    }

    @Test
    void createsActionWhenAbsentThenCachesAcrossCalls() {
        when(actionRepository.existsById(any())).thenReturn(false);
        ActionLogService svc = service(props(true, List.of()));

        svc.logAction("u", "ResourceController.getAllResources", "GET /resources.json", 200);
        svc.logAction("u", "ResourceController.getAllResources", "GET /resources.json", 200);

        // Existence check + action insert happen only once (cached after first sighting);
        // both requests still produce an action_logs row.
        verify(actionRepository, times(1)).existsById(any());
        verify(actionRepository, times(1)).save(any(Action.class));
        verify(actionLogRepository, times(2)).save(any(ActionLog.class));
    }

    @Test
    void doesNotCreateActionWhenAlreadyPresent() {
        when(actionRepository.existsById(any())).thenReturn(true);
        service(props(true, List.of()))
                .logAction("u", "ResourceController.getResource", "GET /resources/x.json", 200);
        verify(actionRepository, never()).save(any(Action.class));
        verify(actionLogRepository, times(1)).save(any(ActionLog.class));
    }

    @Test
    void contextIsTruncatedToColumnWidth() {
        when(actionRepository.existsById(any())).thenReturn(true);
        String longContext = "GET /" + "a".repeat(400);
        service(props(true, List.of()))
                .logAction("u", "X.y", longContext, 200);

        ArgumentCaptor<ActionLog> captor = ArgumentCaptor.forClass(ActionLog.class);
        verify(actionLogRepository).save(captor.capture());
        assertThat(captor.getValue().getContext()).hasSize(255);
    }

    @Test
    void swallowsPersistenceFailure() {
        when(actionRepository.existsById(any())).thenReturn(true);
        when(actionLogRepository.save(any())).thenThrow(new RuntimeException("db down"));
        assertThatCode(() -> service(props(true, List.of()))
                .logAction("u", "X.y", "GET /x", 200)).doesNotThrowAnyException();
    }
}
