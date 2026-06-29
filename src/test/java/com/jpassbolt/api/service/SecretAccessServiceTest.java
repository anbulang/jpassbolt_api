package com.jpassbolt.api.service;

import com.jpassbolt.api.model.Secret;
import com.jpassbolt.api.model.SecretAccess;
import com.jpassbolt.api.repository.SecretAccessRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-Mockito unit tests for {@link SecretAccessService}: the secret-access write path
 * records exactly the (user, resource, secret) triple, filters a resource's secret list
 * to the caller's own secret, guards against nulls, and — critically — never propagates an
 * exception (auditing must not break a password read).
 */
class SecretAccessServiceTest {

    private final SecretAccessRepository repository = mock(SecretAccessRepository.class);
    private final SecretAccessService service = new SecretAccessService(repository);

    @Test
    void logAccessRecordsTheTriple() {
        service.logAccess("user-1", "res-1", "sec-1");

        ArgumentCaptor<SecretAccess> captor = ArgumentCaptor.forClass(SecretAccess.class);
        verify(repository, times(1)).save(captor.capture());
        SecretAccess saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo("user-1");
        assertThat(saved.getResourceId()).isEqualTo("res-1");
        assertThat(saved.getSecretId()).isEqualTo("sec-1");
    }

    @Test
    void logAccessSkipsOnNullArgument() {
        service.logAccess(null, "res-1", "sec-1");
        service.logAccess("user-1", null, "sec-1");
        service.logAccess("user-1", "res-1", null);
        verify(repository, never()).save(any());
    }

    @Test
    void logCallerSecretAccessRecordsOnlyTheCallersOwnSecret() {
        Secret callers = secret("sec-mine", "res-1", "user-1");
        Secret othersCiphertext = secret("sec-other", "res-1", "user-2");

        service.logCallerSecretAccess("user-1", List.of(othersCiphertext, callers));

        ArgumentCaptor<SecretAccess> captor = ArgumentCaptor.forClass(SecretAccess.class);
        verify(repository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getSecretId()).isEqualTo("sec-mine");
        assertThat(captor.getValue().getUserId()).isEqualTo("user-1");
    }

    @Test
    void logCallerSecretAccessRecordsNothingWhenCallerHasNoSecret() {
        service.logCallerSecretAccess("user-1", List.of(secret("sec-other", "res-1", "user-2")));
        verify(repository, never()).save(any());
    }

    @Test
    void logAccessSwallowsRepositoryFailure() {
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));
        // Must NOT propagate — the caller already received their secret.
        assertThatCode(() -> service.logAccess("user-1", "res-1", "sec-1")).doesNotThrowAnyException();
    }

    private static Secret secret(String id, String resourceId, String userId) {
        Secret s = new Secret();
        s.setId(id);
        s.setResourceId(resourceId);
        s.setUserId(userId);
        return s;
    }
}
