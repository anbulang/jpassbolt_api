# JPassbolt 事件驱动邮件通知基础设施 — 实现方案

> 制定 2026-06-28。配套缺口审计见 `gap_audit_vs_passbolt_ce.md`(第一梯队第 1 条)。
> 目标:补齐审计指出的"副作用层空心"。照搬 Passbolt CE 的「领域事件 + EmailRedactor + 通知开关门控」解耦架构,落到 Spring 生态。第一版同步直发(无队列),打通最高价值的 5~6 类通知,让已存在但"纯摆设"的 25 键开关真正生效。

---

## 1. 架构总览

### 1.1 数据流(文字图)

```
[Service 写方法 @Transactional]
        │  业务全部持久化完成(save/delete)后,return 前
        │  applicationEventPublisher.publishEvent(new XxxSuccessEvent(...))   ← 只发事件,不发邮件
        ▼
[Spring 容器内事务同步缓冲]  ← 事件被挂起,等待事务结果
        │
        │  事务 COMMIT 成功(数据真正落库)
        ▼
[@TransactionalEventListener(phase = AFTER_COMMIT) @Async  —— 各 Redactor]
        │  ① 门控:emailNotificationSettingsService.get().get("send_xxx") == false → return(短路,零查询)
        │  ② 收件人解析:recipientResolver.resolveXxx(...) → Set<Recipient>(剔除 deleted/disabled/操作者本人)
        │  ③ 逐收件人:emailTemplateService.render(templateName, recipient.locale(), vars) → html
        │  ④ try { mailService.send(EmailMessage) } catch(Exception e){ log.warn }  ← 单封失败不影响其它/不抛
        ▼
[MailService.send(EmailMessage)]  ← 复用现有传输层
        │  JavaMailSender 不可用 / jpassbolt.email.enabled=false → log.info 降级
        ▼
[JavaMailSender → SMTP](default/test profile 降级 log;local profile 真发 MailHog)
```

### 1.2 为什么必须 AFTER_COMMIT(核心)

1. **数据一致性**:监听器内会用 `RecipientResolver` 反查数据库(权限行、群成员、profile、locale)。若在事务提交前发邮件,监听器开的只读事务可能读不到本事务尚未提交的写入(不同连接),导致收件人算错或邮件内容指向不存在的实体。
2. **避免"发了信但事务回滚"**:若用 `BEFORE_COMMIT` 或同步直发(现状 `RecoverService` 那样),事务在发信之后回滚,就会出现"用户收到分享通知,但分享其实没存进库"的幽灵邮件。AFTER_COMMIT 保证"只有数据真落库了才发信",对齐 CE 在请求末尾 `enqueue` 的时序语义。
3. **主链路不被拖慢/不被污染**:发信是 best-effort 副作用,绝不能因 SMTP 抖动让 API 返回 5xx。AFTER_COMMIT 阶段事务已结束,配合 `@Async` + try/catch,彻底把发信从主事务剥离。

> 注意:`AFTER_COMMIT` 监听器里若要再写库,需 `@Transactional(propagation = REQUIRES_NEW)`。本方案监听器**只读**(`readOnly=true`),不写库,无此问题。

### 1.3 第一版:同步直发 vs 队列 —— 明确取舍与建议

| 维度 | 第一版(同步直发 + @Async) | 第二阶段(email_queue 表 + @Scheduled) |
|------|------|------|
| Schema 变更 | **零新表**(关键优势,规避 `ddl-auto=validate` 铁律的远程建表风险) | 需先在远程 MySQL 建好 `email_queue` 表才能上线 |
| 可靠性 | SMTP 临时故障即丢该封(无重试) | 持久化 + 指数退避重试 + 摘要聚合 |
| 复杂度 | 复用现成 `MailService` 降级逻辑,最短路径 | 新增 Job/锁/退避/Digest |
| N 收件人 | N 次同步 SMTP 调用,靠 `@Async` 线程池缓解 | 入队 O(N) 写库 + 后台批发 |

