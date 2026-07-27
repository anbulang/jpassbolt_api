# 开发/测试用 GPG 密钥 —— 有意公开，非泄露

本目录下的 `.asc` 文件（除 `server_private.asc`，见下）是**故意提交到公开仓库**的
dev/test fixture。它们的 passphrase 同样公开，不保护任何真实数据。

如果密钥扫描服务（GitGuardian、GitHub Secret Scanning、TruffleHog 等）对本目录
告警，那是**预期内的已知情况**，不是安全事件。

## 为什么可以公开

1. **只种进嵌入式 H2 库**。播种这些密钥的 `DataInitializer` 上有
   `@Profile("local")`，但**光看 profile 名不足以保证安全** —— 若以
   `SPRING_PROFILES_ACTIVE=local,mysql` 启动，`local` 仍会激活该初始化器，而
   `mysql` 会把数据源切到真实（可能远程）库，于是 ada admin（私钥+passphrase 已
   公开入库）会被种进真实库，等于把 admin 登录送给任何拿到本仓的人。
   因此 `DataInitializer.run()` 开头有一道**构造级守卫**：运行时检查实际连接，
   只有 URL 为 `jdbc:h2:` 才播种，否则大声告警并跳过（`config/DataInitializer.java`）。
   安全属性由此守卫保证，而非仅靠 profile 名。
2. **不保护任何真实机密**。它们加密的只有 `DataInitializer` 写入的演示资源。
3. **与官方 Passbolt 做法一致**。上游 `passbolt_api_ref` 的 TestData 同样附带
   公开的 betty / dame / frances 等测试密钥，且约定 passphrase 即用户邮箱。

## 铁律：生产环境绝不使用

生产部署的服务端 GPG 私钥与 JWT 私钥**必须**经环境变量 / 外部路径注入，绝不入库。
默认 profile 下 GPG 加密私钥需 passphrase 才能解出，且 RS256 JWT 密钥对不再随仓
附带、缺失即启动失败（`application.yml` 的 jwt / gpg 段），故生产**至少**需要以下五项：

```
JPASSBOLT_GPG_PRIVATE_KEY_LOCATION=file:/secure/path/server_private.asc
JPASSBOLT_GPG_PUBLIC_KEY_LOCATION=file:/secure/path/server_public.asc
JPASSBOLT_GPG_PASSPHRASE=<服务端 GPG 私钥的 passphrase>
JPASSBOLT_JWT_PRIVATE_KEY_LOCATION=file:/secure/path/jwt_private.pem
JPASSBOLT_JWT_PUBLIC_KEY_LOCATION=file:/secure/path/jwt_public.pem
```

（`allow-ephemeral-dev-key` 仅 local/test profile 可开；生产严禁。）

## 文件清单

| 文件 | 用途 | passphrase | 入库 |
|------|------|-----------|------|
| `server_public.asc` | 服务端身份公钥（GpgAuth stage0 校验用） | — | ✅ |
| `server_private.asc` | 服务端身份私钥，**仅本机** | 见 `application-local.yml` | ❌ 已 gitignore |
| `ada_public.asc` / `ada_private.asc` | `ada@passbolt.com`（admin）登录钥，浏览器 / E2E 用 | `password` | ✅ |
| `demo_metadata_public.asc` / `demo_metadata_private.asc` | v5 跨用户元数据共享钥（既非服务器钥也非用户钥） | `password` | ✅ |
| `betty_public.asc` | `betty@passbolt.com` 公钥，私钥取自 `passbolt_api_ref` TestData | `betty@passbolt.com` | ✅（仅公钥） |
| `fixtures/*.asc` | dame / edith / frances 等演示用户公钥 | 用户邮箱 | ✅（仅公钥） |

`server_private.asc` 是唯一被排除的私钥：它是服务端身份钥，一旦公开，任何人都能
冒充本服务完成 GpgAuth stage0，性质与上面几把用户钥不同。克隆仓库后需自行生成或
经上述环境变量注入。

## 新增密钥时

往本目录添加**私钥**前，先判断它属于哪一类：

- **用户 / 演示钥**（只在 `@Profile("local")` 中播种，不影响真实环境）→ 可提交，
  并在上表登记。
- **服务端身份钥、生产钥、任何真实环境在用的钥** → 加入 `.gitignore`，经环境变量
  注入。

判断依据是"这把钥匙泄露后，攻击者能拿它做什么"，而不是"它现在是不是测试用的"。
