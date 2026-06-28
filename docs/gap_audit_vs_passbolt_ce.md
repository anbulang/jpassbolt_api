# JPassbolt 相比原版 Passbolt CE 功能缺口审计报告

> 审计日期 2026-06-27。基准:本地 `passbolt.local` 实测 edition=ce。
> 方法:21 个功能领域 × (对照分析 PHP 参考源/JPassbolt 代码/OpenAPI 规范 → 对抗式验证) + 综合,共 43 个 agent。

## 执行摘要

JPassbolt 在**端点存在性**与**核心同步业务逻辑**上对原版 Passbolt CE 的移植度极高:OpenAPI 规范定义的 56 个 path / ~85 个 path×method 操作**全部有对应 handler**,资源/密钥 CRUD、共享与权限(ACO/ARO + secret 覆盖 + 群组 fan-out)、文件夹 CRUD/移动/共享、用户/群组深度管理(唯一所有者保护、所有权转移、密钥重加密)、GpgAuth 三阶段 + JWT 认证、TOTP MFA、v5 加密元数据透明层等均已扎实落地,部分领域(v5 metadata、文件夹、标签)甚至超出 CE 基线。

然而,JPassbolt 存在一条贯穿全局的**系统性缺口**:几乎所有"后台副作用层"未实现。本地实测 `passbolt.local` 为 CE 版,默认启用 `selfRegistration`、`inFormIntegration` 等插件,而 JPassbolt 在这些维度成片缺失。

**最关键的 5 个缺口(按影响排序):**

1. **整套事件驱动邮件通知体系缺失** — 22 个 EmailRedactor + EmailQueue + EmailDigest 全无;分享、评论、群组变更、用户停用/删除、资源 CRUD、元数据密钥事件均不发邮件(`MailService` 仅接到找回/初始化流程)。横跨 8 个领域的最大共性缺口。
2. **自助注册(SelfRegistration)整块缺失** — 本地 CE 实测此插件**已启用**,但 JPassbolt 无任何 `/self-registration/*` 端点、无访客注册路径、settings.json 不广告该插件。少数"运行中默认启用却完全没有"的真缺口。
3. **操作审计日志(Log 插件)完全空白** — 无 `action_logs`/`entities_history`/`secret_accesses` 表与写入;所有 secret 访问、权限变更、资源操作均无审计痕迹,合规能力为零。
4. **SMTP 设置页整套缺失** — 无 `/smtp/settings` 读写、无"发送测试邮件"、无 GPG 加密入库;管理员无法通过 API 配置/验证邮件服务器,SMTP 仅靠静态 `application.yml`。
5. **前端缺独立管理控制台 + 多个用户功能** — 无 `/administration` 区域(管理项零散塞进个人 Settings),缺密码导入/导出(CSV/KDBX)、密码历史、头像上传、移动端配对等 CE 默认功能。

总体判断:**JPassbolt 是一个"读写主链路完整、副作用层空心"的移植版**——能正确建/改/删/分享数据并保证零知识与数据完整性,但不会发任何通知邮件、不留任何审计、缺少多个管理控制台页面。

---

## 一、【CE 核心缺口】(原版 CE 默认提供、本地实际启用、JPassbolt 缺失/仅部分实现)

### 1.1 邮件通知与 SMTP(最大共性缺口)