**建议:第一版采用同步直发(`@TransactionalEventListener(AFTER_COMMIT)` + `@Async`),不建任何表。** 理由:当前正确性需求是"让该发的信发出去 + 让 25 键开关生效",而非高并发/摘要;`MailService` 已具备 SMTP 缺失降级、发信失败 swallow 的安全特性,正好契合同步直发的风险点。把队列做成 `MailService.send()` 背后的**可替换实现**,将来切换时 Service/Redactor 层完全无感。

---

## 2. 包 / 类设计(`com.jpassbolt.api` 下新增)

```
service/email/
├── MailService.java                        【改造】新增 public void send(EmailMessage),保留现有 3 方法
├── EmailMessage.java                       【新】不可变 record:单封待发邮件值对象
├── EmailTemplateService.java               【新】Thymeleaf 渲染:(templateName, locale, vars) → html
├── RecipientResolver.java                  【新】@Service 只读,userId 集合 → Set<Recipient> 聚合视图
├── Recipient.java                          【新】record(userId,email,locale,firstName,lastName)
├── event/                                  【新包】领域事件(全部 immutable record,无业务逻辑)
│   ├── ResourceShareSuccessEvent.java
│   ├── CommentAddSuccessEvent.java
│   ├── GroupCreateSuccessEvent.java
│   ├── GroupUpdateSuccessEvent.java        (聚合 added/deleted/updated)
│   ├── GroupDeleteSuccessEvent.java
│   ├── UserRegisterSuccessEvent.java
│   ├── UserDeleteSuccessEvent.java
│   ├── AccountRecoveryRequestedEvent.java
│   ├── SetupInviteRequestedEvent.java
│   └── AccountRecoveryCompletedEvent.java
└── redactor/                               【新包】每事件一个监听器,对齐 CE 的 EmailRedactor
    ├── ShareEmailRedactor.java
    ├── CommentAddEmailRedactor.java
    ├── GroupUserAddEmailRedactor.java      (监听 Create + Update 两个事件)
    ├── GroupUserDeleteEmailRedactor.java   (监听 Update + GroupDelete)
    ├── GroupUserUpdateEmailRedactor.java   (监听 Update,角色变更)
    ├── UserRegisterEmailRedactor.java
    ├── UserDeleteEmailRedactor.java
    └── AccountRecoveryEmailRedactor.java   (监听 Requested/Invite/Completed 三事件)

config/
└── AsyncConfig.java                        【新】@EnableAsync + 专用 ThreadPoolTaskExecutor("mailExecutor")
```

| 类 | 一句职责 |
|----|----------|
| `EmailMessage` (record) | 单封邮件不可变载体:`recipient, subject, html` |
| `EmailTemplateService` | 用 Thymeleaf `TemplateEngine` + `Context(locale)` 把模板渲染成 HTML,文案走 `mailMessageSource` |
| `RecipientResolver` | 把 userId 集合聚合成可发信的 `Recipient`(email=username、locale、姓名),统一剔除 deleted/disabled/actor |
| `Recipient` (record) | 收件人视图,封装 User+Profile+locale 三处数据 |
| `*SuccessEvent` (record) | 领域事件,只携带 id 与必要快照(避免持有 detached 实体) |
| `*EmailRedactor` (@Component) | `@TransactionalEventListener(AFTER_COMMIT)@Async` 监听器:门控→解析收件人→渲染→`MailService.send` |
| `MailService`(改造) | 传输层底座:新增通用 `send(EmailMessage)`,复用现有 JavaMailSender 探测/降级/容错 |
| `AsyncConfig` | 启用异步并提供独立邮件线程池,隔离主请求线程 |

**为什么不需要手写 Dispatcher/Pool/SubscriptionManager**:CE 的两段式 collect/dispatch 是为补 PHP 无 DI 容器;Spring 容器本身就是订阅表,`@TransactionalEventListener` 按事件类型自动路由,`@Component` 自动发现,跨池复用天然去重。

---

## 3. 第一版范围(MVP)

聚焦审计点名的最高价值 + 现成可复用收件人能力的通知:**分享、评论、用户邀请、用户删除、群组成员变更、账户恢复全链路**。

### 事件 → Redactor → 开关 key → 收件人 → 模板 映射表

