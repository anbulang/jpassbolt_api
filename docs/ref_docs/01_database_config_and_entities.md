# 数据库配置与实体映射

本文档记录 JPassbolt API 与 Passbolt 数据库的配置和实体映射关系。

## 数据库连接配置

### MySQL 配置文件

**文件**: [application-mysql.yml](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/src/main/resources/application-mysql.yml)

```yaml
spring:
  datasource:
    url: ${JPASSBOLT_DB_URL}            # 原硬编码远程 MySQL 已泄露并轮换，改 env 注入
    username: ${JPASSBOLT_DB_USERNAME}
    password: ${JPASSBOLT_DB_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: validate  # 不修改现有表结构
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQLDialect
```

### Maven 依赖

**文件**: [pom.xml](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/pom.xml)

```xml
<!-- MySQL Connector -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- PostgreSQL (原有) -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

### 启动方式

```bash
# 使用 MySQL 配置
mvn spring-boot:run -Dspring-boot.run.profiles=mysql

# 使用默认 PostgreSQL 配置
mvn spring-boot:run
```

---

## 实体映射

### GpgKey 实体

**文件**: [GpgKey.java](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/src/main/java/com/jpassbolt/api/model/GpgKey.java)

| 数据库字段 | Java 属性 | 类型 | 说明 |
|-----------|----------|------|------|
| `id` | `id` | `CHAR(36)` | 主键 UUID |
| `user_id` | `userId` | `CHAR(36)` | 关联用户 ID |
| `armored_key` | `armoredKey` | `TEXT` | ASCII Armor 格式的公钥 |
| `bits` | `bits` | `INT` | 密钥位数 (2048/3072/4096) |
| `uid` | `uid` | `VARCHAR(769)` | 用户标识 (Name <email>) |
| `key_id` | `keyId` | `VARCHAR(16)` | 密钥 ID (16 字符) |
| `fingerprint` | `fingerprint` | `VARCHAR(51)` | 密钥指纹 (40 字符) |
| `type` | `type` | `VARCHAR(16)` | 密钥类型 (RSA) |
| `expires` | `expires` | `DATETIME` | 过期时间 |
| `key_created` | `keyCreated` | `DATETIME` | 密钥创建时间 |
| `deleted` | `deleted` | `TINYINT(1)` | 软删除标记 |
| `created` | `created` | `DATETIME` | 创建时间 |
| `modified` | `modified` | `DATETIME` | 修改时间 |

**关键修改**:
```diff
- @Column(name = "\"key\"", nullable = false, columnDefinition = "TEXT")
- private String key;
+ @Column(name = "armored_key", nullable = false, columnDefinition = "TEXT")
+ private String armoredKey;

- @Column(name = "uid", nullable = false, length = 128)
+ @Column(name = "uid", nullable = false, length = 769)
  private String uid;

- @Column(name = "key_id", nullable = false, length = 8)
+ @Column(name = "key_id", nullable = false, length = 16)
  private String keyId;
```

---

### User 实体

**文件**: [User.java](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/src/main/java/com/jpassbolt/api/model/User.java)

| 数据库字段 | Java 属性 | 类型 | 说明 |
|-----------|----------|------|------|
| `id` | `id` | `CHAR(36)` | 主键 UUID |
| `role_id` | `roleId` | `CHAR(36)` | 角色 ID |
| `username` | `username` | `VARCHAR(255)` | 用户名 (邮箱) |
| `active` | `active` | `TINYINT(1)` | 是否激活 |
| `deleted` | `deleted` | `TINYINT(1)` | 软删除标记 |
| `disabled` | `disabled` | `DATETIME` | 禁用时间 |
| `created` | `created` | `DATETIME` | 创建时间 |
| `modified` | `modified` | `DATETIME` | 修改时间 |

**关键修改**:
```diff
+ @Column(name = "disabled")
+ private LocalDateTime disabled;

- @Column(name = "username", nullable = false, length = 50, unique = true)
+ @Column(name = "username", nullable = false, length = 255, unique = true)
  private String username;
