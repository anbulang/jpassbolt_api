# JPassbolt 项目知识库 (Project Knowledge Base)

## 1. 项目概况

**项目名称**: JPassbolt — Java Port of Passbolt API
**目标**: 使用 Java (Spring Boot) 重新实现开源密码管理器 Passbolt 的后端 API，做到与原始 Passbolt 浏览器插件和前端完全兼容。
**版本**: 0.0.1-SNAPSHOT (开发中)
**包路径**: `com.jpassbolt.api`

### Monorepo 结构
本项目属于 `jpassbolt` monorepo 的一部分：
- `jpassbolt_api/` — **本项目**，Java 后端 API
- `jpassbolt_frontend/` — React/TypeScript 新前端 (SPA)
- `jpassbolt_api/passbolt_api_ref/` — 原始 PHP/CakePHP 参考代码

---

## 2. 技术栈

| 类别 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 17 (LTS) |
| 框架 | Spring Boot | 3.2.0 |
| 构建工具 | Maven | — |
| ORM | Spring Data JPA (Hibernate) | — |
| 安全框架 | Spring Security | — |
| GPG 加密 | Bouncy Castle (`bcprov/bcpkix/bcpg-jdk15on`) | 1.70 |
| JWT 库 | JJWT (`jjwt-api/impl/jackson`) | 0.11.5 |
| JSON 序列化 | Jackson | — |
| 代码简化 | Lombok | — |
| 生产数据库 | MySQL 8 / PostgreSQL | — |
| 测试数据库 | H2 (内存) | — |
| API 合规校验 | Atlassian Swagger Request Validator (MockMVC) | 2.39.0 |

### 关键依赖说明 — Bouncy Castle
- `bcprov-jdk15on`: 加密算法提供者 (AES, RSA 等)
- `bcpkix-jdk15on`: PKI 工具 (证书、密钥管理)
- `bcpg-jdk15on`: OpenPGP 操作 (加密、解密、签名)

> **绝对规则**: 所有 GPG/PGP 操作必须使用 Bouncy Castle，**严禁** 调用 `Runtime.exec("gpg ...")`。

---

## 3. 项目架构

### 3.1 分层架构

```
Controller (REST API) ← DTO (数据传输对象)
    ↓
Service (业务逻辑)
    ↓
Repository (数据访问)
    ↓
Model/Entity (数据模型)
```

**各层职责**:
- **Controller**: 处理 HTTP 请求/响应，只做参数校验和 DTO 转换
- **DTO**: 仅用于 API 层的输入/输出传输，不包含业务逻辑
- **Service**: 核心业务逻辑，事务管理 (`@Transactional`)
- **Repository**: Spring Data JPA 接口，自动生成 CRUD
- **Model/Entity**: 映射数据库表，包含核心领域逻辑

### 3.2 当前实现清单

**Models (19)**: `BaseEntity`, `User`, `Profile`, `Avatar`, `Resource`, `ResourceType`, `Secret`, `Permission`, `GpgKey`, `AuthenticationToken`, `Role`, `Group`, `GroupUser`, `Folder`, `FoldersRelation`, `Comment`, `Favorite`, `OrganizationSetting`, `AccountSetting`

**Controllers (20)**: `AuthController`, `JwtAuthController`, `MfaController`, `ResourceController`, `ResourceTypeController`, `SecretController`, `ShareController`, `PermissionsController`, `UsersController`, `SetupController`, `GroupController`, `FolderController`, `MoveController`, `CommentController`, `FavoriteController`, `AvatarController`, `GpgKeyController`, `RoleController`, `SettingsController`, `HealthCheckController`

**Services (25)**: `AuthService`, `JwtAuthService`, `JwtService`, `MfaService`, `TotpService`, `GpgService` (接口), `GpgServiceImpl`, `GpgKeyService`, `GpgKeyParserService`, `PermissionService`, `ResourceService`, `ResourceTypeService`, `ShareSearchService`, `UserService`, `UserDeleteService`, `SetupService`, `GroupService`, `FolderService`, `FoldersRelationsMoveService`, `CommentService`, `FavoriteService`, `AvatarService`, `RoleService`, `SettingsService`, `HealthcheckService`

