package com.jpassbolt.api.service;

import com.jpassbolt.api.dto.MetadataSettingsDto;
import com.jpassbolt.api.exception.PassboltApiException;
import com.jpassbolt.api.model.MetadataKey;
import com.jpassbolt.api.model.User;
import com.jpassbolt.api.repository.MetadataKeyRepository;
import com.jpassbolt.api.repository.MetadataPrivateKeyRepository;
import com.jpassbolt.api.repository.OrganizationSettingRepository;
import com.jpassbolt.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Disabling zero-knowledge mode while shared metadata keys exist but no
 * server-copy private key is present must require the payload to carry the
 * server metadata private key (PHP
 * {@code MetadataKeysSettingsSetService::shouldCreateMetadataPrivateKey}). This
 * guards the H-1 data-integrity gap: silently dropping the field would break the
 * metadata-key distribution chain after the mode switch.
 */
@SpringBootTest
class MetadataKeysSettingsZeroKnowledgeTest {

    @Autowired
    private MetadataKeysSettingsService service;

    @Autowired
    private OrganizationSettingRepository organizationSettingRepository;

    @Autowired
    private MetadataKeyRepository metadataKeyRepository;

    @Autowired
    private MetadataPrivateKeyRepository metadataPrivateKeyRepository;

    @Autowired
    private UserRepository userRepository;

    private String adminId;

    @BeforeEach
    void setUp() {
        organizationSettingRepository.deleteAll();
        metadataPrivateKeyRepository.deleteAll();
        metadataKeyRepository.deleteAll();
        userRepository.findByUsername("zkadmin@passbolt.com").ifPresent(userRepository::delete);

        User admin = new User();
        admin.setUsername("zkadmin@passbolt.com");
        admin.setRoleId("admin");
        admin.setActive(true);
        admin.setDeleted(false);
        adminId = userRepository.save(admin).getId();
    }

    private MetadataSettingsDto.KeysSettingsUpdate update(boolean zeroKnowledge) {
        return MetadataSettingsDto.KeysSettingsUpdate.builder()
                .allowUsageOfPersonalKeys(true)
                .zeroKnowledgeKeyShare(zeroKnowledge)
                .build();
    }

    @Test
    void disablingZeroKnowledgeWithoutServerKey_Requires_MetadataPrivateKeys() {
        // 1) Establish zero-knowledge = true (no key logic runs from the default).
        service.setKeysSettings(update(true), adminId);
        // 2) A shared metadata key exists, but no server-copy private key.
        MetadataKey key = new MetadataKey();
        key.setFingerprint("2FC8945833C51946E937F9FED47B0811573EE67E");
        key.setArmoredKey("-----BEGIN PGP PUBLIC KEY BLOCK-----\n(test)\n-----END PGP PUBLIC KEY BLOCK-----");
        metadataKeyRepository.save(key);

        // 3) Turning ZK off WITHOUT supplying the server private key must 400.
        assertThatThrownBy(() -> service.setKeysSettings(update(false), adminId))
                .isInstanceOf(PassboltApiException.class)
                .hasMessageContaining("server metadata private key is required");

        // The settings must NOT have flipped to user-friendly (still zero-knowledge).
        assertThat(service.getKeysSettings().getZeroKnowledgeKeyShare()).isTrue();
    }

    @Test
    void disablingZeroKnowledge_NoSharedKeys_Succeeds() {
        // With no shared metadata keys, shouldCreateMetadataPrivateKey is false,
        // so turning ZK off needs no server private key.
        service.setKeysSettings(update(true), adminId);
        service.setKeysSettings(update(false), adminId);

        assertThat(service.getKeysSettings().getZeroKnowledgeKeyShare()).isFalse();
    }
}
