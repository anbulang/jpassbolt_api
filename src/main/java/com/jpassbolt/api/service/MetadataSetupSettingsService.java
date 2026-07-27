package com.jpassbolt.api.service;

import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.MetadataKeyRepository;
import com.jpassbolt.api.repository.MetadataPrivateKeyRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * v5 Metadata onboarding probes — the two read-only booleans the official
 * browser extension polls to drive the "getting started" hint and the
 * fresh-install setup default. Ports PHP
 * {@code MetadataSettingsGetStartedService} and
 * {@code Setup\MetadataSettingsSetupService}. Both endpoints that consume this
 * are admin-gated in the controller, matching the PHP {@code assertIsAdmin()}.
 */
@Service
@RequiredArgsConstructor
public class MetadataSetupSettingsService {

    private final OrganizationSettingRepository organizationSettingRepository;
    private final MetadataKeyRepository metadataKeyRepository;
    private final MetadataPrivateKeyRepository metadataPrivateKeyRepository;
    private final UserRepository userRepository;
    private final UserService userService;

    /** passbolt.plugins.metadata.enableForExistingInstances (CE default true). */
    @Value("${jpassbolt.metadata.enable-for-existing-instances:true}")
    private boolean enableForExistingInstances;

    /** passbolt.plugins.metadata.enableForNewInstances (CE default true). */
    @Value("${jpassbolt.metadata.enable-for-new-instances:true}")
    private boolean enableForNewInstances;

    /**
     * {@code GET /metadata/settings/getting-started}: true on a pristine
     * instance — the flag is on, neither metadata types/keys settings row
     * exists, and there are no metadata keys nor private keys yet (PHP
     * {@code MetadataSettingsGetStartedService::get}).
     */
    @Transactional(readOnly = true)
    public boolean isGettingStartedEnabled() {
        if (!enableForExistingInstances) {
            return false;
        }
        boolean settingsExist = organizationSettingRepository
                .findByProperty(MetadataTypesSettingsService.ORG_SETTING_PROPERTY).isPresent()
                || organizationSettingRepository
                .findByProperty(MetadataKeysSettingsService.ORG_SETTING_PROPERTY).isPresent();
        if (settingsExist) {
            return false;
        }
        return metadataKeyRepository.count() == 0 && metadataPrivateKeyRepository.count() == 0;
    }

    /**
     * {@code GET /metadata/setup/settings}: true when this is a fresh install —
     * the flag is on and the only user is an active administrator (PHP
     * {@code Setup\MetadataSettingsSetupService::get}).
     */
    @Transactional(readOnly = true)
    public boolean isEncryptedMetadataEnabledOnInstall() {
        if (!enableForNewInstances) {
            return false;
        }
        List<User> users = userRepository.findAll();
        if (users.size() != 1) {
            return false;
        }
        User sole = users.get(0);
        return Boolean.TRUE.equals(sole.getActive()) && userService.isAdmin(sole.getId());
    }
}