| 功能 | 原版提供 | JPassbolt 现状 | 影响 | 优先级 |
|------|---------|---------------|------|--------|
| 事件驱动通知邮件(22 个 Redactor) | 资源创建/更新/删除、分享、评论、群组增删改、用户注册/删除/停用、管理员停用、账户恢复、setup 完成等事件均发邮件 | 完全未实现。`MailService` 仅 3 方法且只被 `RecoverService` 调用;无任何 Redactor/事件机制 | 用户对所有协作操作零感知;安全审计邮件全失 | **高** |
| EmailQueue 异步队列 + SenderCommand | 邮件入队 + cron 批量发送 + 失败重试持久化 | 无队列、无 `@Scheduled`、同步直发 | 架构性差异;无重试/批处理 | 中 |
| EmailDigest 摘要聚合 + 预览端点 | 按收件人聚合多封通知为摘要;`GET /email/digest/preview` | 完全缺失 | 高频通知场景体验差 | 低 |
| 分享通知邮件(`send.password.share`) | 新获访用户收到"X 分享了 Y"邮件 | `PermissionService` 不引用 `MailService`、无 `share.success` 事件 | 被分享者不知情 | **高** |
| 评论新增通知邮件(`CommentAddEmailRedactor`) | 资源有权用户收到评论通知 | 评论 CRUD 正确但 `addComment` 不发事件/邮件 | 评论协作无通知 | 中 |
| 文件夹通知邮件(create/update/delete/share) | CE 默认发删除/更新/共享邮件;`send_folder_*` 设置项 | 完全缺失;`EmailNotificationSettings` 的 25 键中无 `send_folder_*` | 文件夹操作无通知 | 中 |
| GET/POST `/smtp/settings.json` | 管理员读写 SMTP 配置,GPG 加密入库,多源解析(db>file>env) | 完全缺失;SMTP 仅 `spring.mail.*` 静态明文 | 管理员无法运行时配置 SMTP | **高** |
| POST `/smtp/settings/email.json` 发测试邮件 | 用提交配置实发测试信 + 返回脱敏 trace | 完全缺失 | 无法验证 SMTP 连通性 | 中 |
| SMTP 健康检查 | 4 项真实探测(配置/来源/端点禁用/SSL) | `HealthcheckService` 硬编码占位 stub | 健康检查不反映真实状态 | 中 |

> 说明:`GET/POST /settings/emails/notifications.json`(25 键通知开关)JPassbolt **已实现存取**,但这些开关**不门控任何实际发信**(纯摆设),因为底层通知本身未实现。

### 1.2 自助注册(SelfRegistration)— 本地 CE 实测已启用

| 功能 | 原版提供 | JPassbolt 现状 | 影响 | 优先级 |
|------|---------|---------------|------|--------|
| `/self-registration/settings.json` (GET/POST/DELETE) | 管理员读写自助注册策略(provider + 域名白名单) | 完全无端点/服务/持久化 | 管理员无法配置自助注册 | **高** |
| `/self-registration/dry-run.json` | 访客提交 email 校验域名白名单/格式/未注册 | 完全缺失 | 前端无法判断能否注册 | **高** |
| 访客注册路径(`POST /users.json` 域名拦截) | 域名命中白名单的访客可建号 + 派发 self-register 事件 | `addUser` 仅管理员邀请式;无访客分支 | 访客无法自助注册(CE 核心场景) | **高** |
| 域名白名单校验 + 自助注册成功邮件 | 完整校验 + 双向通知邮件 | 完全缺失 | — | 中 |
| settings.json 广告 `selfRegistration` 插件 | guest 视图即暴露 `selfRegistration.enabled` | `SettingsProperties` 不含该键 | 前端不显示注册入口 | **高** |
| Healthcheck 三项自助注册检查 | 真实检测插件/provider | 硬编码占位 | 状态不实 | 低 |

### 1.3 操作审计日志(Log 插件 — CE 默认启用)

| 功能 | 原版提供 | JPassbolt 现状 | 影响 | 优先级 |
|------|---------|---------------|------|--------|
| 操作审计写入(`action_logs`) | 每个控制器动作记录用户/动作/状态码 | 无表、无写入、无拦截器 | 无任何操作审计 | 中 |
| 实体历史(`entities_history`/`permissions_history`) | 资源/权限/文件夹增删改历史 | 完全缺失 | 前端 activity feed 无数据源 | 中 |
| 密码访问记录(`secret_accesses`) | 谁在何时读取了哪条密码 | 资源列表/详情、secret 读取均不留痕 | 合规审计关键能力缺失 | **高** |
| 管理员报表端点 `/reports/{slug}.json` | 管理员查看活跃/非活跃用户统计 | 无 `ReportController`/报表框架 | 管理员无统计报表 | 低 |
| 审计清理命令 / 外部日志引擎转发 | purge cron + syslog/SIEM 转发 | 完全缺失 | 运维侧能力缺失 | 低 |

### 1.4 RBAC 功能可见性控制(Rbacs 插件 — CE 默认启用)