**Repositories (18)**: 与 18 个持久化实体一一对应（`BaseEntity` 除外），命名 `XxxRepository`

**DTOs (14)**: `AuthDto`, `JwtAuthDto`, `MfaDto`, `ResourceDto`, `ResourceTypeDto`, `ShareDto`, `UserDto`, `SetupDto`, `GroupDto`, `FolderDto`, `CommentDto`, `FavoriteDto`, `GpgKeyDto`, `RoleDto`

**Config (7)**: `SecurityConfig`, `GpgProperties`, `SettingsProperties`, `JwtAuthenticationFilter`, `MfaEnforcementFilter`, `GlobalExceptionHandler`, `DataInitializer`

**Exception (2)**: `PassboltApiException`, `ShareValidationException`

### 3.3 编码模式和规范

**依赖注入**: 使用 Lombok `@RequiredArgsConstructor` + `private final` 字段
```java
@RequiredArgsConstructor
public class AuthService {
    private final GpgService gpgService;       // 构造器注入
    private final UserService userService;
}
```

**Entity 基类**: 所有实体继承 `BaseEntity`
- UUID 主键 (`String id`, `char(36)`)
- 自动时间戳 (`created`, `modified`, 通过 `@PrePersist`/`@PreUpdate`)
- 使用 Lombok `@Data` + `@EqualsAndHashCode(callSuper = true)`

**关联关系**: 使用 `LAZY` Fetch + `insertable=false, updatable=false`
```java
@Column(name = "user_id", nullable = false, length = 36, columnDefinition = "char(36)")
private String userId;  // 外键字段用于业务逻辑

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "user_id", insertable = false, updatable = false)
private User user;      // 关联对象用于查询
```

**JSON 字段映射**: 使用 `@JsonProperty` 处理 snake_case ↔ camelCase
```java
@JsonProperty("resource_type_id")
private String resourceTypeId;
```

**DTO 模式**: 使用 Lombok `@Builder` + 静态内部类
```java
public class ResourceDto {
    public static class CreateRequest { ... }
    public static class Response { ... }
}
```

---

## 4. 安全架构

### 4.1 核心安全原则 (Passbolt)
1. **E2EE (端到端加密)**: 客户端加密/解密，服务端不接触明文
2. **零知识服务器**: 服务端从不看到私钥、密码短语或未加密的机密
3. **GpgAuth**: 基于 OpenPGP 的质询-响应认证，非密码
4. **粒度加密**: 每个用户对每个资源的机密数据独立加密
5. **Server Key**: 用于服务端身份验证和加密服务端设置

### 4.2 GPG 认证流程 (3 阶段)

**Nonce 格式**: `gpgauthv1.3.0|36|{UUID}|gpgauthv1.3.0`

**Stage 0 — 服务器验证 (Server Verify)**:
- 客户端用服务端公钥加密一个 Nonce → 发送给服务端
- 服务端用私钥解密 → 通过 `X-GPGAuth-Verify-Response` 头返回明文
- 客户端验证明文是否匹配

**Stage 1 — 用户挑战 (Login Challenge)**:
- 客户端发送自己的 `keyid` (指纹或 key_id)
- 服务端生成 UUID → 格式化为 Nonce → 用用户公钥加密
- 存储 UUID 到 `AuthenticationToken` 表
- 通过 `X-GPGAuth-User-Auth-Token` 头返回加密的 Nonce

**Stage 2 — 完成认证 (Authenticate)**:
- 客户端解密 Nonce → 发回 `user_token_result`
- 服务端验证 Nonce 格式 → 提取 UUID → 查找匹配的 Token
- 验证成功后标记 Token 为 inactive → 颁发 JWT

**关键 HTTP 头**:
- `X-GPGAuth-Authenticated`: `true`/`false`
- `X-GPGAuth-Progress`: `stage0`/`stage1`/`stage2`/`complete`
- `X-GPGAuth-User-Auth-Token`: 加密的 Nonce (Stage 1)
- `X-GPGAuth-Verify-Response`: 解密的 Nonce (Stage 0)
- `X-GPGAuth-Error`: 错误标志
- `X-GPGAuth-Debug`: 调试信息