| 领域事件 | Redactor | 通知开关 key(默认值) | 收件人 | 模板(role/name) |
|---------|----------|----------|--------|------|
| `ResourceShareSuccessEvent` | `ShareEmailRedactor` | `send_password_share`(true) | 本次**新获权**用户(event.added,排除 actor) | `lu/resource_share` |
| `CommentAddSuccessEvent` | `CommentAddEmailRedactor` | `send_comment_add`(true) | 对该 resource 有访问权的其他用户(排除作者) | `lu/comment_add` |
| `GroupCreateSuccessEvent` / `GroupUpdateSuccessEvent`(added) | `GroupUserAddEmailRedactor` | `send_group_user_add`(true) | 被加入群组的成员 | `lu/group_user_add` |
| `GroupUpdateSuccessEvent`(deleted) / `GroupDeleteSuccessEvent` | `GroupUserDeleteEmailRedactor` | `send_group_user_delete`(true) | 被移出 / 原群组成员 | `lu/group_user_delete` |
| `GroupUpdateSuccessEvent`(updated) | `GroupUserUpdateEmailRedactor` | `send_group_user_update`(true) | 角色被变更的成员 | `lu/group_user_update` |
| `UserRegisterSuccessEvent` | `UserRegisterEmailRedactor` | `send_user_create`(true) | 新注册用户本人 | `an/user_register_admin` |
| `UserDeleteSuccessEvent` | `UserDeleteEmailRedactor` | `send_group_user_delete`(true,对齐 CE) | 受影响群组的成员 | `gm/user_delete` |
| `AccountRecoveryRequestedEvent` | `AccountRecoveryEmailRedactor` | `send_user_recover`(true) | 发起找回的用户 | `an/user_recover` |
| `SetupInviteRequestedEvent` | `AccountRecoveryEmailRedactor` | `send_user_create`(true) | 被邀请用户 | `an/user_register_admin` |
| `AccountRecoveryCompletedEvent` | `AccountRecoveryEmailRedactor` | `send_user_recoverComplete`(true) | 完成找回的用户(+管理员二期) | `an/user_recover_complete` |

> MVP 暂不做:folder 系列(25 键里无 `send_folder_*`,需扩 DEFAULTS)、resource create/update/delete、disable/admin-role、setup-complete/abort 的管理员告警、metadata/MFA/password-expiry 插件类。这些放第二阶段。

---

## 4. 服务层改造点(逐条 file:method + publish 位置 + payload)

**铁律:Service 只 `publishEvent`,绝不直接发邮件。** 注入 `private final ApplicationEventPublisher eventPublisher;`(`@RequiredArgsConstructor`)。

| # | 文件 : 方法 | publish 位置 | 发布事件 + payload 字段(方法内现成数据) |
|---|------------|--------------|------|
| 1 | `service/PermissionService.java : share`(行185-338) | return 前,权限/secret/folders_relations 全部持久化后 | `ResourceShareSuccessEvent(resourceId, ownerId=userId, added, removed, secrets)`;`added/removed` 在行195-196 已算出 |
| 2 | `service/CommentService.java : addComment`(行60-86) | `save`(行83)后 return 前 | `CommentAddSuccessEvent(commentId=saved.id, resourceId, authorId=userId, content)` |
| 3 | `service/GroupService.java : createGroup`(行162-211) | `save` 成员后(行207)、`log`(行209)前 | `GroupCreateSuccessEvent(groupId, groupName, operatorId, members[userId,isAdmin])` |
| 4 | `service/GroupService.java : updateGroup`(行225-344) | 应用完 toAdd/toDelete/toUpdate、改名后,`save group`(行343)前 | `GroupUpdateSuccessEvent(groupId, groupName, operatorId, addedMembers, deletedMembers, updatedMembers, renamed)`(**单一聚合事件**,三组列表为内存对象) |
| 5 | `service/GroupService.java : deleteGroup`(二参重载,行512-556) | `log.info`(行555)前,**仅内层发一次** | `GroupDeleteSuccessEvent(groupId, groupName, operatorId, memberUserIds)`;`members` 在行516 已快照(软删前取) |
| 6 | `service/UserService.java : createUser`(行84-160) | `token.save`(行156)后、`log`(行158)前 | `UserRegisterSuccessEvent(userId, username, tokenValue, adminId)`;建议给 `createUser` 加 `operatorId` 形参 |
| 7 | `service/UserDeleteService.java : deleteUser`(行114-184) | `user.setDeleted`(行179)后、`log`(行182)前 | `UserDeleteSuccessEvent(deletedUserId, deletedUsername, deletedBy=actorId, deletedGroupIds=onlyMemberGroupIds(行139))` |
| 8 | `service/RecoverService.java : recover`(行131/134) | **删除**现有 `mailService.sendRecoverEmail/sendSetupInviteEmail` 同步直调,改为 publish | 按 token 类型:`AccountRecoveryRequestedEvent(userId, username, tokenValue)` 或 `SetupInviteRequestedEvent(userId, username, tokenValue)` |
| 9 | `service/RecoverService.java : completeRecover`(行213) | **删除**现有 `sendRecoverCompleteEmail` 直调,改为 publish | `AccountRecoveryCompletedEvent(userId, username)` |

