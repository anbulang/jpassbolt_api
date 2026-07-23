# JPassbolt 部署指南 — HTTPS 终止与安全响应头

> 结论先行：**JPassbolt 不做应用层 HTTPS 强制**。HTTP→HTTPS 重定向、TLS 终止、HSTS
> 全部由反向代理负责；应用侧只通过 `APP_FULL_BASE_URL=https://…` 与
> `X-Forwarded-Proto` 感知自己正处在 https 之下。

## 1. 为什么应用不做重定向

官方 Passbolt (CakePHP) 有 `App.forceSSL`，但本项目刻意不复刻：

- 应用层 `sendRedirect` / `requiresChannel(HTTPS)` 会直接破坏本地明文 http 开发
  （默认 `full-base-url=http://localhost:8090`）；
- 在一个配置正确的反代之后，重定向早已在边缘发生，应用再判一次是冗余的；
- 反代是唯一知道「客户端那一跳到底是不是 TLS」的组件——应用只能相信
  `X-Forwarded-Proto`。

因此 `GET /api/healthcheck.json` 里 `application.sslForce` **恒为 `false`**，这不是
缺陷，是声明（见 `service/HealthcheckService.java` 注释）。`application.sslFullBaseUrl`
则如实反映 `full-base-url` 是否为 `https://` 开头。

同理 `ssl.peerValid` / `ssl.hostValid` / `ssl.notSelfSigned` 恒为 `false`，含义是
**「未执行证书校验」而非「证书无效」**：JPassbolt 从不对自己发起出站 TLS 握手。
证书有效性请在反代侧校验。

## 2. 应用必须知道的两件事

| 配置 | 说明 |
|------|------|
| `APP_FULL_BASE_URL` (`jpassbolt.settings.full-base-url`) | 公网 origin，例如 `https://vault.example.com`。浏览器扩展用它做可信域匹配，必须与用户实际访问的 origin 完全一致（含 scheme）。 |
| `X-Forwarded-Proto: https` | 反代终止 TLS 后转发明文 http 时**必须**带上。缺失会导致：① `passbolt_mfa` cookie 丢失 `Secure` 标记；② 骨架页误报「不安全模式」横幅。 |
| `X-Forwarded-Host` | 多级代理时携带原始 Host；影响外发邮件里的恢复链接 origin（见 `service/PublicBaseUrlResolver.java` 的可信主机白名单）。 |
| `jpassbolt.app.public-base-url` / `jpassbolt.app.trusted-hosts` | 生产环境建议显式设置其一，防止 Host 头注入污染找回密码链接。 |

只绑回环地址（项目基线）：`SERVER_ADDRESS=127.0.0.1`，由反代跨网络暴露。

## 3. 不安全模式横幅

骨架页（`resources/skeleton/app.html`）在**同时满足**下列条件时，会在页脚显示
中英双语的「不安全模式」警示条：

1. 请求不是 https（`request.isSecure()` 为假 **且** `X-Forwarded-Proto` 不是 `https`）；
2. 主机名不是回环地址（不是 `localhost` / `127.0.0.0/8` 字面量 / `::1` / `*.localhost`）。

即：本地开发永不显示，生产明文 http 部署才显示。判定在**服务端**完成
（`SkeletonPageConfig.SkeletonPageServlet#isUnsafeMode`），因为客户端 `location.protocol`
判断恰好能被它要警告的那个中间人篡改掉。

## 4. Nginx 反代样例

```nginx
# ---- 80: 只做跳转，不服务任何内容 ----
server {
    listen 80;
    listen [::]:80;
    server_name vault.example.com;

    # ACME 质询走明文，其余全部 301 到 https
    location /.well-known/acme-challenge/ { root /var/www/certbot; }
    location / { return 301 https://$host$request_uri; }
}

# ---- 443: TLS 终止 ----
server {
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;
    server_name vault.example.com;

    ssl_certificate     /etc/letsencrypt/live/vault.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/vault.example.com/privkey.pem;

    # 仅 TLS 1.2 / 1.3；1.3 的套件由 OpenSSL 固定，无需配置
    ssl_protocols             TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;
    ssl_ciphers               ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305;

    ssl_session_timeout 1d;
    ssl_session_cache   shared:SSL:10m;
    ssl_session_tickets off;

    # OCSP stapling
    ssl_stapling        on;
    ssl_stapling_verify on;

    # HSTS —— 由反代负责，应用不发这个头。
    # 先用小 max-age 灰度，确认全站 https 后再上调到 2 年。
    # preload / includeSubDomains 会影响整个域名，上线前务必确认所有子域已 https。
    add_header Strict-Transport-Security "max-age=63072000; includeSubDomains" always;

    location / {
        proxy_pass http://127.0.0.1:8090;

        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        # 缺了这两行，应用就会以为自己在明文 http 上（见第 2 节）
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host  $host;

        proxy_http_version 1.1;
    }
}
```

> `X-Forwarded-Proto` / `X-Forwarded-Host` 是**客户端可伪造**的头。上面的
> `proxy_set_header` 会用反代自己的值**覆盖**客户端传入的同名头——这是安全前提，
> 不要改成 `$http_x_forwarded_proto` 之类的透传写法。若前面还有一层 CDN/LB，
> 应用只取这些头的第一个值，且只接受 `http`/`https` 两个 token
> （`util/HttpRequestSecurity#safeScheme`）。

## 5. 应用自己发的安全响应头

与官方 Passbolt（CakePHP `SecurityHeadersMiddleware`，默认参数）逐字对齐，
`/api` 与骨架页两个 Tomcat context 都会发送：

