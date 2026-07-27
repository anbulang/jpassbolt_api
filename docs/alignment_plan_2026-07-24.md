# JPassbolt → Passbolt CE 对齐执行计划（2026-07-24）

## Context（为什么做）

2026-07-24 的配置层复审（`docs/config_gap_audit_2026-07-24.md`）确认：JPassbolt 配置面骨架无洞、无假广播，旧五大缺口已全部落地；剩余差距集中在「安全硬化旋钮 / 副作用层脱节 / Metadata 兼容硬伤 / 未移植大插件」。

用户拍板本轮对齐范围与三项决策：
- **范围 = P0–P3**（快修 + 两项安全硬化 + Metadata 两项 High + 副作用邮件层）。P4/大插件本轮不做。
- **MFA = totp-only**，Duo/Yubikey 不移植（引入外部依赖违项目约束），仅文档固化定位。
- **JWT access token 默认 = 5 分钟**（对齐 CE，refresh 30 天续期）。

目标产出：每批可独立编译 + `mvn test` 全绿 + 对应契约/单测，最后浏览器 + MailHog 实测。所有改动守铁律（GPG 仅 Bouncy Castle、MySQL `ddl-auto: validate` 不改 schema、DTO 无业务逻辑、新功能带集成测试）。

---

## P0 — 四项快速修复（全 small，建议一次提交）

### 0.1 healthcheck `emailNotificationEnabled` 改读真实值
- 落点：`service/HealthcheckService.java:312`（`application.put("emailNotificationEnabled", false)`，位于 `checkApplication()`）。
- 做法：给 `HealthcheckService`（`@RequiredArgsConstructor`，字段 49-54）**新增 final 字段** `EmailNotificationSettingsService`（Lombok 自动补构造，无需手改构造器），把字面量 `false` 换成实时值。参照同方法已「去硬编码」的 `sslFullBaseUrl`(294)/`registrationClosed`(302-309) 模式。
- **需在实现时确认语义**：读 `passbolt_api_ref` 的 `ApplicationHealthcheckService`（或等价）确定 `emailNotificationEnabled` 的 PHP 真实取值口径，再决定用 `emailNotificationSettingsService.get()` 派生还是复用 MailService 的「发信是否启用」判定。默认倾向：反映本实例是否会真正发通知邮件。
- 复用：`EmailNotificationSettingsService.get()`(:130) / `isEnabled(dotted)`(:172)。
- 测试：`controller/HealthCheckControllerTest.java`（当前 :140 只断言 `body.application` 存在）补 `emailNotificationEnabled` 断言。

### 0.2 经典 GpgAuth Stage1 改用 `encryptSign`
- 落点：`service/AuthService.java:150` `gpgService.encrypt(nonce, ...)` → 改为 `gpgService.encryptSign(nonce, gpgKey.getArmoredKey())`；更新 :149 注释。
- 依据：签名方法已存在（`GpgService.java:15` / `GpgServiceImpl.java:150`），JWT 路径 `JwtAuthService.login` 已在用（:200）。签名一致，纯一行替换。
- 测试：`controller/AuthControllerTest.java` 在 `testFullLoginFlow`(:142) 把 `gpgService.decrypt`(:163) 补/换成 `decryptVerify`（decrypt 单独仍能解签名载荷，故不加验证抓不到回归）。复用 `util/GpgTestHelper.java`。

### 0.3 账户恢复邮件补 `send.user.recover` 门控
- 落点：`service/RecoverService.java:160`（active-user 分支 `if (TOKEN_TYPE_RECOVER...)` :159）调用 `mailService.sendRecoverEmail(...)` 前加门控。这是全仓唯一遗留的 direct send（`MailService.java:118-122` 注释确认）。
- 做法：给 `RecoverService` 注入 `EmailNotificationSettingsService`，`if (settings.isEnabled("send.user.recover")) { mailService.sendRecoverEmail(...); }`（键默认 true，见 `EmailNotificationSettingsService.java:115`）。门控放 RecoverService（保持 MailService 纯粹，与已用 redactor 门控的 register 分支对称）。
- 测试：`controller/RecoverControllerTest.java` 加「关闭该键则不发」用例（范式：`RecoverEnumerationProtectionTest` 的 `@TestPropertySource`；本项走设置持久化，用 mock/断言 MailService 未被调用）。