```

---

### AuthenticationToken 实体

**文件**: [AuthenticationToken.java](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/src/main/java/com/jpassbolt/api/model/AuthenticationToken.java)

| 数据库字段 | Java 属性 | 类型 | 说明 |
|-----------|----------|------|------|
| `id` | `id` | `CHAR(36)` | 主键 UUID |
| `token` | `token` | `CHAR(36)` | Token UUID |
| `user_id` | `userId` | `CHAR(36)` | 关联用户 ID |
| `type` | `type` | `VARCHAR(16)` | Token 类型 |
| `data` | `data` | `TEXT` | 附加数据 (JSON) |
| `active` | `active` | `TINYINT(1)` | 是否有效 |
| `created` | `created` | `DATETIME` | 创建时间 |
| `modified` | `modified` | `DATETIME` | 修改时间 |

**Token 类型** (参考 PHP [AuthenticationToken.php](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/passbolt_api_ref/src/Model/Entity/AuthenticationToken.php)):
- `login` - GPG 认证登录
- `register` - 用户注册
- `recover` - 密码恢复
- `mfa` - 多因素认证
- `refresh_token` - JWT 刷新

---

### Role 实体

**文件**: [Role.java](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/src/main/java/com/jpassbolt/api/model/Role.java)

| 数据库字段 | Java 属性 | 类型 | 说明 |
|-----------|----------|------|------|
| `id` | `id` | `CHAR(36)` | 主键 UUID |
| `name` | `name` | `VARCHAR(50)` | 角色名称 |
| `description` | `description` | `VARCHAR(255)` | 角色描述 |
| `created` | `created` | `DATETIME` | 创建时间 |
| `modified` | `modified` | `DATETIME` | 修改时间 |

**预定义角色**:
- `admin` - 管理员
- `user` - 普通用户
- `guest` - 访客

---

### BaseEntity 基类

**文件**: [BaseEntity.java](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/src/main/java/com/jpassbolt/api/model/BaseEntity.java)

```java
@Data
@MappedSuperclass
public abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "created", nullable = false, updatable = false)
    private LocalDateTime created;

    @Column(name = "modified", nullable = false)
    private LocalDateTime modified;
}
```

**关键修改**: 移除了 `created_by` 和 `modified_by` 字段（Passbolt 表结构中不存在）。

---

## Repository 接口

### GpgKeyRepository

**文件**: [GpgKeyRepository.java](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/src/main/java/com/jpassbolt/api/repository/GpgKeyRepository.java)

```java
@Repository
public interface GpgKeyRepository extends JpaRepository<GpgKey, String> {
    List<GpgKey> findByUserId(String userId);
    Optional<GpgKey> findByKeyId(String keyId);
    Optional<GpgKey> findByFingerprint(String fingerprint);
    
    // 认证时使用的查询方法
    Optional<GpgKey> findByFingerprintAndDeletedFalse(String fingerprint);
    Optional<GpgKey> findByKeyIdAndDeletedFalse(String keyId);
}
```

---

## 数据库表关系

```
┌──────────────┐         ┌──────────────┐
│    roles     │         │    users     │
├──────────────┤         ├──────────────┤
│ id (PK)      │◄────────│ role_id (FK) │
│ name         │         │ id (PK)      │
│ description  │         │ username     │
└──────────────┘         │ active       │
                         │ deleted      │
                         │ disabled     │
                         └──────┬───────┘
                                │
              ┌─────────────────┼─────────────────┐
              │                 │                 │
              ▼                 ▼                 ▼
    ┌──────────────────┐ ┌──────────────────┐ ┌──────────────┐
    │     gpgkeys      │ │  auth_tokens     │ │   profiles   │
    ├──────────────────┤ ├──────────────────┤ ├──────────────┤
    │ id (PK)          │ │ id (PK)          │ │ id (PK)      │
    │ user_id (FK)     │ │ user_id (FK)     │ │ user_id (FK) │
    │ armored_key      │ │ token            │ │ first_name   │
    │ fingerprint      │ │ type             │ │ last_name    │
    │ key_id           │ │ active           │ └──────────────┘
    └──────────────────┘ └──────────────────┘
```

---

## 查询数据库示例

```bash
# 连接数据库
mysql -h "$JPASSBOLT_DB_HOST" -P "$JPASSBOLT_DB_PORT" -u "$JPASSBOLT_DB_USERNAME" -p jpassbolt   # 凭据经 env 注入（原硬编码值已泄露并轮换）

# 查看用户及其 GPG 密钥
SELECT u.id, u.username, g.fingerprint, g.key_id
FROM users u
JOIN gpgkeys g ON u.id = g.user_id
WHERE u.deleted = 0 AND g.deleted = 0;

# 查看认证 token
SELECT id, token, user_id, type, active, created
FROM authentication_tokens
ORDER BY created DESC
LIMIT 10;
```