| 功能 | 原版提供 | JPassbolt 现状 | 影响 | 优先级 |
|------|---------|---------------|------|--------|
| `GET /rbacs/me` | 客户端拉取当前角色 RBAC 策略以隐藏/显示前端动作 | 无任何 Rbac/UiAction 实体/端点 | 客户端拿不到 RBAC 策略 | 中 |
| `GET /rbacs` / `POST\|PUT /rbacs`(管理员) | 列出/批量更新角色对 UI 动作的 Allow/Deny | 完全缺失 | 管理员无法控制功能可见性 | 中 |
| `GET /rbacs/uiactions` + 20 个默认 UI 动作 | 可控动作清单(import/export/copy/share/tags 等) | H2 SQL 含种子但**运行时是死数据**(无实体→表不创建→不 seed) | 整个 RBAC 体系为零 | 中 |

> 验证更正:分析阶段误判 20 个 UI 动作为"部分实现"(因 H2 SQL 有种子),复核确认该 SQL 仅在 `docs/ref_files/` 从未被运行时加载,准确状态为**未实现**。

### 1.5 认证与会话(GpgAuth / JWT)

| 功能 | 原版提供 | JPassbolt 现状 | 影响 | 优先级 |
|------|---------|---------------|------|--------|
| 登录后更新 `last_logged_in` | 登录成功事件更新时间戳 | 从不更新,序列化恒为 `null` | 用户列表无登录时间;审计缺失 | 中 |
| GpgAuth Stage 1 服务器签名 | 对挑战 nonce 做服务器私钥签名 | 仅 `encrypt` 不签名 | GpgAuth 服务器身份保证削弱 | 中 |
| GpgAuth 响应头白名单(Version/Pubkey/Logout-Url) | 10 个头 + 统一中间件 | 仅手工 add 7 类头,缺 3 个 | 严格兼容性差一层 | 低 |
| JWT 攻击告警邮件(域名不匹配) | 触发管理员/用户安全告警邮件 | 仅返回 400,不发邮件 | 安全告警缺失 | 中 |
| 无效 JWT 访问令牌的 error 日志/事件 | `Log::error` + `invalid_access_token` 事件 | 仅 `log.debug` 静默放行 | 安全日志不足 | 低 |
| JWT 密钥对 Healthcheck | 运行期暴露密钥对/目录状态 | 仅启动期 fail-fast | 运维可观测性不足 | 低 |
| JWT cookie 刷新 CSRF 中间件 | cookie 刷新端点强制 CSRF | 全局禁用 CSRF,无差异化 | cookie 刷新 CSRF 风险敞口 | 中 |
| GpgAuth 失败状态码语义 | 区分 200/404/500 | 一律 200 + error 信封 | 与 PHP 状态码不完全一致 | 低 |

### 1.6 账户恢复与用户密钥策略

| 功能 | 原版提供 | JPassbolt 现状 | 影响 | 优先级 |
|------|---------|---------------|------|--------|
| UserKeyPolicies 端点(`/user-key-policies/settings`) | CE 5.2 默认插件;客户端据此决定 RSA/ECC 密钥 | 完全无端点/服务/校验/前端 | setup 无密钥策略(本地未配置该插件,影响有限) | 中 |
| 恢复完成后管理员审计邮件 | 向全体管理员通报恢复完成(含 IP/UA) | 仅给用户发确认信 | 管理员审计缺失 | 中 |
| 中止恢复管理员通知邮件 | `SetupRecoverAbortAdminEmailRedactor` | 仅 `log.info` 无邮件 | 中止无通知 | 低 |
| recovery case 驱动模板 | `lost-passphrase` 影响邮件文案 | 校验已对齐但 case 未驱动模板 | 文案不区分 | 低 |

### 1.7 资源 / 密钥 / 密码过期