| 头 | 值 | 来源 |
|----|----|------|
| `X-Content-Type-Options` | `nosniff` | `noSniff()` |
| `X-Download-Options` | `noopen` | `noOpen()` |
| `X-Permitted-Cross-Domain-Policies` | `all` | `setCrossDomainPolicy()` 默认值 |
| `Referrer-Policy` | `same-origin` | `setReferrerPolicy()` 默认值 |
| `X-Frame-Options` | `SAMEORIGIN` | `setXFrameOptions()` 默认值 |

定义见 `config/SecurityHeaders.java`。

- **`X-Permitted-Cross-Domain-Policies: all`** 是官方值。它只对早已 EOL 的
  Adobe Flash / Acrobat 生效，且本服务不提供 `/crossdomain.xml`，因此实际惰性。
  想收紧的运维可在反代 `add_header X-Permitted-Cross-Domain-Policies none always;`
  覆盖。
- **`X-Frame-Options: SAMEORIGIN`** 不影响浏览器扩展接管：扩展是把一个
  `chrome-extension://` origin 的 iframe **塞进**骨架页，而 `X-Frame-Options`
  约束的是「谁能框住本响应」，方向相反。
- **HSTS 不由应用发送**——它必须只在 https 响应上出现，而应用无法可靠判断这一点。
- **CSP 未设置**：骨架页目前使用内联 `<style>` / `<script>`，加 CSP 需先把它们外链化，
  属于后续工作。

## 6. 服务器密钥固定（SP-11）与域名切换

浏览器扩展会把**服务器的 OpenPGP 公钥**钉死（pin）到它的 origin，并在**每次解锁**时
跑 GpgAuth Stage 0：用钉死的公钥加密一个 nonce，要求服务器回显解密后的明文
(`X-GPGAuth-Verify-Response`)。拿不出明文 = 没有那把私钥 = 不是真服务器 → 拒绝登录。

这防的不是"服务器看到明文"（E2EE 已经防了），而是**服务器身份被冒充**：服务器是公钥
交换的中间人，冒充者可以在你分享密码时把收件人公钥换成自己的，让你把密码加密给攻击者。

### 6.1 pin 绑定到 origin，不只是域名

pin 记录 `origin = scheme + host + port`（`background/serverKey.ts` 的 `PinnedServerKey.origin`）：

- `http://localhost:8090` 与 `https://vault.example.com` 是**不同 origin** → 互不套用。
- 用 `new URL().origin` 归一化，所以 `https://vault.example.com`、`…/`（尾斜杠）、
  `…:443`（https 默认端口）三种写法**等价**，不会误判为换服务器。
- 非默认端口是不同 origin：`https://vault.example.com:8443` ≠ `https://vault.example.com`。

### 6.2 从 localhost 切到生产域名会发生什么

**pin 自愈，无需手工迁移**：扩展指向新 origin 时（`SET_SERVER`），旧 pin 因 origin 不同
被清除，下次登录对生产 origin 重新 TOFU 钉死生产服务器的钥。

**但首次连接必须可信**。信任模型是 TOFU（trust on first use）：第一次钉死发生在
setup/recover 流程——那是经**邮件一次性 token 链接**（带外通道）到达的，网络中间人
控制不了它。TOFU 数学上防不住"首次投毒"，只能靠 **HTTPS 有效证书 + 邮件带外通道**
把窗口压到最小。若生产首连就被中间人劫持，钉死的会是攻击者的钥。

### 6.3 上生产清单

| 项 | 要做什么 | 不做的后果 |
|----|---------|-----------|
| `APP_FULL_BASE_URL` | 设成 `https://vault.example.com`（邮件链接是**纯配置源**，不看请求头） | 邀请/恢复邮件链接仍指向 localhost:8090，点了 404 |
| 真 HTTPS + 反代 | 有效证书 + HSTS；反代设 `X-Forwarded-Proto: https`（见第 2、4 节） | ① SP-11 的 TOFU 锚点不可信 ② MFA/refresh cookie 丢 `Secure` ③ 误报不安全模式横幅 |
| **生产服务器 GPG 钥** | 生产必须**自己安全生成**服务器密钥对，绝不用仓库里的 dev fixture 钥 | 私钥公开 = server-verify 信任全崩；SP-11 忠实地把一把人人可冒充的钥钉死 |
| JWT `iss` | `iss` 由 full-base-url 派生，换域名后**已签发 token 全失效**，用户需重登 | 换域名当下所有会话 401（一次性，预期行为） |
| 持久化 MySQL | `ddl-auto: validate`，Schema 须先建好；种子 `DataInitializer` 是 `@Profile("local")`，**生产不跑** | 缺表/列型不符则启动失败；生产无 demo 数据（正常） |
| 扩展配置 URL | 填标准 `https://vault.example.com`（无需带 `:443`） | 非标端口会被当成不同 origin，pin 不复用 |

### 6.4 服务器密钥轮换（运维换钥）

轮换服务器 GPG 钥会让**所有已钉死的老用户**在下次解锁时被 Stage 0 拒绝并看到
「服务器密钥已变更」告警——这是**设计如此**，不是 bug：合法轮换与中间人攻击在客户端
看来无法区分，所以唯一出口是让用户**重走恢复流程**（再经一次邮件带外通道）重新钉死。

因此：**轮换服务器密钥前先规划好用户重新 onboarding 的通知与流程**，否则等同于全员锁死。
（这也是官方 Passbolt 接受的代价。）