### 0.4 `accountRecoveryRequestHelp` 默认 false→true
- 落点**两处都要改**（否则 yml 覆盖码默认）：
  - `config/SettingsProperties.java:188` `defaults.put("accountRecoveryRequestHelp", false)` → `true`，更新 :187 注释。
  - `application.yml` 的 `jpassbolt.settings.plugins.accountRecoveryRequestHelp: false` → `true`。
- 安全性：该 flag 是纯广告布尔，全仓无其它消费方、不注册任何端点（已核）；置 true 只让扩展显示恢复帮助入口，无后端副作用。已在 `SettingsService.java:58` 的 `PUBLIC_ENABLED_FLAGS` 中。
- 测试：`controller/SettingsControllerContractTest.java`（认证态 :36 + 匿名态 :52）与 `SettingsControllerTest.java` 补 `plugins.accountRecoveryRequestHelp.enabled == true` 断言。

---

## P1 — 两项安全硬化（small）

### 1.1 CSP 响应头（API + 骨架页双份）
- API 侧：`config/SecurityConfig.java:51-60` 的 `.headers(...)` DSL 内、紧接 `.referrerPolicy`(:53-54) 后加 `.contentSecurityPolicy(csp -> csp.policyDirectives("<policy>"))`。当前未 `defaultsDisabled()`，属「只增」不破坏 nosniff/frame-options。
- 骨架页侧：`config/SecurityHeaders.java` 加 `CONTENT_SECURITY_POLICY` 常量 + 在 `applyTo(HttpServletResponse)`(:82) 内 `response.setHeader(...)`（骨架页是 filter-less 第二 context，`SkeletonPageConfig.java:158` 调 `applyTo`）。
- **关键约束**：骨架页是 chrome-extension iframe 宿主（`SecurityHeaders.java:44-54` 注释）。CSP 若含 `frame-src`/`child-src` **必须放行 `chrome-extension:`**，否则扩展注入的 `app.html` iframe 被 CSP 拦掉。默认策略需保守但不破坏扩展；用 `${PASSBOLT_SECURITY_CSP:<default>}` 可覆盖/关闭（对齐 CE 的 `security.csp`）。
- **实现时必做浏览器实测**：加载扩展、确认 iframe 注入与所有内联资源不被 CSP 打断，再定稿指令集。
- 测试：`controller/SecurityHeadersTest.java`（:36-49 现断言五头）加 CSP 断言；`config/SkeletonPageServletTest.java` 加骨架页 CSP 断言。

### 1.2 反代真实 IP 解析（opt-in，默认关以对齐 CE `proxies.active=false`）
- 落点：`application.yml` `server:` 段（:1-11）加 `forward-headers-strategy: ${SERVER_FORWARD_HEADERS_STRATEGY:none}`。默认 `none`（安全、对齐 CE 默认关），生产反代后设 `framework`。
- 生效后 `RecoverController.java:143` 的 `httpRequest.getRemoteAddr()` 自动返回转发真实 IP（下游恢复完成邮件 `clientIp` 即真实），**无需改控制器**。`util/HttpRequestSecurity.java` 现只解析 X-Forwarded-Proto/Host（不碰 IP），不受影响。
- 文档：在部署文档标注「反代后须设 `SERVER_FORWARD_HEADERS_STRATEGY=framework` 且仅信任受控代理」。
- 测试：可选集成测试设该属性断言 X-Forwarded-For 被采信（成本较高，非阻塞）。

---

## P2 — Metadata 兼容硬伤