| 功能 | 原版提供 | JPassbolt 现状 | 影响 | 优先级 |
|------|---------|---------------|------|--------|
| 密码过期(PasswordExpiry,源码默认 enabled) | 3 个 settings 端点 + 过期校验 + 过期通知邮件 + cron | 完全无 `password-expiry` 端点/服务/实体 | 无密码轮换提示能力 | 中 |
| 禁用/删除用户时密钥过期(`ExpireResources`) | 标记失访用户消费过的资源为 expired | `expired` 字段仅普通列透传,无策略写入 | 其他用户不被提示轮换 | 中 |
| 资源列表完整能力 | 分页/排序/多 contain/多 filter | 仅 `filter[is-favorite]` + `contain[favorite]`,固定 username asc | 大数据量/复杂查询受限 | 中 |
| 资源删除清空明文 + drop 权限 | 软删时清空 uri/username/description、drop permissions | 仅置 `deleted`,不清明文字段、不 drop 权限 | 软删后明文字段残留 | 中 |
| `password-and-description` 明文清洗 | 加密描述类型保存时清空明文 `description` | 不清洗,明文与加密描述并存 | 潜在数据泄漏风险 | 中 |
| 资源创建并发死锁重试 | 5 次重试 | 无重试 | 高并发下偶发失败 | 低 |

### 1.8 头像上传 / 用户管理细节

| 功能 | 原版提供 | JPassbolt 现状 | 影响 | 优先级 |
|------|---------|---------------|------|--------|
| 头像上传(`POST /users/{id}` multipart) | 上传并存储头像 blob | **下载读路径已实现**,但无上传端点 | 头像永远是默认占位图 | 中 |
| `is_mfa_enabled` 用户列字段 | GET /users 每用户附 MFA 状态 | 完全无该字段 | 管理员看不到谁开了 MFA | 低 |
| pending 用户重发邀请 | 前端有"重发邀请"按钮 | 无 resend/reinvite | 邀请失效需手动处理 | 低 |

### 1.9 MFA(TOTP 已实现,其余缺)

| 功能 | 原版提供 | JPassbolt 现状 | 影响 | 优先级 |
|------|---------|---------------|------|--------|
| YubiKey 因子 | CE 默认完整 provider | 完全缺失(`isProviderReady` 对 yubikey 恒 false) | 不支持 YubiKey | 中 |
| Duo 因子(Duo Universal SDK) | Duo 已下放 CE | 完全缺失 | 不支持 Duo | 中 |
| 管理员重置他人 MFA(`DELETE /mfa/setup/{userId}`) | 管理员可重置任意用户 MFA + 通知 | 仅能删自己 totp | 用户丢失 MFA 设备无法救援 | **高** |
| MFA 重置通知邮件 | 重置时发安全通知 | 完全缺失 | 安全审计缺失 | 中 |
| 组织 MFA 设置仅接受 totp | 可启用并校验 yubikey/duo | 非 totp 返回 400 | provider 选择受限 | 中 |
| JWT 登录挑战内嵌 providers | 挑战内联 MFA providers 列表 | 改由登录后 302 探测 | 协议级差异(功能可达成) | 低 |

> 注:MFA token 会话绑定降级(active+30天替代 session-id)、内存级限流(替代 action_logs 持久化)为**有意简化**,功能等效,安全属性略降。

### 1.10 设置 / 多语言 / 健康检查

| 功能 | 原版提供 | JPassbolt 现状 | 影响 | 优先级 |
|------|---------|---------------|------|--------|
| `POST /locale/settings` 组织级语言(管理员) | 管理员改全局默认语言 | 只读组织 locale,无写端点 | 管理员无法改全局语言 | 中 |
| `inFormIntegration` 插件 flag(本地 CE 已启用) | guest 视图暴露 `enabled=true` | `SettingsProperties` 无该键 | 扩展页内填充开关缺失 | 中 |
| settings.json 访客视图 plugins 白名单 | guest 即见 jwtAuthentication/selfRegistration/inFormIntegration | 仅 accountRecoveryRequestHelp/locale/rememberMe | 扩展登录前判断受影响 | 中 |
| `accountRecoveryRequestHelp` flag 默认值 | 运行时 `enabled=true` | 默认 `false`(功能未实现) | 默认值与运行时相反 | 低 |
| Healthcheck 真实探测(SSL/版本/环境) | 真实检测各域 | 多数硬编码 `true` 占位 | 健康检查不反映真实状态 | 低 |
| `GET /healthcheck/error` 测试端点 | 受开关控制的错误测试端点 | 缺失 | 影响小 | 低 |
| `/import/resources` 别名路由 | 导入复用建资源逻辑的别名 | 仅 `POST /resources`,无别名 | 浏览器插件导入逐条 POST 会 404 | 中 |

