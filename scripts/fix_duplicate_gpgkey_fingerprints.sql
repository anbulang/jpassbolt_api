-- ---------------------------------------------------------------------------
-- 一钥两号脏数据清理（2026-07-10）
--
-- 背景：DataInitializer 曾把 frances 测试公钥（指纹
-- 98DA33350692F21BD5F83A17E8DC5617477FB14C，UID "Frances Allen
-- <frances@passbolt.com>"）种给 anbulang1@gmail.com，而 frances@passbolt.com
-- 此前已用同一把钥完成注册 —— 两条 active 的 gpgkeys 行共享一个指纹，
-- frances_private.key 因此能登录两个账号。代码侧已在 SetupService（全表查重，
-- 含软删）与 DataInitializer（种子查重）封堵；本脚本清理已落库的存量脏数据。
--
-- ⚠ 只在【持久化 MySQL】上需要执行（H2 create-drop 环境每次重启已被新种子
--    逻辑保护）。执行前请备份 gpgkeys / users 两表。
-- ⚠ 密钥材料层面：同钥期间两个账号的全部 Secret 都加密给同一把公钥，持有
--    frances 私钥的人可解两个金库。若这两个账号存过真实机密，处理完本脚本后
--    应轮换相关密码（按"已互相共享"处置）。
-- ---------------------------------------------------------------------------

-- 第 0 步：确认现状（预期：frances@passbolt.com 与 anbulang1@gmail.com 各一行）
SELECT g.id, g.fingerprint, g.deleted, u.username, u.active, u.deleted AS user_deleted
FROM gpgkeys g
JOIN users u ON u.id = g.user_id
WHERE g.fingerprint IN (
    SELECT fingerprint FROM (
        SELECT fingerprint FROM gpgkeys WHERE deleted = 0
        GROUP BY fingerprint HAVING COUNT(*) > 1
    ) dup
)
ORDER BY g.fingerprint, u.username;

-- 第 1 步：保留钥主（frances@passbolt.com —— 钥的 UID 本人），
-- 软删 anbulang1@gmail.com 的同指纹 key 行（保留审计痕迹，不物理删除）
UPDATE gpgkeys g
JOIN users u ON u.id = g.user_id
SET g.deleted = 1
WHERE u.username = 'anbulang1@gmail.com'
  AND g.fingerprint = '98DA33350692F21BD5F83A17E8DC5617477FB14C'
  AND g.deleted = 0;

-- 第 2 步：失活 anbulang1 账号（无 key 的 active 账号无法登录，但显式失活更干净；
-- 如需保留该账号做恢复演示，请改为走一次 recover 流程换绑一把全新密钥）
UPDATE users SET active = 0
WHERE username = 'anbulang1@gmail.com' AND deleted = 0;

-- 第 3 步：吊销 anbulang1 的存量刷新/登录 token（防止已签发会话继续有效）
UPDATE authentication_tokens t
JOIN users u ON u.id = t.user_id
SET t.active = 0
WHERE u.username = 'anbulang1@gmail.com' AND t.active = 1;

-- 第 4 步：复核 —— 应返回 0 行
SELECT fingerprint, COUNT(*) AS c
FROM gpgkeys WHERE deleted = 0
GROUP BY fingerprint HAVING c > 1;

-- 说明：指纹 98DA…B14C 从此永久"烧毁"（SetupService 全表查重含软删行，与官方
-- GpgkeysTable isUnique 语义一致）——anbulang1 重新启用时必须用一把全新密钥。