> 关于 #5、#7(硬删/软删收件人快照):`GroupDelete`/`UserDelete` 必须在删除/级联清理**前**把收件人 userId 快照进 payload;AFTER_COMMIT 触发时 membership 行可能已不在。`share`/`comment`/`resource delete`(软删)则可在监听器里反查(权限行仍在)。

---

## 5. 通知开关门控(25 key → Redactor)

门控统一写在每个 Redactor 方法**开头第一行**,开关关闭即 `return`,短路掉收件人查询与渲染(零额外开销)。读取走现成 `EmailNotificationSettingsService.get()`(行122,返回 25 键扁平 `Map<String,Object>`,DB 覆盖 DEFAULTS)。

```java
private boolean enabled(String key) {
    return Boolean.TRUE.equals(settingsService.get().get(key));
}
// Redactor 内:
if (!enabled("send_password_share")) return;   // 短路
```

**25 键映射**(括号为默认值):

| 类别 | key | 归属 Redactor / 用途 |
|------|-----|------|
| 发信门控(send_*) | `send_password_share`(true) | ShareEmailRedactor |
| | `send_comment_add`(true) | CommentAddEmailRedactor |
| | `send_group_user_add`(true) | GroupUserAddEmailRedactor |
| | `send_group_user_delete`(true) | GroupUserDeleteEmailRedactor + UserDeleteEmailRedactor(对齐 CE) |
| | `send_group_user_update`(true) | GroupUserUpdateEmailRedactor |
| | `send_group_delete`(true) | (二期,GroupDelete 给成员的另一类文案) |
| | `send_group_manager_update`(true) / `send_group_manager_requestAddUser`(true) | 二期(群管汇总/请求加人) |
| | `send_user_create`(true) | UserRegisterEmailRedactor + SetupInvite |
| | `send_user_recover`(true) | AccountRecovery(请求) |
| | `send_user_recoverComplete`(true) | AccountRecovery(完成-用户) |
| | `send_password_create`(false) / `send_password_update`(true) / `send_password_delete`(true) | 二期(resource CUD) |
| | `send_admin_user_setup_completed`(true)/`send_admin_user_recover_abort`(true)/`send_admin_user_recover_complete`(true)/`send_admin_user_disable_user`(true)/`send_admin_user_disable_admin`(true) | 二期(管理员告警类) |
| 内容门控(show_*) | `show_comment`/`show_description`/`show_secret`/`show_uri`/`show_username`(均 false) | **在模板渲染时读取**,控制正文是否渲染敏感字段;MVP 默认全 false(不暴露) |
| | `purify_subject`(false) | 主题净化开关,MVP 可暂不实现 |

> 注意:`show_secret` 对 E2EE 几乎恒不渲染明文(服务端无明文),保留键以对齐 CE 即可。

---

## 6. 收件人解析(RecipientResolver)

关键实体事实(已核实):`User` 无独立 email 列(`email = username`)、无 locale 列(经 `AccountLocaleService.getUserLocale(userId)` 从 `account_settings` 解析)、姓名在 `Profile`。所以必须聚合三处。