---

## 二、【部分实现 / 降级】(有实现但简化或缺关键子功能)

| 功能 | 现状说明 | 优先级 |
|------|---------|--------|
| 文件夹跨用户环检测 | 仅做操作者单树祖先链上行,未实现 Tarjan 强连通分量;多用户共享可能漏判产生环 | 中 |
| 文件夹个人夹转共享退回内容 | 缺 `moveSelfOrganizedContentWithInsufficientPermissionToRoot` | 中 |
| 群组成员变更同步文件夹树 | 仅直接 shareFolder 维护树;经群组间接获/失访问时用户树不同步 | 中 |
| 共享 V5 资源校验 | 缺 `metadata_key_type=user_key` 的 400 守卫与 resource_type 软删 404 分支 | 低 |
| v5 metadata 设置变更 ZK 联动 | 仅 boolean upsert;缺关闭 ZK 时建 server private key、开启时 Flush 删除、设置变更邮件 | 中 |
| v5 metadata 密钥事件邮件 | 4 个管理员邮件 redactor 全缺(校验逻辑已完整移植) | 中 |
| v5 metadata 5 个 Healthcheck | 完全缺失 | 低 |
| TOTP 资源类型密文编辑(前端) | 识别 slug 但表单无 TOTP 字段、详情不渲染动态码 | 中 |
| 组织 MFA 设置页(前端) | 仅 whole-org TOTP 开关,无强制全员 MFA 策略 | 中 |
| 密码策略编辑 | 只读展示,无写入端点与编辑 UI | 中 |
| GPG 密钥备份下载(前端) | 可看指纹,缺私钥/公钥导出下载 | 低 |
| 密码策略配置覆盖(file/env 来源) | 写死默认值,不响应 file/env/legacy 覆盖链 | 低 |

---

## 三、【前端页面缺口】(Web 应用缺的页面 / 管理控制台)

| 缺口 | 说明 | 优先级 |
|------|------|--------|
| 独立管理控制台(`/administration`) | 无独立区域;管理项零散塞进个人 Settings 的 Account 标签 | 中 |
| SMTP 邮件服务器设置页 | 前后端均缺 | **高** |
| 自助注册管理页 | 前后端均缺(本地 CE 已启用) | **高** |
| 操作日志 / 活动审计页 + 资源 Activity 标签 | 前后端均缺;SecretPanel 仅 shared/comment 标签 | 中 |
| RBAC / UI Actions 控制台 | 前后端均缺 | 中 |
| 密码导入 / 导出(CSV/KDBX)UI | 前端无导入导出按钮/解析库 | 中 |
| 密码历史版本查看 | SecretPanel 无 history 标签 | 低 |
| 标签输入/过滤侧栏 | 后端 `TagController` 已有真实端点,前端完全未接入 | 低 |
| 头像上传 UI | 后端无写端点 | 中 |
| 移动端配对 / 二维码页 | 前后端均缺 | 中 |
| 健康检查可视化页 | 后端有端点,前端无页面 | 低 |
| 用户管理"重发邀请"按钮 | 缺 resend 动作 | 低 |

---

## 四、【Pro/EE 功能】(原版 CE 也没有,缺失可接受)

- **SSO**(OAuth2/OpenID/SAML/Azure/Google)— Pro/EE
- **用户目录同步 LDAP/AD(Directory Sync)**— Pro/EE
- **MfaPolicies**(强制全员 MFA / 自定义记住时长)— Pro/EE
- **订阅/许可证管理(Subscription)**— Pro/EE
- **组织级账户恢复策略(托管私钥)**— EE(本地仅启用 `accountRecoveryRequestHelp` 提示插件)
- **密钥历史版本(SecretRevisions)**— 实为原版 **5.7.0 beta 插件**,本地 CE **未启用**(`/secret-revisions/settings.json` 实测 404)
- **v5 metadata rotate-key/upgrade/session-keys 数据流** — 端点 handler 全部**真实落库实现**(复核更正了"疑似占位"误判),CE 默认未启用相关 Pro 插件;EE Tags 同理

---

## 五、【不适用】(浏览器插件专属 / 纯客户端 / 架构差异)

