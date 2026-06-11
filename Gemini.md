# JPassbolt 项目记忆 (Project Memory)

## 1. 项目概况
**项目名称**: JPassbolt (Java Port of Passbolt API)
**目标**: 创建一个基于 Java (Spring Boot) 的 Passbolt API 兼容实现，完全兼容 Passbolt 前端和浏览器插件。
**版本**: 1.0.0 (开发中)

## 2. 关键配置与环境
### 数据库 (Database)
* **类型**: MySQL 8.
* **环境**: 远程测试数据库
  * **Host**: `REDACTED-DB-HOST`
  * **Port**: `3307`
  * **Database**: `jpassbolt`
  * **User**: `root`
  * **Password**: `REDACTED-ROTATED-DB-PW`
* **Schema**: 兼容 Passbolt v3/v4 官方 Schema

### 认证 (Authentication)
* **GPG 认证**: 仅使用 Bouncy Castle 库 (无外部 gpg 命令调用)
  * **Stage 0**: Server Verify (验证服务器公钥)
  * **Stage 1**: Login Challenge (获取加密 Nonce)
  * **Stage 2**: Authenticate (验证解密后的 Nonce)
* **JWT**: 认证成功后颁发 JWT Token (HS256 签名)

### 关键路径
* **API 前缀**: `/api` (例如 `/api/auth/login.json`)
* **文档**: `docs/ref_docs/`
  * `01_database_config_and_entities.md`: 数据库与实体
  * `02_gpg_auth_flow.md`: GPG 认证流程
  * `03_bouncy_castle_encryption.md`: 加密实现细节
  * `04_jwt_authentication.md`: JWT 实现细节

## 3. 开发规范
* **API 兼容性**: 必须通过 `AuthControllerCompatibilityTest` 测试
* **数据库迁移**: `jpa.hibernate.ddl-auto` 设置为 `validate` (严禁 `update`/`create`)
* **测试**: 所有新功能必须包含对应的 Integration Test

## 4. 参考资源
* **PHP 参考代码**: `passbolt_api_ref/` 目录
* **API 对比脚本**: `scripts/api_comparison.sh`

## 5. 交互规范
* **回复语言**: 始终使用中文回复用户
* **本地开发**: 远程 MySQL 不可达时，使用 `--spring.profiles.active=local` 启动 H2 内存数据库
