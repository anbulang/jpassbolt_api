package com.jpassbolt.api.util;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 共享的统一响应信封工厂（替代此前散落在 ~19 个控制器中的私有 createResponse 副本）。
 *
 * <p>权威定义见 OpenAPI {@code components/schemas/header}
 * （docs/ref_files/plugin-redoc-0.yaml）。header 的 7 个 required 字段全部输出：
 * id(uuid) / status / servertime / action(uuid) / message / url / code。</p>
 *
 * <p>设计要点：
 * <ul>
 *   <li>用 {@link LinkedHashMap} 保证键序稳定（取代各副本的 {@code Map.of}，后者键序不保证且不可空）。</li>
 *   <li>{@code action} 由 url 派生出稳定 UUID（{@link #actionFor}）：同一端点每次返回同一个合法 uuid，
 *       满足 spec {@code format: uuid} 且具备幂等性，无需为每个端点硬编码占位 uuid。</li>
 *   <li>{@code servertime} 统一为 Unix 秒（epoch seconds，整数），与 spec {@code type: integer} 一致。</li>
 *   <li>body 的空值回退策略由调用方按端点语义显式选择，避免隐式默认造成各控制器不一致：
 *       多数端点用 {@link #success}/{@link #error}（null→{}），
 *       Favorite/Setup 用 {@link #passthrough}（透传 body，含 null），
 *       Share/Users/Mfa 的 nullBody 端点用 {@link #nullBody}（body 固定 JSON null）。</li>
 *   <li>code 不再固定 200/400：调用方按真实语义传入；提供 {@link #success}(200)/{@link #error}(400)
 *       便捷方法，以及 {@link #withCode}（如 Mfa 的 403、异常处理器的真实 HTTP 状态码）。</li>
 *   <li>Auth/JwtAuth 历史上 code 恒为 200（含 error 分支）且自带固定 action：用
 *       {@link #withExplicitAction} 显式传入 action 与 code，保留该既有偏差。</li>
 * </ul></p>
 *
 * <p>纯传输辅助，不含业务逻辑（遵守 DTO/工具类无业务逻辑铁律）。</p>
 */
public final class ApiResponse {

    private ApiResponse() {
    }

    /**
     * 由端点 url 派生稳定的 action UUID（type-3 / name-based UUID）。
     * 同一 url 永远得到同一个合法 uuid，满足 spec {@code action: format uuid} 的同时保持幂等。
     */
    public static String actionFor(String url) {
        String seed = url != null ? url : "/";
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * 成功信封：code=200，body 为 null 时回退为空对象 {}。action 由 url 派生。
     */
    public static Map<String, Object> success(String message, Object body, String url) {
        return envelope("success", message, body != null ? body : new LinkedHashMap<>(),
                200, actionFor(url), url);
    }

    /**
     * 错误信封：code=400，body 为 null 时回退为空对象 {}。action 由 url 派生。
     */
    public static Map<String, Object> error(String message, Object body, String url) {
        return envelope("error", message, body != null ? body : new LinkedHashMap<>(),
                400, actionFor(url), url);
    }

    /**
     * 通用信封：显式传入 status/code/body，body 为 null 时回退为空对象 {}。action 由 url 派生。
     * 用于 status 与 code 需手动对齐真实 HTTP 状态的端点（如 Permissions/Users）。
     */
    public static Map<String, Object> withCode(String status, String message, Object body, int code, String url) {
        return envelope(status, message, body != null ? body : new LinkedHashMap<>(),
                code, actionFor(url), url);
    }

    /**
     * body 透传信封（不对 null 做空对象回退）：用于 Favorite/Setup —— body 直接按原样写入，
     * code 取 success→200 / 其余→400。action 由 url 派生。
     */
    public static Map<String, Object> passthrough(String status, String message, Object body, String url) {
        return envelope(status, message, body, "success".equals(status) ? 200 : 400, actionFor(url), url);
    }

    /**
     * nullBody 信封：body 固定为 JSON null（OpenAPI responses/nullBody，PHP success() 无 data 时 "body": null）。
     * code 取 success→200 / 其余→400。用于 Share/Users/Mfa 的 nullBody 端点（有意偏差）。action 由 url 派生。
     */
    public static Map<String, Object> nullBody(String status, String message, String url) {
        return envelope(status, message, null, "success".equals(status) ? 200 : 400, actionFor(url), url);
    }

    /**
     * 显式 action + 显式 code 信封（body 透传）：用于 Auth/JwtAuth —— 它们历史上 code 恒为 200
     * 且自带固定的 action UUID，迁移时通过参数显式传入以保留既有偏差。
     */
    public static Map<String, Object> withExplicitAction(String status, String message, Object body,
            int code, String action, String url) {
        return envelope(status, message, body, code, action, url);
    }

    /**
     * 信封核心：用 LinkedHashMap 保序生成 header（含全部 7 个 required 字段）+ body。
     * id 每次随机 UUID；servertime 为当前 Unix 秒；action 为合法 UUID（不可空）。
     */
    private static Map<String, Object> envelope(String status, String message, Object body,
            int code, String action, String url) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("id", UUID.randomUUID().toString());
        header.put("status", status);
        header.put("servertime", System.currentTimeMillis() / 1000);
        header.put("action", action != null ? action : actionFor(url));
        header.put("message", message);
        header.put("url", url);
        header.put("code", code);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("header", header);
        response.put("body", body);
        return response;
    }
}
