-- =====================================================================
-- sql-pipeline 平台元数据初始化脚本（PostgreSQL）
-- =====================================================================
-- 幂等性：建表 IF NOT EXISTS、补列 ADD COLUMN IF NOT EXISTS、示例数据 WHERE NOT EXISTS，
--         可随应用每次启动重复执行。
-- 章节：1 数据源  2 健康检查  3 发布  4 告警通知  5 示例数据（Sample Data）
-- 注意：本脚本不含任何连接密码——连接请通过 Web 界面「连接管理」创建（密码自动 AES-256-GCM 加密）。
-- =====================================================================

-- =====================================================================
-- 1. 数据源（目标库连接定义，仅 PostgreSQL）
-- =====================================================================

CREATE TABLE IF NOT EXISTS db_connection (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conn_key        VARCHAR(64)  NOT NULL UNIQUE,
    display_name    VARCHAR(128) NOT NULL,
    jdbc_url        VARCHAR(512) NOT NULL,
    username        VARCHAR(64)  NOT NULL,
    password_enc    VARCHAR(512) NOT NULL,
    driver_class    VARCHAR(128) NOT NULL DEFAULT 'org.postgresql.Driver',
    default_schema  VARCHAR(64),
    pool_size       INT          NOT NULL DEFAULT 10,
    conn_timeout_ms INT          NOT NULL DEFAULT 5000,
    max_rows        INT          NOT NULL DEFAULT 1000,
    query_timeout_s INT          NOT NULL DEFAULT 30,
    enabled         SMALLINT     NOT NULL DEFAULT 1,
    created_by      VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_by      VARCHAR(64),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_conn_enabled ON db_connection (enabled);
-- 兼容存量库：补列
ALTER TABLE db_connection ADD COLUMN IF NOT EXISTS default_schema VARCHAR(64);

COMMENT ON TABLE  db_connection                IS '数据库连接定义（目标库，仅 PostgreSQL）';
COMMENT ON COLUMN db_connection.conn_key       IS '平台内引用名（健康检查/发布计划通过它引用连接）';
COMMENT ON COLUMN db_connection.password_enc   IS 'AES-256-GCM 加密后的密码（接口仅返回掩码）';
COMMENT ON COLUMN db_connection.default_schema IS '连接默认 Schema（空=驱动默认 search_path），建池时经 Hikari setSchema 生效';
COMMENT ON COLUMN db_connection.max_rows       IS '健康检查查询最大行数';
COMMENT ON COLUMN db_connection.enabled        IS '1 启用 / 0 禁用（软删，禁用时连接池同步移除）';

-- =====================================================================
-- 2. 健康检查
-- =====================================================================

-- 2.1 健康检查 SQL 定义
CREATE TABLE IF NOT EXISTS sql_definition (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    conn_key      VARCHAR(64)  NOT NULL,
    sql_text      TEXT         NOT NULL,
    params_json   JSONB,
    assert_type   VARCHAR(16),
    assert_op     VARCHAR(8),
    assert_value  NUMERIC,
    assert_config JSONB,
    notify_channel_id BIGINT,
    cron_expr     VARCHAR(64),
    timeout_sec   INT          NOT NULL DEFAULT 30,
    enabled       SMALLINT     NOT NULL DEFAULT 1,
    version       INT          NOT NULL DEFAULT 1,
    created_by    VARCHAR(64),
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_by    VARCHAR(64),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sql_def_conn ON sql_definition (conn_key);
-- 兼容存量库：补列
ALTER TABLE sql_definition ADD COLUMN IF NOT EXISTS assert_op VARCHAR(8);
ALTER TABLE sql_definition ADD COLUMN IF NOT EXISTS assert_value NUMERIC;
ALTER TABLE sql_definition ADD COLUMN IF NOT EXISTS notify_channel_id BIGINT;

COMMENT ON TABLE  sql_definition               IS '健康检查 SQL 定义（仅 SELECT，保存与执行双重 AST 校验）';
COMMENT ON COLUMN sql_definition.conn_key      IS '引用 db_connection.conn_key';
COMMENT ON COLUMN sql_definition.params_json   IS '默认参数（JSON 对象，key 对应 SQL 中 ${name} 占位符），缺省 {}';
COMMENT ON COLUMN sql_definition.assert_type   IS '断言目标：VALUE（第一行第一列）/ ROWS（返回行数），空=不断言';
COMMENT ON COLUMN sql_definition.assert_op     IS '断言操作符：== != > >= < <=';
COMMENT ON COLUMN sql_definition.assert_value  IS '断言期望值';
COMMENT ON COLUMN sql_definition.assert_config IS '兼容保留：旧版 JSON 断言配置';
COMMENT ON COLUMN sql_definition.notify_channel_id IS '绑定的告警通道（notify_channel.id）：HC_FAIL 只推送到该通道，空=不告警；不同类型的检查可绑定不同通道';
COMMENT ON COLUMN sql_definition.cron_expr     IS '调度 Cron（Spring 6 段：秒 分 时 日 月 周），空=不调度';
COMMENT ON COLUMN sql_definition.version       IS '每次更新 +1（历史快照见 sql_definition_history）';

-- 2.2 健康检查 SQL 修改记录（版本快照）
CREATE TABLE IF NOT EXISTS sql_definition_history (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sql_def_id    BIGINT      NOT NULL,
    version       INT         NOT NULL,
    sql_text      TEXT,
    assert_type   VARCHAR(16),
    assert_op     VARCHAR(8),
    assert_value  NUMERIC,
    assert_config JSONB,
    cron_expr     VARCHAR(64),
    changed_by    VARCHAR(64),
    changed_at    TIMESTAMP   NOT NULL DEFAULT now(),
    change_type   VARCHAR(16)
);
CREATE INDEX IF NOT EXISTS idx_sql_def_his ON sql_definition_history (sql_def_id, version);
ALTER TABLE sql_definition_history ADD COLUMN IF NOT EXISTS assert_op VARCHAR(8);
ALTER TABLE sql_definition_history ADD COLUMN IF NOT EXISTS assert_value NUMERIC;
COMMENT ON TABLE sql_definition_history IS '健康检查 SQL 修改记录：CREATE | UPDATE | DISABLE（change_type 区分）';

-- 2.3 健康检查执行记录
CREATE TABLE IF NOT EXISTS health_check_run (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sql_def_id   BIGINT      NOT NULL,
    version      INT,
    trigger_type VARCHAR(16),
    status       VARCHAR(16),
    started_at   TIMESTAMP,
    finished_at  TIMESTAMP,
    duration_ms  BIGINT,
    row_count    INT,
    result_head  TEXT,
    assert_msg   TEXT,
    error_msg    TEXT
);
CREATE INDEX IF NOT EXISTS idx_hc_run_def_time ON health_check_run (sql_def_id, started_at);
COMMENT ON TABLE  health_check_run             IS '健康检查执行记录：trigger=CRON|MANUAL|RETRY，status=SUCCESS|FAIL|ERROR|TIMEOUT';
COMMENT ON COLUMN health_check_run.result_head IS '前 20 行 JSON 摘要（截断 2000 字符）';
COMMENT ON COLUMN health_check_run.version     IS '执行时的定义版本快照';

-- =====================================================================
-- 3. 发布（Release Plan / Runbook）
-- =====================================================================

-- 3.1 发布计划
CREATE TABLE IF NOT EXISTS release_plan (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_name        VARCHAR(128) NOT NULL,
    base_path        VARCHAR(512) NOT NULL,
    release_type     VARCHAR(16)  NOT NULL DEFAULT 'FOLDER',
    release_path     VARCHAR(512),
    default_conn_key VARCHAR(64),
    step_config      JSONB,
    status           VARCHAR(24),
    cr_number        VARCHAR(64),
    remark           VARCHAR(512),
    operator         VARCHAR(64),
    started_at       TIMESTAMP,
    finished_at      TIMESTAMP,
    rerun_count      INT          NOT NULL DEFAULT 0,
    created_by       VARCHAR(64),
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_plan_name UNIQUE (plan_name)
);
ALTER TABLE release_plan ADD COLUMN IF NOT EXISTS release_type VARCHAR(16) NOT NULL DEFAULT 'FOLDER';
ALTER TABLE release_plan ADD COLUMN IF NOT EXISTS release_path VARCHAR(512);

COMMENT ON TABLE  release_plan               IS '发布计划：DRAFT|RUNNING|WAITING|PAUSED|COMPLETED|FAILED|SKIPPED（仅 DRAFT 可编辑）';
COMMENT ON COLUMN release_plan.release_type  IS 'FOLDER（release_path 为发布目录）| ZIP（release_path 为 zip 包，解压暂未实现）';
COMMENT ON COLUMN release_plan.release_path  IS '发布路径：可指定 share folder 绝对路径；相对路径基于全局 base-path 解析';
COMMENT ON COLUMN release_plan.step_config   IS '步骤编排配置 [{dirName,stepNo,connKey,afterMode,executor}]（dirName↔stepNo 即目录→运行编号映射，由 SCAN 生成、可编辑）';

-- 3.2 发布步骤
CREATE TABLE IF NOT EXISTS release_step (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id      BIGINT      NOT NULL,
    step_no      INT         NOT NULL,
    dir_path     VARCHAR(512),
    conn_key     VARCHAR(64),
    after_mode   VARCHAR(16) NOT NULL DEFAULT 'CONTINUE',
    executor     VARCHAR(64),
    status       VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    confirm_by   VARCHAR(64),
    confirm_at   TIMESTAMP,
    retry_remark VARCHAR(512),
    script_hash  VARCHAR(64),
    started_at   TIMESTAMP,
    finished_at  TIMESTAMP,
    duration_ms  BIGINT,
    retry_count  INT         NOT NULL DEFAULT 0,
    error_msg    TEXT,
    CONSTRAINT uk_plan_step UNIQUE (plan_id, step_no)
);
CREATE INDEX IF NOT EXISTS idx_plan_status ON release_step (plan_id, status);
ALTER TABLE release_step ADD COLUMN IF NOT EXISTS confirm_by VARCHAR(64);
ALTER TABLE release_step ADD COLUMN IF NOT EXISTS confirm_at TIMESTAMP;
ALTER TABLE release_step ADD COLUMN IF NOT EXISTS retry_remark VARCHAR(512);
ALTER TABLE release_step ADD COLUMN IF NOT EXISTS script_hash VARCHAR(64);

COMMENT ON TABLE  release_step            IS '发布步骤：一个数字目录 = 一个事务；PENDING|RUNNING|SUCCESS|FAIL|WAITING_CONTINUE|SKIPPED';
COMMENT ON COLUMN release_step.after_mode IS 'CONTINUE 自动推进 | WAIT 等人工确认';
COMMENT ON COLUMN release_step.confirm_by IS 'WAIT 步骤人工确认人（continue 时记录，审计用）';
COMMENT ON COLUMN release_step.retry_remark IS '最近一次重试备注';
COMMENT ON COLUMN release_step.script_hash  IS 'start 时脚本内容 SHA-256（详情页实时比对，检测脚本变更）';

-- 3.3 发布 SQL 明细日志
CREATE TABLE IF NOT EXISTS release_sql_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id     BIGINT,
    step_id     BIGINT,
    step_no     INT,
    run_seq     INT,
    operator    VARCHAR(64),
    file_name   VARCHAR(256),
    seq         INT,
    sql_preview VARCHAR(1024),
    status      VARCHAR(16),
    duration_ms BIGINT,
    error_msg   TEXT,
    executed_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sql_log_step ON release_sql_log (step_id);
CREATE INDEX IF NOT EXISTS idx_sql_log_plan ON release_sql_log (plan_id);
ALTER TABLE release_sql_log ADD COLUMN IF NOT EXISTS step_no INT;
ALTER TABLE release_sql_log ADD COLUMN IF NOT EXISTS run_seq INT;
ALTER TABLE release_sql_log ADD COLUMN IF NOT EXISTS operator VARCHAR(64);
COMMENT ON TABLE release_sql_log IS '发布 SQL 明细日志（step_no/run_seq/operator 冗余：多轮重跑后日志仍可按计划查询与区分轮次）';

-- 3.4 发布运行摘要（UAT 计时）
CREATE TABLE IF NOT EXISTS release_run_summary (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id     BIGINT NOT NULL,
    run_seq     INT    NOT NULL,
    total_ms    BIGINT,
    step_detail JSONB,
    started_at  TIMESTAMP,
    finished_at TIMESTAMP,
    operator    VARCHAR(64),
    CONSTRAINT uk_plan_run UNIQUE (plan_id, run_seq)
);
COMMENT ON TABLE release_run_summary IS '发布运行摘要：第 run_seq 次运行的总耗时与各步耗时（成功与失败的运行均记录）';

-- =====================================================================
-- 4. 告警通知
-- =====================================================================

-- 4.1 告警通道
CREATE TABLE IF NOT EXISTS notify_channel (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name              VARCHAR(128) NOT NULL,
    type              VARCHAR(32)  NOT NULL DEFAULT 'XMATTERS',
    url               VARCHAR(512) NOT NULL,
    auth_type         VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    username          VARCHAR(128),
    auth_header_name  VARCHAR(64)  DEFAULT 'apikey',
    secret_enc        VARCHAR(512),
    events            JSONB,
    hc_fail_threshold INT          NOT NULL DEFAULT 1,
    enabled           SMALLINT     NOT NULL DEFAULT 1,
    created_by        VARCHAR(64),
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_by        VARCHAR(64),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now()
);
COMMENT ON TABLE  notify_channel               IS '告警通道：XMATTERS（POST JSON 至入站集成/Webhook 触发地址）';
COMMENT ON COLUMN notify_channel.auth_type     IS 'NONE | BASIC | API_KEY';
COMMENT ON COLUMN notify_channel.secret_enc    IS 'BASIC 密码 / API Key（AES-256-GCM 密文）';
COMMENT ON COLUMN notify_channel.events        IS '订阅事件 JSON 数组：HC_FAIL|RELEASE_STEP_FAIL|PLAN_FAIL|RELEASE_STEP_SUCCESS|PLAN_COMPLETED';
COMMENT ON COLUMN notify_channel.hc_fail_threshold IS '健康检查连续失败多少次才触发（1=每次失败即告警，成功清零计数）';

-- 4.2 告警推送日志
CREATE TABLE IF NOT EXISTS notify_log (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel_id    BIGINT,
    channel_name  VARCHAR(128),
    event         VARCHAR(32),
    title         VARCHAR(256),
    status        VARCHAR(16),
    response_code INT,
    response_body VARCHAR(1024),
    error_msg     TEXT,
    payload       TEXT,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_notify_log_time ON notify_log (created_at);
COMMENT ON TABLE notify_log IS '告警推送日志：SUCCESS | FAIL（含响应摘要与完整 payload）';

-- =====================================================================
-- 5. 示例数据（Sample Data）
-- =====================================================================
-- 全部 enabled=0（不生效），仅用于理解字段结构与快速演示：
--   1) 在「连接管理」创建真实连接（记下 conn_key）
--   2) 将下方示例的 conn_key 改为真实连接、enabled 置 1（UI 或 UPDATE 语句）即可使用
-- 幂等：WHERE NOT EXISTS，重复启动不会插入重复行。

-- 5.1 示例告警通道（指向占位 URL，启用前请改为真实 xMatters 触发地址）
INSERT INTO notify_channel (name, type, url, auth_type, events, hc_fail_threshold, enabled, created_by)
SELECT 'sample - xMatters channel (disabled)', 'XMATTERS',
       'https://yourco.xmatters.com/api/integration/1/functions/REPLACE_ME/trigger',
       'NONE',
       '["HC_FAIL","RELEASE_STEP_FAIL","PLAN_FAIL","RELEASE_STEP_SUCCESS","PLAN_COMPLETED"]',
       1, 0, 'sample-data'
WHERE NOT EXISTS (SELECT 1 FROM notify_channel WHERE name = 'sample - xMatters channel (disabled)');

-- 5.2 示例健康检查（conn_key 为占位，创建真实连接后修改即可运行）
-- 示例 A：数据库时间（VALUE 断言：第一行第一列 >= 0；演示最基础的连通性检查）
INSERT INTO sql_definition (name, conn_key, sql_text, assert_type, assert_op, assert_value, cron_expr, timeout_sec, enabled, created_by)
SELECT 'sample - db time (VALUE assert)', 'sample-bizdb',
       'SELECT now() AS db_time',
       'VALUE', '>=', 0,
       '0 */5 * * * *', 10, 0, 'sample-data'
WHERE NOT EXISTS (SELECT 1 FROM sql_definition WHERE name = 'sample - db time (VALUE assert)');

-- 示例 B：活跃会话数（ROWS 断言：返回行数 >= 1）
INSERT INTO sql_definition (name, conn_key, sql_text, assert_type, assert_op, assert_value, cron_expr, timeout_sec, enabled, created_by)
SELECT 'sample - active sessions (ROWS assert)', 'sample-bizdb',
       'SELECT count(*) AS active_sessions FROM pg_stat_activity',
       'ROWS', '>=', 1,
       '0 */10 * * * *', 10, 0, 'sample-data'
WHERE NOT EXISTS (SELECT 1 FROM sql_definition WHERE name = 'sample - active sessions (ROWS assert)');

-- 示例 C：锁等待检查（ROWS 断言：行数必须为 0，即不允许存在锁等待——PG 运维常用巡检）
INSERT INTO sql_definition (name, conn_key, sql_text, params_json, assert_type, assert_op, assert_value, cron_expr, timeout_sec, enabled, created_by)
SELECT 'sample - lock waits must be zero (ROWS == 0)', 'sample-bizdb',
       'SELECT count(*) AS waiting_locks FROM pg_locks WHERE NOT granted',
       NULL,
       'ROWS', '==', 0,
       '0 */2 * * * *', 10, 0, 'sample-data'
WHERE NOT EXISTS (SELECT 1 FROM sql_definition WHERE name = 'sample - lock waits must be zero (ROWS == 0)');

-- 示例 D：长事务检查（命名参数占位符演示：${thresholdSeconds} 由 params_json 提供，手动执行可覆盖）
INSERT INTO sql_definition (name, conn_key, sql_text, params_json, assert_type, assert_op, assert_value, cron_expr, timeout_sec, enabled, created_by)
SELECT 'sample - long transactions (named params)', 'sample-bizdb',
       'SELECT count(*) AS long_tx FROM pg_stat_activity WHERE state <> ''idle'' AND now() - xact_start > make_interval(secs => ${thresholdSeconds})',
       '{"thresholdSeconds":300}',
       'VALUE', '>=', 0,
       '0 */30 * * * *', 15, 0, 'sample-data'
WHERE NOT EXISTS (SELECT 1 FROM sql_definition WHERE name = 'sample - long transactions (named params)');

-- 5.3 示例连接（模板，注释状态）：密码需经 AES-256-GCM 加密，请通过 Web 界面「连接管理」创建，
--     或使用下方模板并将 password_enc 替换为加密后的密文（工具：com.sqlpipeline.boot.tools.AesKeyGen 同源密钥）。
-- INSERT INTO db_connection (conn_key, display_name, jdbc_url, username, password_enc, default_schema, enabled, created_by)
-- SELECT 'sample-bizdb', 'Sample Business DB', 'jdbc:postgresql://localhost:5432/your_biz_db', 'app_user',
--        '<AES-256-GCM ciphertext>', 'public', 0, 'sample-data'
-- WHERE NOT EXISTS (SELECT 1 FROM db_connection WHERE conn_key = 'sample-bizdb');