```java
@Service
@RequiredArgsConstructor
public class RecipientResolver {
    private final PermissionService permissionService;
    private final GroupUserRepository groupUserRepository;
    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final AccountLocaleService accountLocaleService;

    public record Recipient(String userId, String email, java.util.Locale locale,
                            String firstName, String lastName) {}

    @Transactional(readOnly = true)
    public Set<Recipient> resolveUsersWithAccessToResource(String resourceId) {
        return resolveUsers(permissionService.getUsersIdsHavingAccessTo(resourceId)); // 复用,含群组展开+剔除deleted
    }
    @Transactional(readOnly = true)
    public Set<Recipient> resolveGroupMembers(String groupId) {
        return resolveUsers(groupUserRepository.findActiveMemberUserIds(groupId));
    }
    @Transactional(readOnly = true)
    public Set<Recipient> resolveAllAdmins() {            // 需 UserRepository 新增 findActiveAdmins()
        return resolveUsers(userRepository.findActiveAdmins().stream().map(User::getId).toList());
    }
    public Optional<Recipient> resolveUser(String userId) { /* 单点 */ }

    @Transactional(readOnly = true)
    public Set<Recipient> resolveUsers(Collection<String> userIds) {
        // 批量取 User / Profile / locale,避免 N+1;剔除 deleted/disabled
    }
}
```

**复用的查询**(均已存在):
- `PermissionService.getUsersIdsHavingAccessTo(resourceId)`(行127)— 权限+群组展开+剔除 deleted,**分享/评论/资源删除直接复用**
- `GroupUserRepository.findActiveMemberUserIds(groupId)` / `findByGroupIdAndIsAdminTrue(groupId)` / `findActiveMemberUserIdsByGroupIdIn(...)`
- `AccountSettingRepository.findByUserIdAndPropertyIn(...)`(批量 locale,防 N+1)

**唯一需新增的查询**(`repository/UserRepository.java`,对齐 PHP `UsersFindersTrait::findAdmins`):

```java
@Query("SELECT u FROM User u, Role r WHERE u.roleId = r.id AND r.name = 'admin' "
     + "AND u.active = true AND u.deleted = false")
List<User> findActiveAdmins();
```

**排除操作者本人**:不污染 repository,由 Redactor 调用方把 `actorId` 传入并在内存 filter(如 `CommentAddEmailRedactor` 剔除 `authorId`,`ShareEmailRedactor` 剔除 `ownerId`)。

---

## 7. 模板方案

**引擎选型:引入 `spring-boot-starter-thymeleaf`,专用于邮件,只产 HTML。** 取舍:现状纯 String `wrap()/button()` 拼接,10+ 事件后维护失控、转义不安全;CE 的 HTML 外壳约 280 行响应式表格,必须结构化。Thymeleaf 是新增依赖但仅用于邮件,可接受。

- **目录**:`src/main/resources/templates/email/{role}/{name}.html`,role = `lu/an/gm/ad`(对齐 CE 编码:登录用户/匿名/群管/管理员);外加共享 `templates/email/_layout.html`(fragment,含 logo/footer/内联 CSS)。
- **HTML/text**:与 CE 一致,**只发 HTML**(`MimeMessageHelper.setText(html, true)`),text 不逐事件维护,必要时留纯文本兜底。
- **多语言**:**第一版只需 en + zh 均可**(现有 `messages/email_{en,zh}.properties` 已是双语,直接复用)。Thymeleaf `#{...}` 走 `mailMessageSource`,按收件人 `Locale` 渲染。文案按 `email.<domain>.<event>.*` 扩展键(如 `email.resource.share.subject/title/body`)。
- **变量注入**:`EmailTemplateService.render(name, locale, Map<String,Object> vars)` → `org.thymeleaf.context.Context(locale)` 注入 vars + 全局 `fullBaseUrl`(=`jpassbolt.app.base-url`,指向 SPA)、`appName`。`show_*` 开关传入 vars,模板内 `th:if` 控制敏感字段是否渲染。

`EmailMessage` 建议形态(让渲染在 Redactor 完成,`MailService` 只管发):
```java
public record EmailMessage(String recipient, String subject, String html) {}
```

`EmailTemplateService` 需要一个独立的邮件 `TemplateEngine` bean(`ClassLoaderTemplateResolver` prefix=`templates/email/`,`templateMode=HTML`,配 `mailMessageSource` 为 messageSource),放 `config/`,避免与可能的 Web 视图引擎冲突。

---

## 8. 数据库

