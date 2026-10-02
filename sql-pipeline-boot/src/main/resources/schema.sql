-- =====================================================================
-- sql-pipeline 平台元数据初始化脚本（PostgreSQL）
-- 幂等：全部使用 IF NOT EXISTS，可随应用每次启动执行
-- =====================================================================

-- 6.2.1 数据库连接定义（目标库，仅 PostgreSQL）
CREATE TABLE IF NOT EXISTS db_connection (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conn_key        VARCHAR(64)  NOT NULL UNIQUE,
    display_name    VARCHAR(128) NOT NULL,
    jdbc_url        VARCHAR(512) NOT NULL,
    username        VARCHAR(64)  NOT NULL,
    password_enc    VARCHAR(512) NOT NULL,
    driver_class    VARCHAR(128) NOT NULL DEFAULT 'org.postgresql.Driver',
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
COMMENT ON TABLE  db_connection            IS '数据库连接定义';
COMMENT ON COLUMN db_connection.conn_key   IS '平台内引用名';
COMMENT ON COLUMN db_connection.password_enc IS 'AES-256-GCM 加密后的密码';
COMMENT ON COLUMN db_connection.max_rows   IS '健康检查查询最大行数';
COMMENT ON COLUMN db_connection.enabled    IS '1 启用 / 0 禁用（软删）';

-- 6.2.2 健康检查 SQL 定义
CREATE TABLE IF NOT EXISTS sql_definition (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    conn_key      VARCHAR(64)  NOT NULL,
    sql_text      TEXT         NOT NULL,
    params_json   JSONB,
    assert_type   VARCHAR(16),
    assert_config JSONB,
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
COMMENT ON TABLE  sql_definition           IS '健康检查 SQL 定义';
COMMENT ON COLUMN sql_definition.params_json IS '默认参数值（JSON 数组，按 ? 顺序绑定）';
COMMENT ON COLUMN sql_definition.assert_type IS 'VALUE | ROWCOUNT | RECORD';
COMMENT ON COLUMN sql_definition.version     IS '每次更新 +1';

-- 6.2.3 健康检查 SQL 修改记录（版本快照）
CREATE TABLE IF NOT EXISTS sql_definition_history (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sql_def_id    BIGINT      NOT NULL,
    version       INT         NOT NULL,
    sql_text      TEXT,
    assert_type   VARCHAR(16),
    assert_config JSONB,
    cron_expr     VARCHAR(64),
    changed_by    VARCHAR(64),
    changed_at    TIMESTAMP   NOT NULL DEFAULT now(),
    change_type   VARCHAR(16)
);
CREATE INDEX IF NOT EXISTS idx_sql_def_his ON sql_definition_history (sql_def_id, version);
COMMENT ON TABLE sql_definition_history IS '健康检查 SQL 修改记录：CREATE | UPDATE | DISABLE';

-- 6.2.4 健康检查执行记录
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
COMMENT ON TABLE  health_check_run            IS '健康检查执行记录：CRON | MANUAL | RETRY';
COMMENT ON COLUMN health_check_run.result_head IS '前 20 行 JSON 摘要';
COMMENT ON COLUMN health_check_run.status      IS 'SUCCESS | FAIL | ERROR | TIMEOUT';

-- 6.2.5 发布计划
CREATE TABLE IF NOT EXISTS release_plan (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_name        VARCHAR(128) NOT NULL,
    base_path        VARCHAR(512) NOT NULL,
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
COMMENT ON TABLE  release_plan             IS '发布计划：DRAFT|RUNNING|WAITING|PAUSED|COMPLETED|FAILED|SKIPPED';
COMMENT ON COLUMN release_plan.step_config IS '各步骤编排配置 [{stepNo,connKey,afterMode,executor}]';

-- 6.2.6 发布步骤
CREATE TABLE IF NOT EXISTS release_step (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id     BIGINT      NOT NULL,
    step_no     INT         NOT NULL,
    dir_path    VARCHAR(512),
    conn_key    VARCHAR(64),
    after_mode  VARCHAR(16) NOT NULL DEFAULT 'CONTINUE',
    executor    VARCHAR(64),
    status      VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    started_at  TIMESTAMP,
    finished_at TIMESTAMP,
    duration_ms BIGINT,
    retry_count INT         NOT NULL DEFAULT 0,
    error_msg   TEXT,
    CONSTRAINT uk_plan_step UNIQUE (plan_id, step_no)
);
CREATE INDEX IF NOT EXISTS idx_plan_status ON release_step (plan_id, status);
COMMENT ON TABLE  release_step            IS '发布步骤：PENDING|RUNNING|SUCCESS|FAIL|WAITING_CONTINUE|SKIPPED';
COMMENT ON COLUMN release_step.after_mode IS 'CONTINUE 自动推进 | WAIT 等人工确认';

-- 6.2.7 发布 SQL 明细日志
CREATE TABLE IF NOT EXISTS release_sql_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id     BIGINT,
    step_id     BIGINT,
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
COMMENT ON TABLE release_sql_log IS '发布 SQL 明细日志';

-- 6.2.8 发布运行摘要（UAT 计时）
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
COMMENT ON TABLE release_run_summary IS '发布运行摘要：第 run_seq 次运行的总耗时与各步耗时';
