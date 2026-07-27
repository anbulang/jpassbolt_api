# JPassbolt vs Passbolt CE 配置层差距审计(2026-07-24 复审)

## 1. 执行摘要

本次复审对照官方 Passbolt CE(edition=ce,版本 5.7.2)只读参考,逐行核验了 JPassbolt 后端 16 个配置领域。总体结论:**配置面骨架无洞、无"假广播/徒有端点无行为"的欺骗性缺陷**,是一次高保真移植。相较 2026-06-27 旧审计(`gap_audit_vs_passbolt_ce.md`)所列的五大缺口——事件驱动邮件、自助注册、SMTP 设置页、操作审计日志、管理员重置他人 MFA——经本次亲验**全部确认已落地且行为忠实**(见第 4 节),旧审计的悲观判定多处已被证伪(尤其"操作审计完全空白""25 通知键纯摆设"应正式勾销)。

当前真正的配置差距集中在三类:①**整块未移植的插件**(PasswordExpiry、SecretRevisions、Rbacs、Mobile/Desktop 配对、EmailDigest、Reports、Duo/Yubikey MFA provider)——但这些 flag 均被**诚实置 false**,客户端不会误请求死端点;②**安全硬化旋钮缺失**(CSP 头、反代真实 IP、SMTP/Metadata 的 editionDisabled 锁、自定义 SSL 四件套、管理员身份匿名化)——多为部署加固而非默认不安全;③**副作用层与配置面脱节**(通知开关有键无发信方、healthcheck 硬编码值与真实功能矛盾、JWT/MFA 限速参数不可配)。可一行修复的高性价比项有:`accountRecoveryRequestHelp` 默认翻 true、healthcheck `emailNotificationEnabled` 改读真实设置、经典 GpgAuth Stage1 改用已存在的 `encryptSign`、`send_user_recover` 直发邮件补门控。

---

## 2. 配置差距总表(CONFIRMED_MISSING / PARTIAL,按严重度排序)

> 说明:同一底层缺口在多个 settings flag 域被观察到的(rbacs / passwordExpiry / mobile / secretRevisions / import-export),已合并为单行,避免重复计分。