### 4.3 JWT 认证
- 签名算法: HS256
- 配置项: `jpassbolt.jwt.secret`, `jpassbolt.jwt.expiration`
- GPG 认证成功后颁发

### 4.4 Spring Security 配置
- CSRF: 禁用
- 公开端点: `/auth/**`, `/health-check`
- 其他所有端点: 需认证

---

## 5. 数据模型

### 5.1 核心实体关系

```
User ─────┬──── GpgKey (1:1, 用于认证)
          ├──── Permission (多对多, ACO/ARO 模型)
          ├──── Secret (每个用户对每个资源一份加密数据)
          └──── AuthenticationToken (GPG 认证临时 Token)

Resource ──┬──── Secret (加密的密码数据)
           ├──── Permission (访问控制)
           ├──── creator (User:ManyToOne)
           └──── modifier (User:ManyToOne)
```

### 5.2 权限模型 (ACO/ARO)
- **ACO** (Access Control Object): 被访问的对象，类型为 `"Resource"`
- **ARO** (Access Request Object): 请求访问者，类型为 `"User"` 或 `"Group"`
- **Permission 级别**: `READ(1)`, `UPDATE(7)`, `OWNER(15)`
- **唯一约束**: `(aco_foreign_key, aro_foreign_key)` — 一个用户对一个资源只能有一条权限记录

### 5.3 GpgKey 查找逻辑
- 40 字符 → 按 `fingerprint` 查找
- 其他长度 → 按 `key_id` 查找
- 自动转为大写

---

## 6. API 约定

### 6.1 Passbolt 标准 JSON 响应格式
```json
{
  "header": {
    "id": "uuid",
    "status": "success" | "error",
    "servertime": 时间戳,
    "code": HTTP状态码,
    "message": "描述信息",
    "url": "/请求路径"
  },
  "body": { ... }
}
```

### 6.2 URL 规范
- 所有端点以 `.json` 结尾 (例如 `/auth/login.json`, `/resources.json`)
- API 前缀: 无 (直接 `/auth/**`, `/resources/**`)
- 某些端点同时支持有无 `.json` 后缀: `@GetMapping(value = {"", ".json"})`

### 6.3 软删除
- `Resource`, `User`, `GpgKey` 使用 `deleted` 布尔字段
- 删除操作标记 `deleted = true`，不物理删除

---

## 7. 配置

### 7.1 GPG 配置 (application.yml)
```yaml
jpassbolt:
  gpg:
    server-key:
      private-location: classpath:gpg/server_private.asc
      public-location: classpath:gpg/server_public.asc
      passphrase: 密钥密码
  jwt:
    secret: Base64编码的密钥
    expiration: 过期时间(毫秒)
```

### 7.2 数据库配置
- **远程测试 MySQL**: `REDACTED-DB-HOST:3307/jpassbolt` (user: `root`, pass: `REDACTED-ROTATED-DB-PW`)
- **Schema**: 兼容 Passbolt v3/v4 官方 Schema
- **H2 测试**: 使用 `V1__Initial_Schema_Data_H2.sql` 初始化
- `jpa.hibernate.ddl-auto`: **必须为 `validate`**，严禁 `update`/`create`

---

## 8. 测试策略

### 8.1 测试分类
- **集成测试**: `src/test/java/com/jpassbolt/api/controller/`
  - `AuthControllerTest.java` — GPG 认证流程
  - `AuthControllerContractTest.java` — 合约测试
  - `AuthControllerCompatibilityTest.java.disabled` — 兼容性测试 (暂禁用)
  - `ResourceControllerTest.java` — 资源 CRUD
  - `SecretControllerTest.java` — 机密管理
  - `ShareControllerTest.java` — 共享权限
  - `OpenApiComplianceTest.java` — OpenAPI 合规
- **测试工具**: `GpgTestHelper.java` — GPG 测试辅助工具

### 8.2 测试数据库
- 使用 H2 内存数据库 + 独立 SQL 初始化脚本
- 测试 Profile 隔离