### 2.1 `getting-started` + `setup/settings` 下发端点（medium）
在 `controller/MetadataSettingsController.java` 加两只读端点，照现有 `getKeysSettings()`(:79-87) + `requireAdmin`(:168) 形状：
- `GET /metadata/settings/getting-started` → DTO `{ "enabled": <bool> }`。`enabled=true` 当且仅当：配置 `enableForExistingInstances=true` 且 无 `metadataTypes`/`metadataKeys` 组织设置行 且 `metadataKeys` 全量 count==0 且 `metadataPrivateKeys` 全量 count==0。参考 PHP `MetadataSettingsGetStartedService::get()`。
- `GET /metadata/setup/settings` → DTO `{ "enable_encrypted_metadata_on_install": <bool> }`。`true` 当且仅当：配置 `enableForNewInstances=true` 且 users 表恰好 1 个用户且其 active+admin（fresh-install 探测）。参考 PHP `MetadataSettingsSetupService::get()`。
- 新配置：`enableForNewInstances` / `enableForExistingInstances`，加到 `application.yml` `jpassbolt.metadata.*`（CE 默认 true），用 `@Value` 或扩 `SettingsProperties`。
- 复用：`organizationSettingRepository.findByProperty(...)` + 常量 `MetadataTypesSettingsService.ORG_SETTING_PROPERTY`(:53)/`MetadataKeysSettingsService.ORG_SETTING_PROPERTY`(:53)（只判存在，不必反序列化）；全量 count 走 `metadataKeyRepository.count()` / `metadataPrivateKeyRepository.count()`（非 active-only 的 `countBy...`）；`UserService.isAdmin` + `UserRepository`。
- **实现时确认**：`setup/settings` 在 setup/recover 流程被调，PHP 是否 admin-only（`getting-started` 确为 admin-only）；若 setup 上下文调用者非 admin，需放宽鉴权。定夺前读 PHP 控制器 `assert*` 段。
- 测试：`controller/MetadataSettingsControllerContractTest.java` 加两端点契约。

### 2.2 关零知识时 server 私钥校验/落库（large）
- 落点：`service/MetadataKeysSettingsService.java:89-111` `setKeysSettings(request, userId)`。当前只读 `allowUsageOfPersonalKeys`/`zeroKnowledgeKeyShare` 两布尔，对 payload `metadata_private_keys` 零引用。
- 做法：在 `upsert`(:109) 前复刻 PHP `MetadataKeysSettingsSetService`：
  - `isDisablingZeroKnowledge`：DB 现已 user-friendly→false；payload 无 `zero_knowledge_key_share`→false；否则 `!payload.zeroKnowledgeKeyShare`。
  - `shouldCreateMetadataPrivateKey`：当 非删除 `metadataKeys count>0` 且切 user-friendly 模式时，统计 `metadata_private_keys WHERE user_id IS NULL`；若 0 则要求 payload `metadata_private_keys` 非空，否则 **400**「server metadata private key is required」；满足则落 server 副本。
- 复用：**落库能力已存在** `MetadataKeyService.createPrivateKeys(entries, userId)`(:402)——`validatePrivateKeyEntry`(:491) 允许 `user_id==null`（server 副本），`assertPrivateKeyUnique`(:518) 走 `findByMetadataKeyIdAndUserIdIsNull`。只需在 `setKeysSettings` 内加判定并调它。
- DTO：`KeysSettingsUpdate` 需能携带 `metadata_private_keys` 数组（DTO 仅传输，无逻辑）。
- 测试：`controller/MetadataSettingsControllerContractTest.java` + 新服务级测试（禁用 ZK 且无 server 私钥→400；带私钥→落库 user_id=null）。

---

## P3 — 副作用邮件层

统一遵循项目 redactor 范式（以 `service/email/redactor/ShareEmailRedactor.java` 为范本）：`@Component`+`@RequiredArgsConstructor`；监听方法 `@Async("mailExecutor")`(`config/AsyncConfig.java:29`)+`@TransactionalEventListener(AFTER_COMMIT)`；**方法首行 `if (!settings.isEnabled(SETTING_PATH)) return;`**；`RecipientResolver` 解析并 `.filter` 排除 actor → `NotificationDelivery.deliver`。事件为不可变 record，在对应 service **事务内** `eventPublisher.publishEvent(...)`（删/共享类须在删除/变更**前快照**收件人与字段）。