- **kdbx/csv 文件解析** — 服务端零职责,100% 浏览器插件/客户端
- **Setup 安全令牌自定义** — 浏览器插件反钓鱼用,纯 Web 端影响有限
- **TOTP 算法/校验** — 纯客户端职责
- **JWT 密钥对生成 CLI / v4→v5 迁移 CLI** — CakePHP console 命令,JPassbolt 走运维侧外部注入
- **GpgAuth 会话 Cookie / session 服务** — JPassbolt 无状态 Bearer JWT 架构
- **WebInstaller 图形化安装向导** — JPassbolt 用 `application.yml` 静态配置
- **groups-users 独立 CRUD 端点** — 原版 CE 同样无,走 GroupsUpdate(非缺口)
- **MFA 恢复码** — Passbolt 全系产品无此概念,等价能力是管理员重置 MFA

---

## 六、建议实现路线(按优先级排序)

### 第一梯队(高优先级)

1. **搭建事件驱动通知基础设施**:Spring `ApplicationEventPublisher` + `@TransactionalEventListener`,先打通分享/评论/用户群组生命周期/账户恢复 邮件(复用 `MailService`,接 25 键开关门控)。解锁多领域的地基。
2. **自助注册整块**:实体 + 域名白名单 + `GET/POST/DELETE /self-registration/settings` + `POST /self-registration/dry-run` + `POST /users.json` 访客分支 + settings.json 广告 + 前端注册页。
3. **SMTP 设置页**:`/smtp/settings` 读写(GPG 加密入库)+ 发测试邮件 + 健康检查真实探测 + 前端管理页。
4. **管理员重置他人 MFA**:`DELETE /mfa/setup/{userId}` + 重置通知邮件。
5. **secret_accesses 密码访问审计**:新建表 + 资源列表/详情、secret 读取处写入。
6. **`/import/resources` 别名路由**:指向现有 create 逻辑,堵浏览器插件导入 404。

### 第二梯队(中优先级)

7. **独立前端管理控制台**(`/administration` 区域 + 二级导航)。
8. **文件夹树一致性**:群组成员变更同步用户树 + 个人夹转共享退回 + Tarjan 环检测。
9. **YubiKey / Duo MFA provider** + 组织 MFA 多 provider。
10. **密码过期(PasswordExpiry)**:settings 端点 + 过期校验 + 用户禁用/删除时标记 expired + 通知。
11. **资源细节补全**:软删清空明文 + drop 权限、明文清洗、列表分页/排序/contain。
12. **`POST /locale/settings`** + `inFormIntegration` flag + 访客 plugins 白名单对齐。
13. **头像上传**:multipart 端点 + Avatar 写入。
14. **v5 metadata 后台行为**:ZK 模式 server private key 联动 + 4 个密钥事件邮件 + 5 个 healthcheck。
15. **操作审计写入(action_logs/entities_history)** + 前端资源 Activity 标签。
16. **前端 TOTP 编辑/渲染**、标签 UI 接入、密码策略编辑写端点。

### 第三梯队(低优先级)

17. RBAC/UiActions 体系。
18. EmailQueue 异步队列 + EmailDigest 摘要。
19. 认证细节:`last_logged_in`、Stage 1 服务器签名、缺失响应头、JWT 攻击告警邮件、cookie 刷新 CSRF。
20. Healthcheck 真实探测、`is_mfa_enabled`、重发邀请、密钥备份下载、UserKeyPolicies、移动端配对、管理员报表。

### 可不实现(Pro/EE 或 beta)

SSO、LDAP 目录同步、MfaPolicies、订阅管理、组织级账户恢复、SecretRevisions(beta)、Action Log 报表 UI、EE Tags 数据流。

---

**审计结论**:JPassbolt"数据正确性"地基扎实(零知识、权限完整性、密钥重加密均经得起对照),核心缺的是**通知/审计/管理控制台**三类"运营层"能力,以及一个**本地 CE 实测启用却完全缺失的自助注册**。优先级聚焦"事件驱动邮件基础设施 + 自助注册 + SMTP + MFA 救援 + 密码访问审计"五条线,即可覆盖绝大多数 CE 核心运行时缺口。