### 8.3 API 合规
- **OpenAPI 规范**: `docs/ref_files/plugin-redoc-0.yaml` 是 API 端点和 DTO 的权威来源
- 使用 Atlassian Swagger Request Validator 进行合规校验

---

## 9. 参考资源

| 资源 | 路径 | 说明 |
|------|------|------|
| PHP 参考代码 | `passbolt_api_ref/` | 原始 Passbolt PHP/CakePHP 实现 |
| OpenAPI 规范 | `docs/ref_files/plugin-redoc-0.yaml` | API 定义的权威来源 (434KB) |
| H2 Schema | `docs/ref_files/V1__Initial_Schema_Data_H2.sql` | 测试数据库初始化 |
| 安全白皮书 | `docs/ref_files/security_paper.pdf/txt` | Passbolt 安全架构文档 |
| DB 实体文档 | `docs/ref_docs/01_database_config_and_entities.md` | 数据库配置与实体详解 |
| GPG 流程文档 | `docs/ref_docs/02_gpg_auth_flow.md` | GPG 认证流程详解 |
| 加密实现文档 | `docs/ref_docs/03_bouncy_castle_encryption.md` | Bouncy Castle 实现细节 |
| JWT 文档 | `docs/ref_docs/04_jwt_authentication.md` | JWT 实现细节 |
| API 对比脚本 | `scripts/api_comparison.sh` | PHP vs Java API 对比 |

---

## 10. 关键决策记录

| 日期 | 决策 | 原因 |
|------|------|------|
| 2025-11-20 | DTO 回滚，避免过度修改 | 不必要的 DTO 改动破坏现有测试 |
| 2025-11-20 | 修正 UUID/String 类型不匹配 | `setModifiedBy` 等方法类型不一致 |
| 2025-12-03 | 使用 H2 进行集成测试 | 避免测试依赖外部数据库 |
| 2026-01-10 | 坚持使用 Bouncy Castle | 拒绝调用系统 `gpg` 命令 |
| 2026-01-13 | 不复刻 CakePHP 插件机制 | 直接使用 Spring Security 实现等效功能 |
| 2026-01-14 | DTO 不包含业务逻辑 | 严格分层，DTO 仅用于传输 |
| 2026-01-22 | 从 PHP 移植测试用例 | 确保 Java 实现与 PHP 行为一致 |
| 2026-01-22 | 实现 ACO/ARO 权限模型 | 完整移植 Passbolt 共享功能 |

---

## 11. 开发路线图 (优先级排序)

### 已完成 ✅
1. 项目结构搭建 + Maven 依赖
2. 数据模型与 JPA 实体
3. GPG 认证 (Stage 0/1/2) + Bouncy Castle 集成
4. JWT 认证（HS256 会话 + RS256 JWT 登录 `/auth/jwt/*`）
5. Resource CRUD
6. Secret 管理
7. Permission/Share 功能（含 simulate / search-aros / Group 共享）
8. 集成测试框架
9. **49 端点全功能移植**（git log `d9d8247..HEAD`，wave1~4）：
   - Wave1: settings / resource-types(读) / roles / gpgkeys / comments / favorites / avatars / healthcheck
   - Wave2: users CRUD + setup（注册/恢复）、groups CRUD（含 dry-run）
   - Wave3: share 扩展（simulate / search-aros）、folders CRUD + move
   - Wave4: group 共享、auth 扩展（RS256 JWT login/refresh/logout/rsa/is-authenticated）、MFA TOTP（含 `MfaEnforcementFilter` 强制校验）

### 待实现 🔲
- v5 Metadata 体系（26 端点）
- EE Tags（3 端点）
- resource-types 写端点（v5-only）
- 前端页面扩展（目前仅 Login / Dashboard）

---

## 12. 编码规范速查

- **风格**: Google Java Style Guide
- **异常**: 可恢复用受检异常 (如 `PassboltApiException`)，运行时问题用非受检异常
- **文档**: 所有公共方法必须包含 Javadoc
- **不可变性**: 尽可能使用 `final` 字段和不可变集合
- **日志**: 使用 Lombok `@Slf4j`
- **事务**: Service 层方法使用 `@Transactional`，只读查询加 `readOnly = true`