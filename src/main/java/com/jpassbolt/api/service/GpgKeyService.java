package com.jpassbolt.api.service;

import com.jpassbolt.api.model.GpgKey;
import com.jpassbolt.api.repository.GpgKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Read-only query service for the GPG public key directory.
 *
 * Mirrors the behavior of the PHP reference implementation
 * (GpgkeysTable::findIndex / GpgkeysTable::findView). The official Passbolt
 * browser plugin relies on these queries to synchronize its local keyring.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GpgKeyService {

    private final GpgKeyRepository gpgKeyRepository;

    /**
     * List GPG keys, replicating PHP findIndex semantics:
     * - always filtered on the deleted flag (defaults to false upstream),
     * - optional "modified-after" filter with STRICT greater-than comparison
     *   (PHP: modified > X), so keys whose modified equals the boundary are
     *   NOT returned — this matters for the plugin's incremental keyring sync.
     *
     * @param isDeleted     value of the deleted flag to match (filter[is-deleted])
     * @param modifiedAfter optional lower bound (exclusive) on modified, or null
     * @return matching keys
     */
    @Transactional(readOnly = true)
    public List<GpgKey> getGpgKeys(boolean isDeleted, LocalDateTime modifiedAfter) {
        if (modifiedAfter == null) {
            return gpgKeyRepository.findByDeleted(isDeleted);
        }
        return gpgKeyRepository.findByDeletedAndModifiedAfter(isDeleted, modifiedAfter);
    }

    /**
     * Fetch a single GPG key by id, replicating PHP findView semantics.
     *
     * IMPORTANT: this deliberately does NOT apply the project's usual
     * soft-delete filter (findById().filter(!deleted)). The PHP reference
     * (GpgkeysTable::findView, L203-214) only adds where(id = ...) — its
     * comment claims "Same rule than index apply" but the code never applies
     * the deleted filter, so a soft-deleted key is still returned with HTTP
     * 200 and deleted=true. The official plugin depends on this behavior to
     * detect revoked/deleted keys during keyring sync. Do not "fix" this.
     *
     * @param id the gpgkey uuid
     * @return the key if it exists (deleted or not)
     */
    @Transactional(readOnly = true)
    public Optional<GpgKey> getGpgKeyById(String id) {
        return gpgKeyRepository.findById(id);
    }
}