### 3.1 `send_folder_*` 四键 + 4 redactor（medium）
- 键：`service/EmailNotificationSettingsService.java` `buildDefaults()`(:78-118) 加 `send_folder_create`(false)/`send_folder_update`(true)/`send_folder_delete`(true)/`send_folder_share`(true)（对照 PHP `FolderNotificationSettingsDefinition`）。
- 事件 + 发布点（`FolderService` 需新注入 `ApplicationEventPublisher`）：`createFolder`(:181 `return saved`)、`updateFolder`(:221)、`deleteFolder`(:285，删前快照)、以及 folder 共享在 `PermissionService.shareFolder`(:386-449，末尾 ~:448，已算出 `added`/`removed`)。
- 新 `RecipientResolver` 方法 `resolveUsersWithAccessToFolder(folderId)`（当前只有 resource 版 :56）——内部走 `PermissionService` 的 folder ARO 展开（`shareFolder` :391/400 已用等价逻辑）。
- 新 4 redactor + 模板 `templates/email/lu/folder_*.html` + `email.folder.*` 键（en/zh）。
- 测试：新增 Folder redactor 测试（范式 `ShareEmailRedactorTest`）+ 可选 1 个 GreenMail e2e。

### 3.2 `send_password_create/update/delete` 发信方（medium）
- 现状：resource 域只有 `ShareEmailRedactor`/`CommentAddEmailRedactor`，三键有默认值无监听方。
- 事件 + 发布点（`ResourceService` 需新注入 publisher）：`createResource`(:160)、`updateResource`(:332)、`deleteResource`(:357，级联前快照)。默认 create=false、update/delete=true。
- redactor：`ResourceCreate/Update/DeleteEmailRedactor`，门控 `send.password.create|update|delete`，收件人 `RecipientResolver.resolveUsersWithAccessToResource`(:56) 排除 actor。
- 模板 + 键 + 测试同 3.1。

---

## 附带（低成本，随本轮一起）

- **JWT 默认 5 分钟**：`application.yml` `jwt.expiration: 3600000` → `300000`，更新注释说明对齐 CE。检查是否有 JWT 过期相关测试需同步（`JwtAuthControllerContractTest` 等）。
- **totp-only 文档**：在 `CLAUDE.md`「铁律」或「当前状态」补一条：MFA 定位 totp-only，Duo/Yubikey 有意不移植（外部依赖约束，CE 默认二者皆 false）。
- **修文档漂移**：`CLAUDE.md` 把「resource-types 写端点（v5-only）待实现」更正为已实现（PUT restore + DELETE 软删 + 契约测试已在）。

---

## 验证（Verification）

1. **单元/集成**：每批后在 `jpassbolt_api/` 跑 `mvn test`（H2 内存库，全绿；surefire 已配 byte-buddy/`--add-opens`，勿绕过 Maven）。新测试按各项列出的类补齐；保持 `OpenApiComplianceTest`/各 `*ContractTest` 通过。
2. **Metadata 端点手测**：`mvn spring-boot:run -Dspring-boot.run.arguments=--spring.profiles.active=local` 后 `curl --noproxy '*' -H "Authorization: Bearer <jwt>" http://127.0.0.1:8090/api/metadata/settings/getting-started.json` 等，核对 DTO 形状。
3. **邮件副作用 e2e**：`local` profile（MailHog）下建/改/删/共享 folder 与 resource，MailHog 收信核对；关闭对应 `send_*` 键则不发。
4. **CSP + 反代浏览器实测**（P1 阻塞项）：加载扩展 `extension/dist`，确认骨架页 iframe 注入正常、无 CSP 报错；设 `SERVER_FORWARD_HEADERS_STRATEGY=framework` + 伪造 `X-Forwarded-For` 核对恢复邮件 `clientIp`。
5. **GpgAuth 回归**：`AuthControllerTest` 全流程 + 扩展经典登录一次，确认签名挑战被客户端接受。

## 执行顺序建议

P0（一次提交）→ P1（含浏览器实测定稿 CSP）→ P2.1 端点 → P2.2 ZK 私钥 → P3.1 folder → P3.2 resource。每批独立 `mvn test` 绿 + 对抗式自审后再进下一批。附带三项可并入 P0 提交。