**第一版同步直发:无需建任何表。** 这是规避「MySQL `ddl-auto=validate`、新表须先在远程库手工建好」铁律的关键好处——MVP 不碰 schema,零部署阻塞。

第二阶段若引入异步队列,再建 `email_queue`(**须先在远程 MySQL 建好该表,再上线代码**,否则 `validate` 启动失败)。建议 schema(`EmailQueue extends BaseEntity`,UUID 主键,不照搬 CE 的 BIGINT 自增/`locked` 列,改用 `SELECT ... FOR UPDATE SKIP LOCKED`):

```sql
CREATE TABLE email_queue (
  id            CHAR(36)     NOT NULL PRIMARY KEY,
  recipient     VARCHAR(255) NOT NULL,
  subject       VARCHAR(255) NOT NULL,
  template      VARCHAR(100) NOT NULL,
  template_vars LONGTEXT     NOT NULL,           -- Jackson 序列化的变量 Map(JSON)
  format        VARCHAR(10)  NOT NULL DEFAULT 'html',
  sent          TINYINT(1)   NOT NULL DEFAULT 0,
  send_tries    INT          NOT NULL DEFAULT 0,
  send_at       DATETIME     NULL,
  next_retry_at DATETIME     NULL,               -- 指数退避
  error         TEXT         NULL,
  created       DATETIME     NOT NULL,
  modified      DATETIME     NOT NULL,
  KEY idx_email_queue_dispatch (sent, send_at, next_retry_at)
);
```
对应一个 `@Scheduled` 的 `EmailQueueSenderJob`(需 `@EnableScheduling`),`MailService.send()` 改为写表实现,Redactor 层无感切换。

---

## 9. 测试策略

新增依赖:`com.icegreen:greenmail-junit5`(内存 SMTP)。每个 Redactor 必带 H2 集成测试。

1. **GreenMail 集成测试**(端到端,对齐"新功能必带 H2 集成测试"约定):
   - `@SpringBootTest`,`@TestConfiguration` 注入 GreenMail 起内存 SMTP(覆盖 `spring.mail.*`)+ `jpassbolt.email.enabled=true`。
   - 测试用例:构造数据 → 调真实 Service 写方法(`@Transactional` 提交)→ 断言 `greenMail.getReceivedMessages()` 的收件人/主题/正文。
   - 覆盖:`ShareControllerTest` 分享后新获权用户收信、非新增用户不收信;`CommentControllerTest` 有权他人收信、作者不收信。

2. **AFTER_COMMIT 触发验证**:`@TransactionalEventListener(AFTER_COMMIT)` 仅在真实提交后触发。集成测试用 `@RecordApplicationEvents` + `ApplicationEvents` 断言事件已发布;或用 `TestTransaction.flagForCommit()/end()` 手动提交触发监听器。**不能**用默认回滚的 `@Transactional` 测试类,否则监听器永不触发。

3. **门控测试**:`save({"send_password_share": false})` → 触发分享 → 断言 GreenMail 收到 0 封;改回 true → 收到 N 封。验证 25 键真正生效。

4. **事件发布单测**:Service 层用 `@MockBean ApplicationEventPublisher`(或 `@RecordApplicationEvents`),断言写方法成功后 `publishEvent` 被调且 payload 字段正确(含硬删前快照场景)。

5. **RecipientResolver 单测/集成**:H2 覆盖群组展开去重、deleted 用户被剔除、locale 回退到组织/默认 `en-UK`、`findActiveAdmins` 只返回 active 非删管理员。

6. **保持 OpenAPI 合规**:本特性是纯副作用层,**不改任何 endpoint/DTO/响应信封**,21 个 `*ContractTest` 不受影响。Redactor 在 AFTER_COMMIT 异步执行,不改变 HTTP 响应。

---

## 10. 分阶段落地清单 + 风险点

### 阶段 0:地基(先合,无行为变化)
1. `pom.xml` 加 `spring-boot-starter-thymeleaf`、测试加 `greenmail-junit5`。
2. `config/AsyncConfig`(`@EnableAsync` + `mailExecutor` 线程池);`config/` 加邮件专用 `TemplateEngine` bean。
3. `MailService` 新增 `public void send(EmailMessage)`(渲染由 Redactor 做,这里只发;复用现有降级/容错路径)。
4. `EmailMessage`/`Recipient` record、`EmailTemplateService`、`RecipientResolver`(含 `UserRepository.findActiveAdmins`)。
5. `templates/email/_layout.html` + 各模板骨架;扩 `email_{en,zh}.properties` 文案键。

