-- ============================================================================
-- seed_demo_data.sql — JPassbolt demo STRUCTURAL data for the remote MySQL.
--
-- ⚠ EXECUTE MANUALLY, AND ONLY AFTER A BACKUP:
--     mysqldump -h <host> -u <user> -p <database> > backup_$(date +%F).sql
--
-- Scope (structure only, per decision — no resources/secrets/comments/
-- favorites, which require runtime GPG encryption):
--   * users:        dame (fixture keypair, seeded disabled), ruth (inactive +
--                   register token randomized at execution time),
--                   sofia (soft-deleted), edith (disabled)
--   * profiles:     one per user above
--   * gpgkeys:      dame + edith official fixture PUBLIC keys (full armored)
--   * groups:       "Board" (manager: ada; members: betty, dame)
--   * folders:      "Ops" > "Servers" (ada's tree) + folders_relations
--                   + OWNER permissions (aco='Folder') so they are visible
--   * organization_settings: selfRegistration (allowed domain: passbolt.com)
--
-- Properties:
--   * Idempotent: fixed UUID primary keys + INSERT IGNORE (a re-run is a no-op).
--   * Pure INSERTs — no UPDATE / DELETE / DDL.
--   * Rows referencing existing users (ada@passbolt.com / betty@passbolt.com)
--     resolve ids via INSERT ... SELECT on username; if those users do not
--     exist in the target database the dependent rows are simply skipped.
--   * role_id is resolved from the roles table by name (never hardcoded).
--
-- Login note: dame's/edith's keypairs are the official Passbolt fixture keys.
-- Their PRIVATE keys are PUBLIC (published in the open-source reference at
--   passbolt_api_ref/plugins/PassboltDev/TestData/config/gpg/dame_private.key
--   passbolt_api_ref/plugins/PassboltDev/TestData/config/gpg/edith_private.key
-- fixture passphrase convention: the user's email address), so anyone could
-- log in with them. Both users are therefore seeded disabled — every auth path
-- (GpgAuth, JWT login/refresh, recover) rejects disabled accounts. To demo a
-- fixture-key login, re-enable dame explicitly (set disabled = NULL) and ONLY
-- on a throwaway demo database.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- ① dame@passbolt.com — regular user with the official fixture keypair.
--    Seeded disabled because the fixture PRIVATE key is public (see header).
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO users (id, role_id, username, active, deleted, disabled, created, modified)
SELECT 'feedc0de-0001-4000-8000-000000000001', r.id, 'dame@passbolt.com', 1, 0, NOW(), NOW(), NOW()
FROM roles r WHERE r.name = 'user';

INSERT IGNORE INTO profiles (id, user_id, first_name, last_name, created, modified)
VALUES ('feedc0de-1001-4000-8000-000000000001', 'feedc0de-0001-4000-8000-000000000001',
        'Dame Steve', 'Shirley', NOW(), NOW());

INSERT IGNORE INTO gpgkeys (id, user_id, armored_key, bits, uid, key_id, fingerprint,
                            type, expires, key_created, deleted, created, modified)
VALUES ('feedc0de-2001-4000-8000-000000000001', 'feedc0de-0001-4000-8000-000000000001',
        '-----BEGIN PGP PUBLIC KEY BLOCK-----

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
UXjj0w==
=ikRL
-----END PGP PUBLIC KEY BLOCK-----',
        4096, 'Dame ''Steve'' Shirley <dame@passbolt.com>', 'DD8E26DB959CF1D0',
        '03E6535C52AFD7544C555829DD8E26DB959CF1D0', 'RSA', NULL, '2015-07-02 11:58:41',
        0, NOW(), NOW());

-- ---------------------------------------------------------------------------
-- ② ruth@passbolt.com — inactive (setup not finished) + register token
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO users (id, role_id, username, active, deleted, disabled, created, modified)
SELECT 'feedc0de-0002-4000-8000-000000000002', r.id, 'ruth@passbolt.com', 0, 0, NULL, NOW(), NOW()
FROM roles r WHERE r.name = 'user';

INSERT IGNORE INTO profiles (id, user_id, first_name, last_name, created, modified)
VALUES ('feedc0de-1002-4000-8000-000000000002', 'feedc0de-0002-4000-8000-000000000002',
        'Ruth', 'Teitelbaum', NOW(), NOW());

-- The register token is generated randomly at execution time (a fixed value in
-- this public repository would be a standing unauthenticated entry point via
-- the permitAll /setup endpoints). The SELECT prints the resulting setup URL;
-- it is also correct on a re-run, when INSERT IGNORE keeps the earlier token.
INSERT IGNORE INTO authentication_tokens (id, token, user_id, active, type, data, created, modified)
VALUES ('feedc0de-3001-4000-8000-000000000001', UUID(),
        'feedc0de-0002-4000-8000-000000000002', 1, 'register', NULL, NOW(), NOW());

SELECT CONCAT('/setup/start/feedc0de-0002-4000-8000-000000000002/', token, '.json') AS ruth_setup_url
FROM authentication_tokens WHERE id = 'feedc0de-3001-4000-8000-000000000001';

-- ---------------------------------------------------------------------------
-- ③ sofia@passbolt.com — soft-deleted user (deleted-filtering demo)
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO users (id, role_id, username, active, deleted, disabled, created, modified)
SELECT 'feedc0de-0003-4000-8000-000000000003', r.id, 'sofia@passbolt.com', 1, 1, NULL, NOW(), NOW()
FROM roles r WHERE r.name = 'user';

INSERT IGNORE INTO profiles (id, user_id, first_name, last_name, created, modified)
VALUES ('feedc0de-1003-4000-8000-000000000003', 'feedc0de-0003-4000-8000-000000000003',
        'Sofia', 'Kovalevskaya', NOW(), NOW());

-- ---------------------------------------------------------------------------
-- ④ edith@passbolt.com — active but disabled (disabled-chip / re-enable demo)
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO users (id, role_id, username, active, deleted, disabled, created, modified)
SELECT 'feedc0de-0004-4000-8000-000000000004', r.id, 'edith@passbolt.com', 1, 0, NOW(), NOW(), NOW()
FROM roles r WHERE r.name = 'user';

INSERT IGNORE INTO profiles (id, user_id, first_name, last_name, created, modified)
VALUES ('feedc0de-1004-4000-8000-000000000004', 'feedc0de-0004-4000-8000-000000000004',
        'Edith', 'Clarke', NOW(), NOW());

INSERT IGNORE INTO gpgkeys (id, user_id, armored_key, bits, uid, key_id, fingerprint,
                            type, expires, key_created, deleted, created, modified)
VALUES ('feedc0de-2002-4000-8000-000000000002', 'feedc0de-0004-4000-8000-000000000004',
        '-----BEGIN PGP PUBLIC KEY BLOCK-----

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
=d1r3
-----END PGP PUBLIC KEY BLOCK-----',
        4096, 'Edith Clarke <edith@passbolt.com>', '1D67BAA69E67396C',
        'D5FDE007B7B4B9816ECE25F61D67BAA69E67396C', 'RSA', NULL, '2015-07-03 10:47:27',
        0, NOW(), NOW());

-- ---------------------------------------------------------------------------
-- ⑤ group "Board" — ada is group manager; betty + dame are members.
--    ada/betty ids are resolved by username (rows skipped if they are absent).
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO `groups` (id, name, deleted, created, modified, created_by, modified_by)
SELECT 'feedc0de-4001-4000-8000-000000000001', 'Board', 0, NOW(), NOW(), u.id, u.id
FROM users u WHERE u.username = 'ada@passbolt.com' AND u.deleted = 0;

INSERT IGNORE INTO groups_users (id, group_id, user_id, is_admin, created)
SELECT 'feedc0de-4101-4000-8000-000000000001', 'feedc0de-4001-4000-8000-000000000001', u.id, 1, NOW()
FROM users u WHERE u.username = 'ada@passbolt.com' AND u.deleted = 0;

INSERT IGNORE INTO groups_users (id, group_id, user_id, is_admin, created)
SELECT 'feedc0de-4102-4000-8000-000000000002', 'feedc0de-4001-4000-8000-000000000001', u.id, 0, NOW()
FROM users u WHERE u.username = 'betty@passbolt.com' AND u.deleted = 0;

INSERT IGNORE INTO groups_users (id, group_id, user_id, is_admin, created)
VALUES ('feedc0de-4103-4000-8000-000000000003', 'feedc0de-4001-4000-8000-000000000001',
        'feedc0de-0001-4000-8000-000000000001', 0, NOW());

-- ---------------------------------------------------------------------------
-- ⑥ folders "Ops" > "Servers" in ada's tree (folder rows + OWNER permissions
--    with aco='Folder' + ada's folders_relations rows — the same three-part
--    shape FolderService.createFolder writes; folders without permissions
--    would be invisible to everyone).
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO folders (id, name, created, modified, created_by, modified_by)
SELECT 'feedc0de-5001-4000-8000-000000000001', 'Ops', NOW(), NOW(), u.id, u.id
FROM users u WHERE u.username = 'ada@passbolt.com' AND u.deleted = 0;

INSERT IGNORE INTO folders (id, name, created, modified, created_by, modified_by)
SELECT 'feedc0de-5002-4000-8000-000000000002', 'Servers', NOW(), NOW(), u.id, u.id
FROM users u WHERE u.username = 'ada@passbolt.com' AND u.deleted = 0;

INSERT IGNORE INTO permissions (id, aco, aco_foreign_key, aro, aro_foreign_key, type, created, modified)
SELECT 'feedc0de-5201-4000-8000-000000000001', 'Folder', 'feedc0de-5001-4000-8000-000000000001',
       'User', u.id, 15, NOW(), NOW()
FROM users u WHERE u.username = 'ada@passbolt.com' AND u.deleted = 0;

INSERT IGNORE INTO permissions (id, aco, aco_foreign_key, aro, aro_foreign_key, type, created, modified)
SELECT 'feedc0de-5202-4000-8000-000000000002', 'Folder', 'feedc0de-5002-4000-8000-000000000002',
       'User', u.id, 15, NOW(), NOW()
FROM users u WHERE u.username = 'ada@passbolt.com' AND u.deleted = 0;

INSERT IGNORE INTO folders_relations (id, foreign_model, foreign_id, user_id, folder_parent_id, created, modified)
SELECT 'feedc0de-5101-4000-8000-000000000001', 'Folder', 'feedc0de-5001-4000-8000-000000000001',
       u.id, NULL, NOW(), NOW()
FROM users u WHERE u.username = 'ada@passbolt.com' AND u.deleted = 0;

INSERT IGNORE INTO folders_relations (id, foreign_model, foreign_id, user_id, folder_parent_id, created, modified)
SELECT 'feedc0de-5102-4000-8000-000000000002', 'Folder', 'feedc0de-5002-4000-8000-000000000002',
       u.id, 'feedc0de-5001-4000-8000-000000000001', NOW(), NOW()
FROM users u WHERE u.username = 'ada@passbolt.com' AND u.deleted = 0;

-- ---------------------------------------------------------------------------
-- ⑨ organization_settings — selfRegistration policy (email_domains provider,
--    allowed domain: passbolt.com). property/value shape matches
--    SelfRegistrationService (which reads by property, not property_id);
--    property_id is the same UUID.nameUUIDFromBytes derivation the Java
--    services use for 'organization.setting.selfRegistration'.
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO organization_settings (id, property_id, property, `value`, created, modified, created_by, modified_by)
SELECT 'feedc0de-6001-4000-8000-000000000001', '4baa42c8-2d1c-3e63-aeeb-06ace267a483',
       'selfRegistration',
       '{"provider":"email_domains","data":{"allowed_domains":["passbolt.com"]}}',
       NOW(), NOW(), u.id, u.id
FROM users u WHERE u.username = 'ada@passbolt.com' AND u.deleted = 0;