| 配置领域 | 官方 CE 有 | JPassbolt 现状 | 判定 | 严重度 | 工作量 |
|---|---|---|---|---|---|
| Metadata 关 ZK 时 server private key 校验/落库 | 关零知识须校验并落 server 私钥副本 | 字段被静默丢弃,切模式后新用户入组分发链会断 | CONFIRMED_MISSING | High | large |
| Metadata `getting-started` + `setup/settings` 下发端点 | 两只读端点驱动引导/setup 流程 | 全缺,官方插件调用即 404 | CONFIRMED_MISSING | High | medium |
| Healthcheck `emailNotificationEnabled` | 读真实 send 设置 | 硬编码 false,与已落地的 17 redactor 邮件系统直接矛盾 | CONFIRMED_MISSING | Medium | small |
| 经典 GpgAuth Stage1 挑战签名 | encrypt+sign(服务器签名) | 仅 encrypt 未签(项目已有 encryptSign,JWT 路径已用) | CONFIRMED_MISSING | Medium | small |
| CSP 响应头 | `passbolt.security.csp` 默认 true | 完全缺失(骨架页是插件 iframe 宿主) | CONFIRMED_MISSING | Medium | small |
| 反代真实 IP 解析 | `proxies.active`/`trustedProxies` | 无 forward-headers,直取 getRemoteAddr(),生产来源 IP 全失真 | CONFIRMED_MISSING | Medium | small |
| username 大小写策略 | 默认 lowerCase=false / 不敏感 | 强制 toLowerCase 且不可配,跨实例迁移踩坑 | CONFIRMED_MISSING | Medium | medium |
| SMTP `endpointsDisabled` 硬化锁 | 中间件可锁死 /smtp/* | 无拦截,healthcheck 硬编码 false | CONFIRMED_MISSING | Medium | small |
| SMTP 自定义 SSL 四件套 | verifyPeer/PeerName/allowSelfSigned/cafile | 全无,私有 CA/自签无法接入(缺可配置性非默认降级) | CONFIRMED_MISSING | Medium | medium |
| Metadata `editionDisabled` 部署锁 | 中间件冻结 v5 设置写 | 无等价物,仅运行时 admin 门 | CONFIRMED_MISSING | Medium | medium |
| 邮件 `send_folder_*` 四键 | create(false)/update/delete/share(true) | 键+发信双缺,Folders 已实现但零通知,官方管理页缺块 | CONFIRMED_MISSING | Medium | medium |
| 邮件 `send_password_create/update/delete` | 资源变更通知有权限者 | 键在但无 redactor/事件,开关假生效 | CONFIRMED_MISSING | Medium | medium |
| 账户恢复邮件绕过 `send_user_recover` | 受开关门控 | 唯一遗留 direct send,关闭后照发 | CONFIRMED_MISSING | Medium | small |
| PasswordExpiry 插件(整簇) | 3 settings 端点+过期副作用+cron+5 邮件 | 整块缺失(仅 Resource.expired 列只读透传);flag 诚实 false | CONFIRMED_MISSING | Medium | large |
| SecretRevisions 插件(整组) | settings CRUD+修订查询+editionDisabled 锁 | 整组缺失(5.7.0 起 CE 默认带,beta);flag 诚实 false | CONFIRMED_MISSING | Medium | large |
| Log `entities_history` 第三张审计表 | 实体 CRUD 变更留痕 | 未写入(仅 action_logs+secret_accesses);消费方仅 EE | CONFIRMED_MISSING | Medium | medium |
| Rbacs 插件(整块) | /rbacs/me 等 4 端点+2 实体+种子 | 整块缺失,flag 诚实 false 降级全量 UI | CONFIRMED_MISSING | Medium | large |
| Mobile/Desktop 配对(/mobile/transfers) | 扫码配对+mobile_transfer token | 整块缺失,手机/桌面 App 不可用;flag 诚实 false | CONFIRMED_MISSING | Medium | large |
| 服务端 export/import 端点 | config.format=[kdbx,csv] 服务端端点 | 做在浏览器插件本地,路径不兼容官方扩展 | CONFIRMED_MISSING | Medium | large |
| POST /import/resources 别名 | 委派资源创建 | 缺失,官方插件导入逐条打此端点会 404 | CONFIRMED_MISSING | Medium | small |
| GET themes 响应形状 | 实体数组 [{id,name,preview}] | 返回名字字符串数组+混入当前值,styleguide 解析失败 | CONFIRMED_MISSING | Medium | medium |
| MFA Duo provider(整套) | Duo Universal v4 全流程+凭据配置 | 整块缺失,setOrgSettings 仅白名单 totp | CONFIRMED_MISSING | Medium | large |
| MFA Yubikey provider(整套) | Yubico OTP 云校验+凭据配置 | 整块缺失 | CONFIRMED_MISSING | Medium | large |
| JWT access token 默认时长 | CE 默认 5 分钟 | 默认 1 小时(可配,仅默认偏松 12 倍) | PARTIAL | Medium | small |
| MFA maxAttempts 限速 | 默认 4,可 env,action_logs 持久 | 硬编码 4 + 单节点内存态,重启/横扩可绕过 | PARTIAL | Medium | medium |

*(以下为 Low 级,详见第 3 节 Low 节;此处从略以控制表长)*

---

## 3. 按严重度分组的详述

### 3.1 High(2 项,建议优先)

**H-1 Metadata 关闭零知识时 server private key 校验/落库缺失**
- 官方配置项:`MetadataKeysSettingsSetService` 在 `isDisablingZeroKnowledge` 且服务端无 server key 时,缺 `metadata_private_keys` 抛 400,有则逐条落库(user_id=null 的 server 副本)。
- 缺口本质:JPassbolt `setKeysSettings` 只读两布尔(personal/ZK)并 serialize,对 payload 中的 `metadata_private_keys` 零引用,字段被静默丢弃。
- 影响:切换到 user-friendly 模式后服务端无共享私钥副本,新用户入组的元数据密钥自动分发链路会断——属功能正确性缺陷,非纯配置。
- 修复:移植 `isDisablingZeroKnowledge` + `shouldCreateMetadataPrivateKey` 逻辑,依赖 MetadataKeyService 私钥创建路径。**工作量 large。**

**H-2 Metadata `getting-started` 与 `setup/settings` 下发端点缺失**
- 官方配置项:`GET /metadata/settings/getting-started`(管理员引导态)、`GET /metadata/setup/settings`(setup/recover 流程聚合)。
- 缺口本质:MetadataSettingsController 只有 types/keys settings,这两路由全缺。
- 影响:官方插件在 v5 引导页与 setup/recover 流程调用,缺失即 404,直接违背"与官方插件完全兼容"目标。
- 修复:在 MetadataSettingsController 增两只读端点。**工作量 medium。**

### 3.2 Medium(建议按下列顺序处理)

**小改高回报组(effort=small):**
- **healthcheck `emailNotificationEnabled` 硬编码 false**:整份报告唯一"报告与真实功能直接矛盾"的字段——17 个 redactor + EmailNotificationSettingsService 已完整运行,却恒报未启用,误导管理员。改为读真实 send 设置(任一 send.* 为 false 则 false),与 smtpSettings/registrationClosed 已采用的"读真实服务状态"一致。
- **经典 GpgAuth Stage1 未签名**:`AuthService.loginStage1` 用纯 encrypt,官方经典路径 encrypt+sign;项目已有 `encryptSign` 且 JWT 路径在用,换一行调用+补测试即可,统一服务器身份保证。
- **CSP 头缺失**:骨架页是浏览器直接渲染、插件 iframe 宿主的 HTML,缺 CSP 即少一道注入防线。给 SecurityConfig.headers 增 `contentSecurityPolicy` DSL + SecurityHeaders.applyTo 同步补,挂 `${PASSBOLT_SECURITY_CSP:true}`。
- **反代真实 IP 解析**:生产必在反代后,否则通知邮件/未来审计的来源 IP 全是代理 IP。接线 Spring `server.forward-headers-strategy` + RemoteIpValve `trustedProxies`,默认关(对齐 CE active=false)。
- **SMTP `endpointsDisabled` 硬化锁**:官方硬化指南明确要求,拿到管理员会话即可把 SMTP 指向自建服务器截获恢复/邀请邮件链接。加 `jpassbolt.security.smtp-settings.endpoints-disabled`,为真时写端点 403,healthcheck 如实上报。healthcheck 里连位置(硬编码 false)都预留了,补起来最直接。
- **`send_user_recover` 直发绕过开关**:`MailService.sendRecoverEmail` 是唯一遗留 direct send,管理员关闭开关后邮件照发。入口加 `isEnabled("send.user.recover")` 门控。
- **POST /import/resources 别名**:加一条 `@PostMapping("/import/resources.json")` 委派现有 ResourcesController.add,配合把 import flag 翻 true、输出 config.format=[kdbx,csv]。

**中改组(effort=medium):**
- **username 大小写不可配**:引入 `jpassbolt.security.username.lower-case`(默认 false 对齐 CE),仅 true 时归一,否则查询侧做不敏感比较。从含大写邮箱的官方实例迁移存量会错位。
- **SMTP 自定义 SSL 四件套**:企业私有 CA/自签 SMTP 目前只能改 JDK 全局 cacerts。增 `jpassbolt.plugins.smtp-settings.security.*`,buildSender 设 `mail.smtp.ssl.trust`/`checkserveridentity`,healthcheck customSslOptions 反映真实。注:JavaMail starttls 默认已校验证书,缺的是可配置性与可见性而非默认降级。
- **Metadata `editionDisabled` 部署锁**:被攻陷管理员可无阻降级 v5→v4 泄露明文元数据。加 flag + HandlerInterceptor 拦 /metadata/types|keys/settings 写与 /metadata/keys 写。
- **邮件 `send_folder_*` 四键**:buildDefaults() 补 4 键(create 默认 false、其余 true),FolderService CUD/share 发布事件 + 新增 4 个 Folder*Redactor。这是本域唯一会导致官方管理页 UI 缺块的项(Folders 功能本身已实现)。
- **邮件 `send_password_create/update/delete`**:三键已在 DEFAULTS 但无消费方(开关假生效)。ResourceService CUD 路径发布事件 + 三 redactor,收件人经 RecipientResolver 解析为有权限 ARO。
- **`entities_history` 第三张审计表**:缓解因素强——CE 本身无 entities_history 读端点(Activity 读侧是 EE),唯一消费方在 EE。补时须遵铁律#2 原样复刻官方表结构(仅 created 列,不继承 BaseEntity)。
- **GET themes 响应形状**:改返回实体数组 [{id,name,preview}],id 用 UUIDv5(namespace `theme.id.<name>`),当前选中主题由 GET /account/settings.json 承载。因项目已拍板托管官方插件 UI,这是主题侧唯一值得优先处理的一条。

**大改组(effort=large,建议排期而非立即):**
- **PasswordExpiry 整簇移植**:3 settings 端点 → 副作用(他人改密/权限回收/删用户自动标记过期)→ cron(@Scheduled)+ 5 类邮件 redactor。Schema 兼容位 Resource.expired 列已就绪,补齐无需动表。分阶段:先 settings 端点(客户端过期管理页依赖)。
- **SecretRevisions 整组移植**:建 secret_revisions 表 + 4 端点 + SecretRevisionsSettingsMiddleware(含 editionDisabled 锁)+ 密文覆盖路径挂修订写钩子。5.7.0 起 CE 默认带但处 beta 需组织显式激活,当前影响可控。
- **Rbacs 三件套**:4 端点(/rbacs/me 允许未认证)+ Rbac/UiAction 实体(表结构已在参考 SQL 备好,20 项 UiAction + 三控制函数)+ 默认 Allow 种子 + 插件侧 /rbacs/me 消费门控。缺失只是"管理员无法收窄功能面",不产生开箱行为偏差。
- **Mobile/Desktop 配对**:MobileTransferController + AuthenticationToken TYPE_MOBILE_TRANSFER + QR 分页传输。
- **服务端 export/import**:若坚持自建浏览器插件扩展则维持 false 是诚实选择;若要兼容官方扩展需实现服务端端点。
- **MFA Duo/Yubikey provider**:两者均需引入外部依赖(Duo Universal SDK / Yubico 云 API 外呼),与项目"少外部依赖""GPG 仅 Bouncy Castle"约束张力大。CE env 默认二者皆 false,totp 是绝对主流。**务实建议:文档明示 totp-only 定位**,而非强行移植。

**PARTIAL(能力在,仅默认/参数偏):**
- **JWT access token 默认 1h vs 官方 5min**:能力完整(可配+签发+校验),仅默认偏松 12 倍。评估改 `application.yml` 为 300000;refresh token 已 30 天可覆盖续期,风险可控。若因业务保留 1h,应在注释记录明确决策依据而非"既有值"。
- **MFA maxAttempts 硬编码 + 单节点内存态**:数值 4 同 CE 但常量不可配,ConcurrentHashMap 重启即清、多实例不共享,水平扩展可绕过锁。改 @Value 可配 + 失败态落 DB/Redis(可复用已落地的 action_logs 表)。

### 3.3 Low(收尾/完整性,可批量收口)

- **settings**:`accountRecoveryRequestHelp` 默认 false vs CE 硬编码 true——**唯一一行可修、消除语义反转的偏差**,RecoverController 已在,置 true 无副作用,强烈建议直接改;`reports`/`secretRevisions` flag 诚实 false。
- **security**:`ssl.force`/`smtp.endpointsDisabled`/`metadata.editionDisabled` healthcheck 三处**硬编码上报**(215/290/295 行,恒 false/false/true),照官方手册核查会被误导——应改读真实配置;`anonymiseAdministratorIdentity`(通知邮件暴露管理员真实身份)、`obfuscateFields.placeholder`(无通用脱敏层)、`userAgent/userIp` 无关闭开关、`setHeaders/cookies.secure/getLogout` 恒安全值不可配、`checkDomainMismatch/fullBaseUrlEnforce` 无告警——均安全侧,风险低。
- **gpg**:服务器密钥指纹无独立期望配置项,healthcheck `gpgKeyPublicFingerprint` 仅校 `length()==40`(连 hex 都不校)恒真,徒有其名;BC 单文件直载,实际错配风险低于官方 gnupg,属部署防呆增强。
- **auth**:缺通用 `tokenExpiry` 回退键('3 days');键名单位(days/minutes/hours/毫秒)与官方 `PASSBOLT_AUTH_*_EXPIRY` 自由时长串不互通,官方迁移脚本环境变量不能直接套用。
- **邮件**:`send_admin_user_setup_completed`/`recover_abort`/`group_delete`/`group_manager_requestAddUser` 四键有键无消费方(其中 group_delete 影响面最大);`send_password_expire` 随 PasswordExpiry 整体缺;`purify_subject` 语义空转(当前主题由服务端模板生成,注入面小)。
- **SelfRegistration**:enabled flag 不闸门运行时端点(flag 关但 DB 行存在时仍放行,CE 默认恒启用故影响极小);healthcheck provider 返原始枚举串而非人类可读映射。
- **PasswordPolicies**:`source` 覆盖链(file/env/legacy)未移植,恒为 default,组织无法把默认生成器改成 passphrase;flag 关停不联动端点存活(flag=false 时仍 200)。
- **Metadata**:4 个变更通知邮件 redactor 缺失、5 项专项 healthcheck 缺失、`enableForNew/ExistingInstances` 配置面缺失(生产实例无 metadataTypes 行时永远回退纯 v4)、settings.json 未下发 `isInBeta/autoSetupClientSide` whiteList、`defaultPaginationLimit` 硬编码(值正确 20)、session-key 更新/删除路由单复数与 PHP 不一致(按 OpenAPI 单数实现,官方复数请求 {id} 会 404)。
- **UserKeyPolicies**:`source` 恒 default;不识别官方 `PASSBOLT_PLUGINS_USER_KEY_POLICIES_*` 环境变量名(沿用官方部署脚本会被静默忽略回落 ECC)。
- **Locale/AccountSettings**:主题集 2/4 缺 solarized_light/dark(收窄依据随 SPA 退役失效);无 `?locale=` 每请求解析;setup/recover 不落盘 payload locale(首封邮件按组织默认 zh-CN);主题 400 文案两条分支均与官方不符(空值/非法值)。
- **MFA**:totp.secretLength 硬编码 32(恰为 PHP 默认);`GET /mfa/setup/select` 缺失(totp-only 下无消费方)。
- **Log**:过滤链早期拒绝(401/302)不落 action_logs(已注释记录,暴力登录探测在审计中不可见);file/syslog 外部转发引擎(SIEM)缺失(CE 默认亦关闭)。
- **Healthcheck**:`GET /healthcheck/error` 测试端点及开关缺失(纯诊断默认关);smtpSettings 两恒 false 项是上游 SMTP 高级配置缺口的映射。
- **其余**:import/export flag=false 不广告 config.format(注:CE 本身也仅 Import 广告 format,Export 只广告 enabled+version);resource-types 写端点未按 `passbolt.v5.enabled` 门控(项目定位 v5 全开,行为等价官方 v5=true 实例)。

---

## 4. 已澄清 / 已修复(勿再当缺口追)

**旧审计(2026-06-27)五大缺口经亲验全部确认已落地:**

1. **事件驱动邮件通知**:17 个 redactor 类 + EmailNotificationSettingsService(GET/POST /settings/emails/notifications.json 真实 send 设置)+ MailService 均在;`isEnabled()` 是真实门控 seam,12 个逻辑事件的 redactor 全部先 isEnabled 再发信,show_* 内容门控接线。旧审计"25 键纯摆设"的核心指控**已实质反转**。
2. **自助注册**:settings GET/POST|PUT/DELETE + dry-run 四级闸门 + 访客 register GET/POST + email_domains provider + settings.json 访客只泄 enabled 不泄 version + healthcheck 三键**全部落地**,是对齐度最高的域之一。旧审计"整块缺失"结论**已彻底作废**。
3. **SMTP 设置页**:三端点齐全(GET + POST|PUT 双动词 + 测试邮件)、admin-only 403 空串体、配置以服务器 OpenPGP 公钥加密存 organization_settings、运行时 DB 优先注入不可解密时拒发、测试邮件三形态凭据脱敏、healthcheck 真实探测——**均已亲验代码行**。
4. **管理员/自助重置他人 MFA**:`DELETE /mfa/setup/{userId}.json` 完整实现(admin-or-self 门控,403→400 uuid→400 不存在,PHP 顺序对齐;删 account_settings mfa 行 + 失活 token + 发 reset 通知邮件)。
5. **操作审计日志**:ActionLogProperties/ActionLogInterceptor/ActionLogService/SecretAccessService **四件均真实落地并接线**(WebMvcConfig 注册全量拦截、Secret/Resource 两 view 挂 secret_accesses 钩子),blacklist/version/enabled 三键对齐官方,2xx→success 状态映射主动修正了 PHP `==200` 直译陷阱,三表守住铁律#2。旧审计"操作审计完全空白/合规能力为零"**已被彻底证伪,应正式勾销**。

**compare 初判被复审纠正的误判:**
- **UserKeyPolicies 端点整体**:旧审计标"完全无端点/服务/校验",实为 **ACTUALLY_IMPLEMENTED**(双 GET 路由 + guest register-token 断言 + rsa/curve 组合校验 + 25 测试)。
- **Log 审计**:compare 曾标 high gap,实为**已完成态**,应移出 gap 清单。
- **Locale 两项**(POST /locale/settings.json 组织语言写端点、访客 settings.json plugins 白名单):compare 标 missing,实为 **ACTUALLY_IMPLEMENTED**(闭环已亲验)。
- **InFormIntegration**:旧审计缺口确已闭合(SettingsProperties=true + guest 白名单)。
- **PasswordPolicies 写端点**:旧审计列为缺口,实际 CE 本身无 set/write 控制器(source=db 持久化是 EE-only),JPassbolt 不移植**正确**,应改判 NOT_APPLICABLE。
- **resource-types 写端点(v5-only)**:CLAUDE.md 仍列为"待实现",但代码已实现(PUT restore 严格体校验 + DELETE 软删守卫 + 契约测试),**文档应更新**。

---

## 5. 不适用 CE 的项(EE-only / 架构差异,不应视为缺口)

- **emailDigest 无 cron 调度器**:逐封即时发送功能上等价,属架构差异(无 email_queue 持久队列)。
- **app.locale 默认 zh-CN**:有意的产品本地化决策,admin 配置的组织 locale 仍优先。
- **gpg encryptValidate / acceptRevokedKeyUnhashedIssuerSubPacket**:BC 后端失败模型与吊销判定语义与 gnupg/openpgpjs 根本不同,无语义落点;gpg backend/keyring/putenv 因纯 BC 内存密钥环天然不适用(守铁律#1)。
- **csrfProtection.active + unlockedActions**:无状态 Bearer JWT 架构无会话 cookie,唯一 cookie 凭证 refresh_token 由 SameSite=Lax + HttpOnly + Secure 兜底,架构性不适用。
- **MFA duoVerifySubscriber / sortProvidersByLastUsage / secretRevisions.editionDisabled / displayNonWebUserWarning / selenium.active**:Duo 未实现、单 provider 无排序语义、依附未实现端点、无 CLI/web 双上下文、无 Selenium 后门(不存在该后门即最安全态)。
- **security ssl 域三布尔 + fullBaseUrlReachable/latestVersion/remoteVersion**:JPassbolt 有意"绝不发出站 HTTP",证书校验交反代;有 info 说明的设计取舍。
- **WebInstaller 图形化安装向导**:以 application.yml 静态配置替代,建议在部署文档固化该决策。
- **Log selenium 预览端点 / SMTP file 源**:CE 默认关闭 / 无 passbolt.php 等价物,有记录的合理省略。
- **allowed_domains MX/DNS 深检**:CE 本身默认关闭。
- **UserKeyPolicies / MFA 管理员写端点**:CE 本为只读(setMethods(['GET'])),JPassbolt 同为只读属对齐。

---

## 6. 优先级建议(接下来若继续对齐 CE)

按"影响 × 成本"排序,建议落地顺序:

1. **四项一行/小改高回报项打包先做**(effort 均 small,合计半天量级):healthcheck `emailNotificationEnabled` 改读真实设置(消除唯一的报告-实态矛盾)+ 经典 GpgAuth Stage1 换 `encryptSign`(统一服务器身份保证)+ `send_user_recover` 补门控(消除唯一遗留 direct send)+ `accountRecoveryRequestHelp` 默认翻 true(消除唯一语义反转)。**理由:全部零/极低风险、无新依赖、直接消除"报告或行为与配置面矛盾"的诚信瑕疵。**

2. **两项生产安全硬化**:CSP 头 + 反代真实 IP 解析(effort 均 small,Spring 均有现成机制)。**理由:骨架页是插件 iframe 宿主、生产必在反代后,二者是当前最有实际后果的纯安全缺失,且成本极低。**

3. **Metadata 两项兼容硬伤**:H-2 `getting-started`+`setup/settings` 下发端点(medium)→ H-1 关 ZK 时 server private key 联动(large)。**理由:直接违背"与官方插件完全兼容"目标,H-2 是纯 404 兼容硬伤成本可控,H-1 是真链路断裂但工程量大宜其后。**

4. **副作用层收尾**:`send_folder_*` + `send_password_CUD` 通知(medium)。**理由:Folders/Resource 功能本身已实现,只差发信侧,是"配置面完整、副作用空心"的最后一块;send_folder_* 还会导致官方管理页 UI 缺块。**

5. **username 大小写可配 + SMTP endpointsDisabled 锁**(medium/small)。**理由:前者是跨实例迁移的真实数据踩坑点,后者是官方硬化指南明确要求且 healthcheck 已预留位置。**

大改插件(PasswordExpiry / SecretRevisions / Rbacs / Mobile / Duo·Yubikey)建议**列入路线图后段并按"文档明示定位"处理**——其 flag 均已诚实 false、客户端不炸,且 Duo/Yubikey 移植会引入违反项目约束的外部依赖,不宜为形式全等而破坏架构纪律。