### 阶段 1:MVP 通知(按价值次序)
6. 分享 → 评论 → 用户邀请(顺带把 `RecoverService` 三处直发**迁移**为事件)→ 用户删除 → 群组成员变更 → 账户恢复完成。每接一个:事件 record + Service publish + Redactor + GreenMail 测试 + 门控测试。

### 阶段 2:补全
7. resource create/update/delete、folder 系列(需扩 DEFAULTS 加 `send_folder_*`)、管理员告警类(disable/admin-role/setup-complete/abort/recover-admin)、群管汇总。
8. `email_queue` 表 + `@Scheduled` 发送 Job + 指数退避重试 + EmailDigest 摘要 + 运行时 SMTP 设置页(`/smtp/settings`,从 DB 动态构造 `JavaMailSenderImpl`)。
9. metadata/MFA/password-expiry 等插件类 Redactor(低优先级)。

### 风险点与对策

| 风险 | 对策 |
|------|------|
| **AFTER_COMMIT 内异常** | 每个 Redactor 整体 try/catch + `log.warn`(对齐 CE Dispatcher 吞异常);`MailService` 已 swallow。AFTER_COMMIT 阶段抛异常**不回滚**主事务,但会污染日志/中断后续监听,故必须自吞。 |
| **事务边界** | 已核实所有目标写方法均**单层 @Transactional**,`deleteGroup` 双重载 REQUIRED 同事务,AFTER_COMMIT 在最外层提交后触发,可行。监听器只读、不写库。 |
| **硬删/成员移除收件人丢失** | `GroupDelete`/`UserDelete`/`FolderDelete`(二期)必须在删除**前**把收件人 userId 快照进 event payload;不可在监听器里反查已删行。 |
| **N 个收件人性能** | `RecipientResolver.resolveUsers` 批量查 User/Profile/locale 防 N+1;`@Async` 邮件线程池隔离主请求线程;同步直发下 N 封 = N 次 SMTP,量大时升级到队列。 |
| **与现有 RecoverService 直发迁移** | 将 `recover`/`completeRecover` 内 3 处 `mailService.sendXxx` 直调删除,改为 publish 事件;`AccountRecoveryEmailRedactor` 监听后复用现有发送方法。`abortRecover` 当前只 log,二期补管理员告警事件。务必在迁移测试里确认"恢复邮件仍照发",避免回归。 |
| **`@Async` 丢失 SecurityContext / 请求上下文** | Redactor 不依赖 `SecurityContext`(actorId 由 event 携带),且 locale 由收件人解析得到,无需 `RequestContextHolder`,安全。 |
| **跨池重复发送** | Spring `@Component` 单例 + 按事件类型路由天然无重复。同一事件多 Redactor(如 GroupUpdate 的 add/delete/update)是**有意**的多封不同收件人邮件。 |

---

## 关键证据文件路径汇总(供开发对照)

- 传输底座:`src/main/java/com/jpassbolt/api/service/email/MailService.java`(`send` 私有方法行128,待提升)
- 门控数据源:`.../service/EmailNotificationSettingsService.java`(`get()` 行122,`DEFAULTS` 行71)
- 收件人复用:`.../service/PermissionService.java`(`getUsersIdsHavingAccessTo` 行127、`share` 行185)
- 待加查询:`.../repository/UserRepository.java`(行15,新增 `findActiveAdmins`)
- 群成员查询:`.../repository/GroupUserRepository.java`(`findActiveMemberUserIds` 行77)
- 迁移点:`.../service/RecoverService.java`(行131/134/213 直发改 publish)
- CE 蓝本(只读):`passbolt_api_ref/src/Notification/Email/`(接口/Pool/Dispatcher)、`Redactor/{Share,Comment,Group,User,Recovery}/`、`src/Notification/NotificationSettings/CoreNotificationSettingsDefinition.php`(25 键 schema)
