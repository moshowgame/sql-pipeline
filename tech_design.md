# sql-pipeline 技术设计文档

| 项 | 内容 |
|---|---|
| 文档名称 | sql-pipeline 技术设计文档（TDD） |
| 版本 | v1.1 |
| 状态 | 已实现（与代码同步，实现状态与设计修订见 §18） |
| 最后更新 | 2026-10-05 |
| 适用范围 | 平台一期（Health Check + Release Plan/Runbook + 告警通知） |

## 修订记录

| 版本 | 日期 | 修订人 | 说明 |
|---|---|---|---|
| v1.0 | 2026-10-03 | — | 初稿，覆盖两大核心模块设计 |
| v1.1 | 2026-10-05 | — | 与实现同步：PostgreSQL 全量化、Release Runbook 重设计（type/path/SCAN/可重编号/编辑查看）、异步驱动 + advisory lock、告警通知模块（xMatters）、i18n、断言与参数模型简化、Pipeline 可视化（详见 §18） |

---

## 目录

1. 背景与目标
2. 术语表
3. 需求概述
4. 总体架构
5. 技术选型与理由
6. 数据模型设计
7. 核心模块设计
   - 7.1 数据源注册与路由
   - 7.2 SQL 只读校验（SqlGuard）
   - 7.3 动态 SQL 执行器
   - 7.4 模块一：健康检查自动化
   - 7.5 模块二：发布编排
8. 接口设计
9. 关键流程时序
10. 并发与线程模型
11. 异常处理与错误码
12. 安全设计
13. 可观测性
14. 性能与容量
15. 部署与配置
16. 技术风险与决策记录
17. 附录
18. 实现修订（v1.1，与代码同步）

---

## 1. 背景与目标

### 1.1 背景

当前数据库相关的两类工作长期依赖人工脚本：

- **日常巡检**：需要定期执行一批查询 SQL 验证数据健康度，靠人记 Cron、靠人看结果，缺乏执行记录和版本追溯。
- **版本发布**：DDL / DML 脚本按目录组织，靠人记顺序、靠人盯执行，缺少流程卡点、失败回滚边界和审计。

两者的共性是：**SQL 是资产，但管理方式是脚本级的**。目标是把它们收敛到一个平台里，做到"可配置、可校验、可追溯、可编排"。

### 1.2 目标

| 编号 | 目标 | 优先级 |
|---|---|---|
| G1 | 平台内定义数据库连接，运行期动态管理连接池 | P0 |
| G2 | 健康检查 SQL 只允许 `SELECT`，从保存到执行全链路拦截 | P0 |
| G3 | 健康检查支持定时调度、断言校验、修改记录与执行记录 | P0 |
| G4 | 发布流程按目录顺序执行，支持 WAIT/CONTINUE 人工卡点 | P0 |
| G5 | 发布支持单步重试与全流程重跑 | P1 |
| G6 | 执行者凭 CR Number + Remark 启动发布 | P1 |
| G7 | 目录不存在 / 缺号自动忽略，无需人工维护清单 | P0 |

### 1.3 非目标（一期不做）

- 不做 SQL 编辑器（语法高亮、补全）
- 不做发布审批流（多级审批）
- 不做跨库分布式事务
- 不做 SQL 性能分析 / 执行计划可视化
- 不做多租户隔离

---

## 2. 术语表

| 术语 | 含义 |
|---|---|
| 平台库 / 元数据库 | 存储平台自身配置与记录的数据库，走 MyBatis |
| 目标库 / 业务库 | 被健康检查或发布实际执行 SQL 的数据库，走动态数据源 |
| 健康检查 | 一条配置化的只读 SQL + 定时任务 + 断言规则 |
| 断言 | 对查询结果进行判定，决定本次检查 SUCCESS / FAIL |
| 发布计划（Plan） | 一个 `release_YYYYMMDD` 目录对应的一次发布 |
| 发布步骤（Step） | 计划下的一个数字目录（1..9），对应一个事务单元 |
| after_mode | 步骤执行后的过渡方式：`CONTINUE` 自动推进 / `WAIT` 等人工点击 |
| CR Number | 变更申请单号，发布启动时由执行者录入 |
| SqlGuard | 只读校验组件，基于 JSqlParser AST |
| 执行者（executor） | 被指定可启动 / 继续 / 重试某步骤的账号 |

---

## 3. 需求概述

### 3.1 模块一：Health Check Automation

| 编号 | 需求 |
|---|---|
| HC-1 | 平台预先定义数据库连接 |
| HC-2 | 可配置只读 SQL 与定时任务 |
| HC-3 | 可配置断言：判断返回值 或 判断返回记录 |
| HC-4 | 拦截 `delete / update / insert`，仅允许 `select` |
| HC-5 | SQL 必须有修改记录（版本快照） |
| HC-6 | 每次执行必须有执行记录 |

### 3.2 模块二：Release Automation

| 编号 | 需求 |
|---|---|
| RL-1 | 按 `release_YYYYMMDD/1..9` 数字目录顺序执行 |
| RL-2 | 每步可配置目标 DB |
| RL-3 | 每步可配置编排条件（`CONTINUE` / `WAIT`） |
| RL-4 | 每步可配置执行者 |
| RL-5 | 执行者需输入 CR Number + Remark 才能开始 |
| RL-6 | `WAIT` 步骤需手工点击 continue 才推进 |
| RL-7 | `CONTINUE` 步骤执行完自动推进下一步 |
| RL-8 | 目录不存在则自动检测并忽略 |
| RL-9 | 支持全流程重跑（用于 UAT 统计执行时间） |
| RL-10 | 支持单步重试，且不自动触发对应 continue 规则 |

---

## 4. 总体架构

### 4.1 分层架构

```
┌───────────────────────────────────────────────────────────────┐
│  Web / API 层                                                  │
│  ConnectionController  HealthCheckController  ReleaseController │
│  RBAC · 审计日志 · 全局异常处理                                  │
├───────────────────────────────────────────────────────────────┤
│  业务层                                                        │
│  ┌─────────────────┐  ┌──────────────────┐  ┌───────────────┐ │
│  │ HealthCheck     │  │ Release          │  │ Scheduler     │ │
│  │ Service         │  │ Orchestrator     │  │ Service       │ │
│  │ (断言/记录)      │  │ (状态机/驱动)     │  │ (动态注册)     │ │
│  └─────────────────┘  └──────────────────┘  └───────────────┘ │
├───────────────────────────────────────────────────────────────┤
│  执行层                                                        │
│  ┌──────────────────┐ ┌──────────────┐ ┌───────────────────┐  │
│  │ DataSource       │ │ SqlGuard     │ │ DynamicSql        │  │
│  │ Registry         │ │ (JSqlParser) │ │ Executor          │  │
│  └──────────────────┘ └──────────────┘ └───────────────────┘  │
│  ┌──────────────────┐ ┌──────────────┐                        │
│  │ ReleaseScanner   │ │ Assert       │                        │
│  │ (目录扫描)        │ │ Evaluator    │                        │
│  └──────────────────┘ └──────────────┘                        │
├───────────────────────────────────────────────────────────────┤
│  持久层                                                        │
│  平台库：MyBatis Mapper（单数据源）                              │
│  目标库：HikariCP 动态池（按 conn_key 路由）                     │
└───────────────────────────────────────────────────────────────┘
```

### 4.2 关键设计原则

| 原则 | 说明 |
|---|---|
| **元数据库与目标库隔离** | 平台自身状态走 MyBatis 固定数据源；业务 SQL 走动态数据源。两者绝不共用连接。 |
| **按 key 取数据源，不用 ThreadLocal** | 规避线程池复用导致的上下文串号（详见 §10.4）。 |
| **校验前置 + 执行兜底** | 只读校验在保存与执行两层做，连接层 `setReadOnly(true)` 兜底。 |
| **状态机驱动，非流程引擎** | 发布编排用显式状态机 + 锁，不引入 Activiti/Flowable，降低复杂度。 |
| **步骤即事务边界** | 一个数字目录 = 一个事务，失败整目录回滚。 |

---

## 5. 技术选型与理由

| 组件 | 选型 | 理由 / 备选 |
|---|---|---|
| 应用框架 | Spring Boot 3.x | 需求指定 |
| 元数据持久层 | MyBatis | 需求指定；SQL 可控，便于审计 |
| 连接池 | HikariCP | Spring Boot 3 默认，性能优 |
| 动态业务 SQL 执行 | 原生 JDBC + PreparedStatement | 见 §5.1 |
| SQL 解析 | JSqlParser | AST 级判断，优于正则 |
| 调度 | Spring `TaskScheduler` | 支持运行期动态注册 Cron |
| JSON | Jackson | Spring Boot 默认 |
| 密码加密 | AES-256（密钥走 KMS / 环境变量） | 不落明文 |

### 5.1 为什么动态 SQL 不用 MyBatis 执行

| 维度 | MyBatis | 原生 JDBC |
|---|---|---|
| 运行期注册 | 需动态注册 `MappedStatement`，污染 `Configuration` | 直接 `prepareStatement` |
| 注入风险 | `${}` 拼接有注入面 | 参数化绑定 |
| 超时控制 | 依赖 `Configuration` 全局设置 | `setQueryTimeout` 逐条可控 |
| 行数限制 | 需手动改写 | `setMaxRows` 原生支持 |
| 事务边界 | 需额外编排 | 直接操作 `Connection` |

**结论**：平台元数据操作用 MyBatis；动态业务 SQL 用原生 JDBC。

---

## 6. 数据模型设计

### 6.1 ER 概览

```
db_connection ──┬──< sql_definition ──< sql_definition_history
                │         │
                │         └──< health_check_run
                │
                └──< release_step

release_plan ──< release_step ──< release_sql_log
```

### 6.2 表定义

#### 6.2.1 `db_connection` 数据库连接定义

```sql
CREATE TABLE db_connection (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  conn_key        VARCHAR(64)  NOT NULL UNIQUE COMMENT '平台内引用名',
  display_name    VARCHAR(128) NOT NULL,
  jdbc_url        VARCHAR(512) NOT NULL,
  username        VARCHAR(64)  NOT NULL,
  password_enc    VARCHAR(512) NOT NULL COMMENT 'AES 加密',
  driver_class    VARCHAR(128) NOT NULL,
  pool_size       INT DEFAULT 10,
  conn_timeout_ms INT DEFAULT 5000,
  max_rows        INT DEFAULT 1000  COMMENT '查询最大行数',
  query_timeout_s INT DEFAULT 30,
  enabled         TINYINT DEFAULT 1,
  created_by      VARCHAR(64),
  created_at      DATETIME,
  updated_by      VARCHAR(64),
  updated_at      DATETIME
);
```

#### 6.2.2 `sql_definition` 健康检查 SQL 定义

```sql
CREATE TABLE sql_definition (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  name          VARCHAR(128) NOT NULL,
  conn_id       BIGINT NOT NULL,
  sql_text      MEDIUMTEXT NOT NULL,
  params_json   JSON COMMENT '默认参数值',
  assert_type   VARCHAR(16) COMMENT 'VALUE | ROWCOUNT | RECORD',
  assert_config JSON,
  cron_expr     VARCHAR(64),
  timeout_sec   INT DEFAULT 30,
  enabled       TINYINT DEFAULT 1,
  version       INT DEFAULT 1,
  created_by    VARCHAR(64),
  created_at    DATETIME,
  updated_by    VARCHAR(64),
  updated_at    DATETIME,
  KEY idx_conn (conn_id)
);
```

#### 6.2.3 `sql_definition_history` 修改记录

```sql
CREATE TABLE sql_definition_history (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  sql_def_id    BIGINT NOT NULL,
  version       INT NOT NULL COMMENT '被替换前的版本号',
  sql_text      MEDIUMTEXT,
  assert_type   VARCHAR(16),
  assert_config JSON,
  cron_expr     VARCHAR(64),
  changed_by    VARCHAR(64),
  changed_at    DATETIME,
  change_type   VARCHAR(16) COMMENT 'CREATE | UPDATE | DISABLE',
  UNIQUE KEY uk_def_ver (sql_def_id, version)
);
```

#### 6.2.4 `health_check_run` 执行记录

```sql
CREATE TABLE health_check_run (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  sql_def_id   BIGINT NOT NULL,
  version      INT COMMENT '执行时的版本快照',
  trigger_type VARCHAR(16) COMMENT 'CRON | MANUAL | RETRY',
  status       VARCHAR(16) COMMENT 'SUCCESS | FAIL | ERROR | TIMEOUT',
  started_at   DATETIME,
  finished_at  DATETIME,
  duration_ms  BIGINT,
  row_count    INT,
  result_head  TEXT COMMENT '前 N 行 JSON 摘要',
  assert_msg   TEXT,
  error_msg    TEXT,
  KEY idx_def_time (sql_def_id, started_at)
);
```

#### 6.2.5 `release_plan` 发布计划

```sql
CREATE TABLE release_plan (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_name   VARCHAR(128) NOT NULL COMMENT 'release_20261003',
  base_path   VARCHAR(512) NOT NULL,
  status      VARCHAR(24) COMMENT 'DRAFT|RUNNING|WAITING|PAUSED|COMPLETED|FAILED|SKIPPED',
  cr_number   VARCHAR(64),
  remark      VARCHAR(512),
  operator    VARCHAR(64),
  started_at  DATETIME,
  finished_at DATETIME,
  rerun_count INT DEFAULT 0,
  created_at  DATETIME,
  UNIQUE KEY uk_plan_name (plan_name)
);
```

#### 6.2.6 `release_step` 发布步骤

```sql
CREATE TABLE release_step (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id     BIGINT NOT NULL,
  step_no     INT NOT NULL COMMENT '1..9',
  dir_path    VARCHAR(512),
  conn_id     BIGINT,
  after_mode  VARCHAR(16) COMMENT 'CONTINUE | WAIT',
  executor    VARCHAR(64) COMMENT '指定执行者账号',
  status      VARCHAR(24) COMMENT 'PENDING|RUNNING|SUCCESS|FAIL|WAITING_CONTINUE|SKIPPED',
  started_at  DATETIME,
  finished_at DATETIME,
  duration_ms BIGINT,
  retry_count INT DEFAULT 0,
  error_msg   TEXT,
  UNIQUE KEY uk_plan_step (plan_id, step_no),
  KEY idx_plan_status (plan_id, status)
);
```

#### 6.2.7 `release_sql_log` 明细日志

```sql
CREATE TABLE release_sql_log (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id     BIGINT,
  step_id     BIGINT,
  file_name   VARCHAR(256),
  seq         INT COMMENT '文件内顺序',
  sql_preview VARCHAR(1024),
  status      VARCHAR(16),
  duration_ms BIGINT,
  error_msg   TEXT,
  executed_at DATETIME,
  KEY idx_step (step_id)
);
```

#### 6.2.8 `release_run_summary` 全流程重跑统计（UAT 计时）

```sql
CREATE TABLE release_run_summary (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id     BIGINT NOT NULL,
  run_seq     INT NOT NULL COMMENT '第几次运行',
  total_ms    BIGINT,
  step_detail JSON COMMENT '各步耗时',
  started_at  DATETIME,
  finished_at DATETIME,
  operator    VARCHAR(64)
);
```

---

## 7. 核心模块设计

### 7.1 数据源注册与路由

#### 7.1.1 职责

- 启动时从 `db_connection` 加载所有启用连接，建立 Hikari 连接池
- 提供 `get(connKey)` 供执行器取用
- 支持运行期新增 / 修改 / 禁用连接的热更新

#### 7.1.2 核心实现

```java
@Component
public class DataSourceRegistry implements DisposableBean {

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final DbConnectionMapper mapper;
    private final CryptoService crypto;

    @PostConstruct
    public void init() {
        mapper.selectEnabled().forEach(this::register);
    }

    public void register(DbConnectionEntity e) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("pool-" + e.getConnKey());
        cfg.setJdbcUrl(e.getJdbcUrl());
        cfg.setUsername(e.getUsername());
        cfg.setPassword(crypto.decrypt(e.getPasswordEnc()));
        cfg.setDriverClassName(e.getDriverClass());
        cfg.setMaximumPoolSize(e.getPoolSize());
        cfg.setConnectionTimeout(e.getConnTimeoutMs());
        cfg.setAutoCommit(true);
        pools.put(e.getConnKey(), new HikariDataSource(cfg));
    }

    public DataSource get(String key) {
        HikariDataSource ds = pools.get(key);
        if (ds == null) throw new BizException(ErrorCode.DS_NOT_FOUND, key);
        return ds;
    }

    public synchronized void reload(String key) {
        HikariDataSource old = pools.remove(key);
        if (old != null) old.close();  // 先移除再关闭，避免新请求拿到已关闭池
        DbConnectionEntity e = mapper.selectByKey(key);
        if (e != null && e.getEnabled() == 1) register(e);
    }

    @Override
    public void destroy() {
        pools.values().forEach(HikariDataSource::close);
    }
}
```

#### 7.1.3 设计要点

- **不使用 `AbstractRoutingDataSource` + ThreadLocal**：定时任务跑在线程池里，ThreadLocal 极易串号。直接按 key 取 `DataSource`，把"用哪个库"作为显式参数传递。
- **reload 顺序**：先 `remove` 再 `close`，防止并发请求拿到正在关闭的池。
- **连接池命名**：`pool-{connKey}`，便于 JMX / 监控识别。

### 7.2 SQL 只读校验（SqlGuard）

#### 7.2.1 职责

对健康检查 SQL 做 AST 级校验，只允许 `SELECT`。

#### 7.2.2 核心实现

```java
@Component
public class SqlGuard {

    public void assertSelectOnly(String rawSql) {
        List<String> statements = SqlSplitter.split(rawSql);
        if (statements.isEmpty()) {
            throw new SqlGuardException("SQL 为空");
        }
        for (String stmt : statements) {
            Statement parsed;
            try {
                parsed = CCJSqlParserUtil.parse(stmt);
            } catch (JSQLParserException e) {
                // 解析失败一律拒绝，宁可误杀不可放过
                throw new SqlGuardException("SQL 解析失败，拒绝执行: " + e.getMessage());
            }
            if (!(parsed instanceof Select)) {
                throw new SqlGuardException(
                    "只允许 SELECT，检测到: " + parsed.getClass().getSimpleName());
            }
            assertNoForbiddenClause((Select) parsed);
        }
    }

    private void assertNoForbiddenClause(Select select) {
        String s = select.toString().toLowerCase();
        if (s.contains("for update"))    throw new SqlGuardException("不允许 FOR UPDATE");
        if (s.contains("into outfile"))  throw new SqlGuardException("不允许 INTO OUTFILE");
        if (s.contains("into dumpfile")) throw new SqlGuardException("不允许 INTO DUMPFILE");
    }
}
```

#### 7.2.3 拦截层次

| 层 | 位置 | 作用 |
|---|---|---|
| L1 | 保存 `sql_definition` 时 | 前端即时反馈，脏数据不入库 |
| L2 | 执行前 | 防历史脏数据、防绕过 API 直改库 |
| L3 | 连接层 `conn.setReadOnly(true)` | 数据库驱动侧兜底 |
| L4 | 目标库只读账号（推荐） | `GRANT SELECT`，最彻底 |

#### 7.2.4 边界情况

| 情况 | 处理 |
|---|---|
| 多语句（分号分隔） | 拆开后逐条校验，任一非 SELECT 即拒绝 |
| 注释包裹 | `SqlSplitter` 需剥离 `--`、`/* */` 后再拆分 |
| `WITH ... SELECT` | JSqlParser 解析为 `Select`，放行 |
| 存储过程调用 `CALL` | 解析为 `ExecuteStatement`，非 `Select`，拒绝 |
| 解析器无法识别的方言 | 拒绝（fail-closed） |

### 7.3 动态 SQL 执行器

```java
@Component
@RequiredArgsConstructor
public class DynamicSqlExecutor {

    private final DataSourceRegistry registry;

    /** 健康检查用：只读查询 */
    public QueryResult query(String connKey, String sql,
                             List<Object> params, int maxRows, int timeoutSec) {
        DataSource ds = registry.get(connKey);
        try (Connection conn = ds.getConnection()) {
            conn.setReadOnly(true);
            conn.setAutoCommit(true);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setQueryTimeout(timeoutSec);
                ps.setMaxRows(maxRows);
                bindParams(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return readResultSet(rs, maxRows);
                }
            }
        } catch (SQLTimeoutException e) {
            throw new SqlExecException(ErrorCode.SQL_TIMEOUT, e);
        } catch (SQLException e) {
            throw new SqlExecException(ErrorCode.SQL_ERROR, e);
        }
    }

    /** 发布用：允许 DML，事务由调用方控制 */
    public int executeUpdate(Connection conn, String sql, int timeoutSec)
            throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(timeoutSec);
            return st.executeUpdate(sql);
        }
    }
}
```

#### 7.3.1 `QueryResult` 结构

```java
public class QueryResult {
    private List<String> columns;
    private List<Map<String, Object>> rows;
    private int rowCount;
    private boolean truncated;   // 是否因 maxRows 截断

    public Object cell(int row, int col) { ... }
    public String toJson(int maxRows) { ... }
}
```

### 7.4 模块一：健康检查自动化

#### 7.4.1 断言模型

`assert_config` 为 JSON，支持三类断言：

**VALUE — 标量比较**

```jsonc
{
  "type": "VALUE",
  "expr": "cell(0,0)",          // 或 "rowCount"
  "op": "==",                    // == != > >= < <=
  "value": 1
}
```

**ROWCOUNT — 行数比较**

```jsonc
{ "type": "ROWCOUNT", "op": ">=", "value": 1 }
```

**RECORD — 记录级规则**

```jsonc
{
  "type": "RECORD",
  "mode": "ALL",                 // ALL | ANY
  "rules": [
    { "field": "status", "op": "not_null" },
    { "field": "amount", "op": ">", "value": 0 },
    { "field": "type",   "op": "in", "value": ["A", "B"] }
  ]
}
```

支持的操作符：`==` `!=` `>` `>=` `<` `<=` `not_null` `is_null` `in` `not_in` `regex`。

#### 7.4.2 断言求值器

```java
public class AssertEvaluator {

    public AssertResult evaluate(AssertConfig cfg, QueryResult result) {
        return switch (cfg.getType()) {
            case VALUE    -> evalValue(cfg, result);
            case ROWCOUNT -> evalRowCount(cfg, result);
            case RECORD   -> evalRecord(cfg, result);
            default -> throw new BizException(ErrorCode.ASSERT_TYPE_UNKNOWN);
        };
    }

    private AssertResult evalValue(AssertConfig cfg, QueryResult r) {
        Object actual = "rowCount".equals(cfg.getExpr())
                ? r.getRowCount()
                : r.cell(0, 0);
        boolean pass = Operator.apply(cfg.getOp(), actual, cfg.getValue());
        return new AssertResult(pass,
            String.format("expected %s %s, actual=%s",
                cfg.getOp(), cfg.getValue(), actual));
    }
}
```

> 不引入 SpEL，自行实现操作符表，避免表达式注入面。

#### 7.4.3 调度注册

```java
@Configuration
public class SchedulerConfig implements SchedulingConfigurer {

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(8);                            // ⚠️ 必须显式设置
        s.setThreadNamePrefix("hc-sched-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(30);
        return s;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(taskScheduler());
    }
}
```

```java
@Component
@RequiredArgsConstructor
public class HealthCheckScheduler {

    private final ThreadPoolTaskScheduler taskScheduler;
    private final Map<Long, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public void reschedule(SqlDefinition def) {
        cancel(def.getId());
        if (def.getEnabled() == 1 && StringUtils.hasText(def.getCronExpr())) {
            CronTrigger trigger = new CronTrigger(def.getCronExpr());
            ScheduledFuture<?> f = taskScheduler.schedule(
                () -> healthCheckService.execute(def.getId(), "CRON"), trigger);
            futures.put(def.getId(), f);
        }
    }

    public void cancel(Long defId) {
        ScheduledFuture<?> f = futures.remove(defId);
        if (f != null) f.cancel(false);   // false: 让正在执行的跑完
    }
}
```

> **关键坑**：Spring Boot 默认 `ThreadPoolTaskScheduler` 池大小为 **1**。若不显式设置，一个慢任务会阻塞所有健康检查。详见 §10.2。

#### 7.4.4 执行流程

```java
public HealthCheckRun execute(Long defId, String triggerType) {
    SqlDefinition def = loadSnapshot(defId);          // 含 version
    SqlGuard.assertSelectOnly(def.getSqlText());      // L2 拦截

    long t0 = System.currentTimeMillis();
    HealthCheckRun run = HealthCheckRun.start(defId, def.getVersion(), triggerType);
    runMapper.insert(run);

    try {
        QueryResult qr = executor.query(
            connKey(def), def.getSqlText(), parseParams(def),
            def.getMaxRows(), def.getTimeoutSec());

        AssertResult ar = evaluator.evaluate(
            JSON.parseObject(def.getAssertConfig(), AssertConfig.class), qr);

        run.markFinished(ar.isPass() ? "SUCCESS" : "FAIL",
                         qr.getRowCount(), truncate(qr.toJson(20), 2000),
                         ar.getMessage(), null);
    } catch (SqlExecException e) {
        run.markFinished(e.getCode(), 0, null, null, e.getMessage());
    } catch (Exception e) {
        run.markFinished("ERROR", 0, null, null, e.getMessage());
    } finally {
        run.setDurationMs(System.currentTimeMillis() - t0);
        runMapper.updateResult(run);
    }
    return run;
}
```

#### 7.4.5 版本管理

更新时通过 `@Transactional` 保证历史与主表一致：

```java
@Transactional
public void update(SqlDefinitionUpdateCmd cmd, String operator) {
    SqlGuard.assertSelectOnly(cmd.getSqlText());      // L1 拦截
    SqlDefinition old = mapper.selectById(cmd.getId());
    if (old == null) throw new BizException(ErrorCode.DEF_NOT_FOUND);

    // 1) 写历史（记录旧版本内容）
    SqlDefinitionHistory h = SqlDefinitionHistory.from(old);
    h.setVersion(old.getVersion());
    h.setChangeType("UPDATE");
    h.setChangedBy(operator);
    historyMapper.insert(h);

    // 2) 更新主表，version + 1
    old.setSqlText(cmd.getSqlText());
    old.setAssertType(cmd.getAssertType());
    old.setAssertConfig(cmd.getAssertConfig());
    old.setCronExpr(cmd.getCronExpr());
    old.setVersion(old.getVersion() + 1);
    old.setUpdatedBy(operator);
    mapper.updateById(old);

    // 3) 重注册调度
    scheduler.reschedule(old);
}
```

### 7.5 模块二：发布编排

#### 7.5.1 目录扫描规则

| 规则 | 说明 |
|---|---|
| 一级子目录名必须匹配 `^\d+$` | 非数字目录忽略 |
| 按数字升序排序 | 决定执行顺序 |
| 缺号忽略 | 3、4 不存在不影响 5 的执行 |
| 空目录忽略 | 目录存在但无 `.sql`，视为不存在 |
| 目录内 `.sql` 文件按文件名字典序 | 决定文件内执行顺序 |
| 目录内文件不递归 | 仅当前层 |
| 整个 plan 目录不存在 | 计划标记 `SKIPPED` |

#### 7.5.2 目录扫描器

```java
@Component
public class ReleaseScanner {

    private static final Pattern NUM_DIR = Pattern.compile("^\\d+$");

    public List<ScannedStep> scan(Path planDir) {
        if (!Files.isDirectory(planDir)) return List.of();

        try (Stream<Path> s = Files.list(planDir)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> NUM_DIR.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(
                        p -> Integer.parseInt(p.getFileName().toString())))
                    .map(this::toStep)
                    .filter(st -> !st.sqlFiles().isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new BizException(ErrorCode.SCAN_FAILED, e);
        }
    }

    private ScannedStep toStep(Path dir) {
        int no = Integer.parseInt(dir.getFileName().toString());
        List<Path> sqls;
        try (Stream<Path> s = Files.list(dir)) {
            sqls = s.filter(p -> p.getFileName().toString()
                        .toLowerCase().endsWith(".sql"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new BizException(ErrorCode.SCAN_FAILED, e);
        }
        return new ScannedStep(no, dir, sqls);
    }
}
```

#### 7.5.3 状态机

```
                    start(CR, Remark)
                          │
                          ▼
   ┌──────┐          ┌─────────┐
   │DRAFT │─────────►│ RUNNING │◄──────────────┐
   └──────┘          └────┬────┘               │
                          │                    │
              ┌───────────┼───────────┐        │
              │           │           │        │
        步骤成功      步骤失败     步骤成功      │
   after=CONTINUE   after=任意   after=WAIT     │
              │           │           │        │
              ▼           ▼           ▼        │
         (下一步)     ┌────────┐  ┌──────────┐  │
              │      │ FAILED │  │ WAITING  │  │
              │      └───┬────┘  └────┬─────┘  │
              │          │            │        │
              │      retryStep    continueNext │
              │          │            │        │
              │          └────────────┴────────┘
              │
        全部步骤完成
              │
              ▼
        ┌───────────┐
        │ COMPLETED │
        └───────────┘
```

**状态流转表**

| 当前状态 | 事件 | 目标状态 |
|---|---|---|
| DRAFT | start(CR, Remark) | RUNNING |
| RUNNING | 步骤成功且 `after=CONTINUE` | RUNNING（下一步） |
| RUNNING | 步骤成功且 `after=WAIT` | WAITING |
| RUNNING | 步骤失败 | FAILED |
| RUNNING | 全部步骤完成 | COMPLETED |
| WAITING | continueNext | RUNNING |
| FAILED | retryStep 成功 | WAITING（等人工决定） |
| FAILED | rerunAll | RUNNING（rerun_count +1） |
| 任意 | plan 目录不存在 | SKIPPED |

#### 7.5.4 编排引擎

```java
@Service
@RequiredArgsConstructor
public class ReleaseOrchestrator {

    private final ReleaseScanner scanner;
    private final ReleasePlanMapper planMapper;
    private final ReleaseStepMapper stepMapper;
    private final DynamicSqlExecutor executor;
    private final DataSourceRegistry registry;
    private final ReleaseLockRegistry lockRegistry;

    /** 执行者提交 CR + Remark 后启动 */
    public void start(Long planId, String crNumber, String remark, String operator) {
        ReleasePlan plan = planMapper.selectById(planId);
        if (plan == null) throw new BizException(ErrorCode.PLAN_NOT_FOUND);
        if (!"DRAFT".equals(plan.getStatus())) {
            throw new BizException(ErrorCode.PLAN_ALREADY_STARTED);
        }

        plan.setCrNumber(crNumber);
        plan.setRemark(remark);
        plan.setOperator(operator);
        plan.setStatus("RUNNING");
        plan.setStartedAt(new Date());
        planMapper.updateById(plan);

        // 扫描并初始化步骤
        List<ScannedStep> scanned = scanner.scan(
                Paths.get(plan.getBasePath(), plan.getPlanName()));

        if (scanned.isEmpty()) {
            plan.setStatus("SKIPPED");
            planMapper.updateById(plan);
            return;
        }
        stepMapper.batchInsert(scanned.stream()
                .map(s -> toStep(planId, s)).toList());

        drive(planId);
    }

    /** 驱动引擎：从当前可推进的步骤开始，直到遇到 WAIT / 失败 / 完成 */
    public void drive(Long planId) {
        ReentrantLock lock = lockRegistry.lockFor(planId);
        lock.lock();
        try {
            while (true) {
                ReleaseStep next = stepMapper.selectNextActionable(planId);
                if (next == null) {
                    markPlanCompleted(planId);
                    return;
                }
                if ("WAITING_CONTINUE".equals(next.getStatus())) return;

                runStep(next);
                ReleaseStep done = stepMapper.selectById(next.getId());

                if ("FAIL".equals(done.getStatus())) {
                    planMapper.updateStatus(planId, "FAILED");
                    return;
                }
                if ("WAIT".equals(done.getAfterMode())) {
                    stepMapper.updateStatus(done.getId(), "WAITING_CONTINUE");
                    planMapper.updateStatus(planId, "WAITING");
                    return;
                }
                // CONTINUE: 循环继续
            }
        } finally {
            lock.unlock();
        }
    }

    /** 手工点击 continue */
    public void continueNext(Long planId, String operator) {
        ReentrantLock lock = lockRegistry.lockFor(planId);
        lock.lock();
        try {
            ReleaseStep waiting = stepMapper.selectWaiting(planId);
            if (waiting == null) throw new BizException(ErrorCode.NO_WAITING_STEP);
            assertExecutor(waiting, operator);

            stepMapper.updateStatus(waiting.getId(), "SUCCESS");
            planMapper.updateStatus(planId, "RUNNING");
            driveInternal(planId);            // 已在锁内，调用内部方法
        } finally {
            lock.unlock();
        }
    }

    /** 单步重试：仅重跑该步，不触发 after_mode 判定 */
    public void retryStep(Long planId, int stepNo, String operator) {
        ReentrantLock lock = lockRegistry.lockFor(planId);
        lock.lock();
        try {
            ReleaseStep step = stepMapper.selectByNo(planId, stepNo);
            if (step == null) throw new BizException(ErrorCode.STEP_NOT_FOUND);
            if (!"FAIL".equals(step.getStatus())) {
                throw new BizException(ErrorCode.STEP_NOT_RETRYABLE);
            }
            assertExecutor(step, operator);

            stepMapper.incrementRetry(step.getId());
            runStep(step);                    // 直接执行，不进入 drive 循环

            ReleaseStep after = stepMapper.selectById(step.getId());
            if ("SUCCESS".equals(after.getStatus())) {
                // 重试成功 → 计划置为 WAITING，由人工决定是否继续
                planMapper.updateStatus(planId, "WAITING");
            }
        } finally {
            lock.unlock();
        }
    }

    /** 全流程重跑：清理步骤，重新开始，累加 rerun_count */
    public void rerunAll(Long planId, String operator) {
        ReentrantLock lock = lockRegistry.lockFor(planId);
        lock.lock();
        try {
            ReleasePlan plan = planMapper.selectById(planId);
            // 先记录上一次运行摘要（UAT 计时）
            summaryService.record(plan);

            stepMapper.deleteByPlan(planId);
            planMapper.resetForRerun(planId);   // status→DRAFT, rerun_count+1
            start(planId, plan.getCrNumber(),
                  "UAT rerun by " + operator, operator);
        } finally {
            lock.unlock();
        }
    }

    /** 单步执行：一个数字目录 = 一个事务 */
    private void runStep(ReleaseStep step) {
        long t0 = System.currentTimeMillis();
        stepMapper.markRunning(step.getId(), new Date());

        Path dir = Paths.get(step.getDirPath());
        if (!Files.isDirectory(dir)) {
            stepMapper.markSkipped(step.getId());
            return;
        }

        try (Connection conn = registry.get(step.getConnKey()).getConnection()) {
            conn.setAutoCommit(false);
            List<Path> files = listSqlFiles(dir);
            for (Path file : files) {
                List<String> stmts = SqlSplitter.split(Files.readString(file));
                int seq = 0;
                for (String sql : stmts) {
                    long s0 = System.currentTimeMillis();
                    try (Statement st = conn.createStatement()) {
                        st.setQueryTimeout(step.getTimeoutSec());
                        st.execute(sql);
                        sqlLogMapper.insert(step, file, ++seq, sql, "SUCCESS",
                                System.currentTimeMillis() - s0, null);
                    } catch (SQLException e) {
                        sqlLogMapper.insert(step, file, ++seq, sql, "FAIL",
                                System.currentTimeMillis() - s0, e.getMessage());
                        throw e;
                    }
                }
            }
            conn.commit();
            stepMapper.markSuccess(step.getId(), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            // try-with-resources 关闭时若未 commit，驱动自动 rollback
            stepMapper.markFail(step.getId(),
                    System.currentTimeMillis() - t0, e.getMessage());
        }
    }

    private void assertExecutor(ReleaseStep step, String operator) {
        if (StringUtils.hasText(step.getExecutor())
                && !step.getExecutor().equals(operator)) {
            throw new BizException(ErrorCode.NOT_AUTHORIZED_EXECUTOR);
        }
    }
}
```

#### 7.5.5 `ReleaseLockRegistry`

```java
@Component
public class ReleaseLockRegistry {
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock lockFor(Long planId) {
        return locks.computeIfAbsent(planId, k -> new ReentrantLock());
    }
}
```

> **锁的必要性**：`drive()` 可能由三种入口触发——`start()`、`continueNext()`、`retryStep()` 后的状态推进。若无锁，定时轮询或用户连点会导致同一 plan 被并发驱动，步骤重复执行。
>
> **注意**：`ReentrantLock` 可重入，但 `drive()` 与 `continueNext()` 若都加锁，需抽一个不加锁的内部方法 `driveInternal()` 供已持锁的调用方使用（见上文 `continueNext`）。

#### 7.5.6 与需求的对应

| 需求 | 实现点 |
|---|---|
| RL-1 数字目录顺序执行 | `ReleaseScanner` 数字排序 |
| RL-2 每步配置 DB | `release_step.conn_id` |
| RL-3 编排条件 | `release_step.after_mode` |
| RL-4 指定执行者 | `release_step.executor` + `assertExecutor` |
| RL-5 CR + Remark | `start()` 入参 |
| RL-6 WAIT 手工 continue | `WAITING_CONTINUE` 状态 + `continueNext()` |
| RL-7 CONTINUE 自动推进 | `drive()` 循环 |
| RL-8 目录不存在忽略 | `scan()` 返回空 / `runStep()` 二次校验 |
| RL-9 全流程重跑 | `rerunAll()` + `release_run_summary` |
| RL-10 单步重试不触发 continue | `retryStep()` 直接 `runStep()`，不走 `drive()` |

---

## 8. 接口设计

### 8.1 连接管理

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/connections` | 新建连接 |
| PUT | `/api/connections/{id}` | 更新连接 |
| POST | `/api/connections/{id}/test` | 测试连通性 |
| POST | `/api/connections/{id}/reload` | 重载连接池 |
| DELETE | `/api/connections/{id}` | 删除（软删，enabled=0） |

### 8.2 健康检查

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/health-checks` | 新建（含 L1 只读校验） |
| PUT | `/api/health-checks/{id}` | 更新（写 history） |
| POST | `/api/health-checks/{id}/run` | 手动执行 |
| GET | `/api/health-checks/{id}/runs?page=&size=` | 执行记录分页 |
| GET | `/api/health-checks/{id}/history` | 修改记录 |
| POST | `/api/health-checks/{id}/enable` | 启用并注册调度 |
| POST | `/api/health-checks/{id}/disable` | 停用并取消调度 |

### 8.3 发布

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/releases/scan` | 预览目录结构（不落库） |
| POST | `/api/releases` | 创建 plan（DRAFT） |
| POST | `/api/releases/{id}/start` | 启动，body: `{crNumber, remark}` |
| POST | `/api/releases/{id}/continue` | 手工继续 |
| POST | `/api/releases/{id}/steps/{no}/retry` | 单步重试 |
| POST | `/api/releases/{id}/rerun` | 全流程重跑 |
| GET | `/api/releases/{id}` | 计划详情（含所有 step 状态与耗时） |
| GET | `/api/releases/{id}/logs?stepNo=` | 明细日志 |
| GET | `/api/releases/{id}/stream` | SSE 状态推送 |

### 8.4 统一响应体

```json
{
  "code": 0,
  "message": "ok",
  "data": { },
  "traceId": "a1b2c3d4"
}
```

---

## 9. 关键流程时序

### 9.1 健康检查执行

```
Scheduler ──► HealthCheckService.execute(defId, "CRON")
                     │
                     ├─► loadSnapshot(defId)            # 读 version
                     ├─► SqlGuard.assertSelectOnly()    # L2
                     ├─► runMapper.insert(RUNNING)
                     ├─► DynamicSqlExecutor.query()
                     │        └─► DataSourceRegistry.get(connKey)
                     │                 └─► HikariPool.getConnection()
                     ├─► AssertEvaluator.evaluate()
                     └─► runMapper.updateResult(SUCCESS/FAIL)
```

### 9.2 发布启动（含 WAIT 卡点）

```
User ──► POST /releases/{id}/start {cr, remark}
              │
              ├─► 校验 plan.status == DRAFT
              ├─► 落 CR / Remark / operator, status=RUNNING
              ├─► ReleaseScanner.scan()  → 步骤落库
              └─► drive(planId)  [持锁]
                     │
                     ├─► step1: runStep() → SUCCESS
                     ├─► step1.after = CONTINUE → 继续
                     ├─► step2: runStep() → SUCCESS
                     ├─► step2.after = WAIT
                     │      └─► step2.status=WAITING_CONTINUE
                     │          plan.status=WAITING
                     └─► return（等待人工）

User ──► POST /releases/{id}/continue   [持锁]
              ├─► step2.status = SUCCESS
              ├─► plan.status = RUNNING
              └─► driveInternal() → step3...
```

### 9.3 单步重试

```
User ──► POST /releases/{id}/steps/3/retry   [持锁]
              ├─► 校验 step3.status == FAIL
              ├─► 校验 operator == step3.executor
              ├─► retry_count + 1
              ├─► runStep(step3)          # 直接执行
              └─► 成功 → plan.status = WAITING
                         （不自动进入 step4，符合 RL-10）
```

---

## 10. 并发与线程模型

### 10.1 线程来源

| 线程来源 | 用途 | 配置 |
|---|---|---|
| Tomcat 工作线程 | 处理 HTTP 请求 | 默认 200 |
| `hc-sched-*` | 健康检查定时触发 | `ThreadPoolTaskScheduler.poolSize = 8` |
| `release-driver-*` | 发布驱动（如异步化） | 一期同步执行，暂不引入 |
| Hikari 内部线程 | 连接保活、超时检测 | 每池独立 |

### 10.2 默认线程池风险

| 风险 | 后果 | 规避 |
|---|---|---|
| `TaskScheduler` 默认池大小 = 1 | 一个慢健康检查阻塞全部 | 显式 `setPoolSize(8)` |
| `@Async` 默认 `SimpleAsyncTaskExecutor` | 每次新建线程，无上限 | 换 `ThreadPoolTaskExecutor`，设 core/max/queue/reject |
| Hikari 默认 `maximumPoolSize=10` | 并发任务多时连接耗尽 | 按库配置，且 `poolSize × 并发任务数 ≤ DB max_connections` |

### 10.3 发布并发控制

| 层级 | 机制 | 目的 |
|---|---|---|
| 单 plan | `ReentrantLock`（`ReleaseLockRegistry`） | 防止并发驱动导致步骤重复执行 |
| 跨 plan | 无锁，天然并行 | 不同发布计划互不干扰 |
| 步骤内 | 单线程串行 | 保证 SQL 执行顺序 |
| 事务 | 一目录一事务 | 失败整目录回滚 |

### 10.4 ThreadLocal 陷阱

**问题**：若使用 `AbstractRoutingDataSource` + `ThreadLocal` 做数据源路由，在线程池复用的场景下（定时任务、异步任务），上一次请求的 ThreadLocal 值可能残留，导致**SQL 执行到错误的库**。这是数据安全事故。

**本设计规避方式**：
- 不使用 `AbstractRoutingDataSource`
- 数据源 key 作为方法参数显式传递
- 若未来引入任何 ThreadLocal 上下文，必须在 `finally` 中 `remove()`

```java
// 反例（禁止）
try {
    DbContextHolder.set(connKey);
    executor.query(sql);
} finally {
    DbContextHolder.clear();   // 一旦遗漏，下次复用该线程就串号
}

// 本设计（推荐）
executor.query(connKey, sql, params, maxRows, timeout);
```

### 10.5 锁与事务顺序

- 锁必须在事务**外层**：`lock → 业务逻辑（含事务） → unlock`
- 若事务在锁内，`runStep` 的事务提交后立即释放锁，安全
- 禁止在持锁期间做远程调用 / 文件 IO 之外的长阻塞

---

## 11. 异常处理与错误码

### 11.1 错误码规范

`{模块}{类型}{序号}`，模块：`DS`(数据源) `SG`(SqlGuard) `HC`(健康检查) `RL`(发布) `SYS`(系统)。

| 错误码 | 含义 | HTTP |
|---|---|---|
| `SYS0001` | 系统内部错误 | 500 |
| `SYS0002` | 参数校验失败 | 400 |
| `DS0001` | 数据源不存在 | 404 |
| `DS0002` | 数据源连接失败 | 502 |
| `DS0003` | 数据源已禁用 | 400 |
| `SG0001` | SQL 解析失败 | 400 |
| `SG0002` | 包含非 SELECT 语句 | 400 |
| `SG0003` | 包含禁用子句 | 400 |
| `HC0001` | 健康检查定义不存在 | 404 |
| `HC0002` | 断言配置非法 | 400 |
| `HC0003` | Cron 表达式非法 | 400 |
| `HC0100` | SQL 执行超时 | 504 |
| `HC0101` | SQL 执行失败 | 500 |
| `RL0001` | 发布计划不存在 | 404 |
| `RL0002` | 计划已启动 | 409 |
| `RL0003` | 步骤不存在 | 404 |
| `RL0004` | 步骤不可重试（非 FAIL 状态） | 409 |
| `RL0005` | 当前无等待中的步骤 | 409 |
| `RL0006` | 非指定执行者 | 403 |
| `RL0007` | 目录扫描失败 | 500 |
| `RL0100` | 步骤执行失败 | 500 |

### 11.2 全局异常处理器

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<?> handleBiz(BizException e) {
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(SqlGuardException.class)
    public R<?> handleGuard(SqlGuardException e) {
        return R.fail(ErrorCode.SG_INVALID_SQL, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public R<?> handleUnknown(Exception e, HttpServletRequest req) {
        log.error("unhandled exception, traceId={}", MDC.get("traceId"), e);
        return R.fail(ErrorCode.SYS_INTERNAL, "系统内部错误");
    }
}
```

### 11.3 发布失败处理策略

| 失败位置 | 处理 |
|---|---|
| 步骤内某条 SQL 失败 | 整目录 rollback，步骤置 FAIL，计划置 FAILED |
| 步骤目录执行前被删 | 二次校验失败，步骤置 SKIPPED，继续下一步 |
| 目标库连接失败 | 步骤置 FAIL，不重试（避免雪崩） |
| 提交事务失败 | 步骤置 FAIL，错误信息落库 |

---

## 12. 安全设计

### 12.1 连接凭据

| 项 | 方案 |
|---|---|
| 存储 | AES-256 加密，密钥不落库（环境变量 / KMS） |
| 传输 | 全程 HTTPS，密码字段前端提交后立即加密 |
| 展示 | 接口不返回明文密码，仅返回掩码 `******` |
| 轮换 | 支持手动更新，更新后 `reload` 连接池 |

### 12.2 只读强制

- L1 保存校验 / L2 执行前校验 / L3 `setReadOnly` / L4 只读账号
- 健康检查的 `conn_id` 建议指向**只读账号**，从数据库层兜底

### 12.3 权限模型（一期）

| 角色 | 权限 |
|---|---|
| 管理员 | 连接管理、健康检查 CRUD、发布计划创建 |
| 执行者 | 启动计划、continue、retry（仅限被指定的步骤） |
| 只读用户 | 查看健康检查记录、发布进度 |

### 12.4 SQL 注入防护

- 健康检查 SQL 由管理员配置，但仍强制 `PreparedStatement` 参数化
- 断言操作符走白名单，不走 SpEL
- 发布 SQL 来自文件系统，需保证 `base_path` 不可被用户任意指定（防止路径穿越读任意文件）

```java
// 路径穿越防护
Path base = Paths.get(config.getBasePath()).toRealPath();
Path target = base.resolve(planName).normalize();
if (!target.startsWith(base)) {
    throw new BizException(ErrorCode.SYS_PARAM_INVALID, "非法路径");
}
```

---

## 13. 可观测性

### 13.1 日志

| 场景 | 级别 | 关键字段 |
|---|---|---|
| 健康检查执行 | INFO | defId, version, status, durationMs |
| SqlGuard 拦截 | WARN | defId, sql 摘要, 违规类型 |
| 发布步骤执行 | INFO | planId, stepNo, status, durationMs |
| 发布 SQL 明细 | DEBUG | stepId, fileName, seq |
| 数据源重载 | INFO | connKey, poolSize |
| 未捕获异常 | ERROR | traceId, stack |

### 13.2 指标（Micrometer / Prometheus）

| 指标 | 类型 | 标签 |
|---|---|---|
| `hc_exec_total` | Counter | defId, status |
| `hc_exec_duration_ms` | Histogram | defId |
| `release_step_total` | Counter | planId, stepNo, status |
| `release_step_duration_ms` | Histogram | planId, stepNo |
| `db_pool_active` | Gauge | connKey |
| `db_pool_idle` | Gauge | connKey |
| `db_pool_pending` | Gauge | connKey |

### 13.3 告警建议

- 健康检查连续 N 次 FAIL → 告警
- 发布步骤 FAIL → 立即告警
- 连接池 pending > 0 持续 1 分钟 → 告警
- 步骤耗时超过历史 P95 的 2 倍 → 提示

---

## 14. 性能与容量

### 14.1 容量假设

| 项 | 假设 |
|---|---|
| 健康检查定义数 | ≤ 500 |
| 并发定时任务 | ≤ 50（Cron 峰值） |
| 单个健康检查耗时 | 典型 < 1s，超时上限 30s |
| 发布计划数 | ≤ 100 / 月 |
| 单计划步骤数 | ≤ 9 |
| 单步骤 SQL 数 | ≤ 100 |
| 发布期间并发计划 | ≤ 5 |

### 14.2 容量估算

- **连接池**：健康检查库建议 `poolSize = 10`，若 50 个任务并发，需 `5 × 10 = 50` 连接，需评估 DB `max_connections`
- **调度线程**：`poolSize = 8` 可覆盖典型场景，若慢查询多需上调
- **结果集**：`setMaxRows(1000)` + `result_head` 只存前 20 行，避免大对象落库

### 14.3 潜在瓶颈与优化

| 瓶颈 | 优化 |
|---|---|
| 单个大目录发布耗时过长 | 目录内 SQL 分批提交（需业务确认是否可接受部分成功） |
| 健康检查结果集过大 | 强制 `maxRows`，断言改用 `SELECT COUNT(*)` |
| `release_sql_log` 表膨胀 | 按月分区 / 定期归档 |
| SSE 连接数过多 | 改用轮询 + 增量拉取 |

---

## 15. 部署与配置

### 15.1 配置示例

```yaml
server:
  port: 8080

spring:
  datasource:                      # 平台元数据库
    url: jdbc:mysql://localhost:3306/sql_pipeline?useSSL=false
    username: pipeline
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20

mybatis:
  mapper-locations: classpath:mapper/*.xml
  configuration:
    map-underscore-to-camel-case: true

sql-pipeline:
  release:
    base-path: /data/releases
    scan:
      include-pattern: "*.sql"
      max-steps: 99
  health:
    scheduler:
      pool-size: 8
    default-timeout-sec: 30
    default-max-rows: 1000
  guard:
    allow-dml: false
    forbidden-keywords:
      - "for update"
      - "into outfile"
      - "into dumpfile"
  crypto:
    key: ${SQL_PIPELINE_AES_KEY}
```

### 15.2 部署拓扑

```
                    ┌──────────────┐
                    │  Nginx / LB  │
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │ sql-pipeline │  （单实例，一期）
                    │  Spring Boot │
                    └──┬────────┬──┘
                       │        │
          ┌────────────▼─┐   ┌──▼────────────┐
          │ 平台元数据库  │   │  目标业务库    │
          │  (MySQL)     │   │ (MySQL/Oracle)│
          └──────────────┘   └───────────────┘
```

> **单实例约束**：一期发布编排基于 JVM 内锁，多实例会破坏互斥。若需多实例，需改为数据库悲观锁（`SELECT ... FOR UPDATE`）或分布式锁（Redis / ZooKeeper）。

### 15.3 文件系统约束

- `base-path` 必须是所有实例可访问的共享存储（NFS / OSS 挂载），或单实例本地存储
- 发布目录由发布者写入，平台只读

---

## 16. 技术风险与决策记录

### 16.1 决策记录（ADR）

| ID | 决策 | 理由 | 替代方案 |
|---|---|---|---|
| ADR-01 | 动态 SQL 用原生 JDBC，不用 MyBatis | 避免 `Configuration` 污染，超时/行数控制更细 | MyBatis `SqlSession` 动态执行 |
| ADR-02 | 数据源按 key 显式传递，不用 `AbstractRoutingDataSource` | 规避线程池 ThreadLocal 串号 | ThreadLocal 路由 + 严格 clear |
| ADR-03 | 只读校验用 JSqlParser，不用正则 | AST 级判断，无法绕过 | 正则匹配关键词 |
| ADR-04 | 发布编排用状态机 + 锁，不引入流程引擎 | 需求简单，引入 Flowable 过重 | Flowable / Activiti |
| ADR-05 | 步骤即事务边界 | 符合发布语义，失败可控回滚 | 单条 SQL 一事务 |
| ADR-06 | `retryStep` 不走 `drive()` | 满足 RL-10，重试不触发后续编排 | 复用 `drive()` + 标记跳过 |
| ADR-07 | 一期单实例部署 | 锁实现简单，容量足够 | 分布式锁 |
| ADR-08 | 断言自行实现操作符表，不用 SpEL | 避免表达式注入 | SpEL / Aviator |

### 16.2 风险清单

| 风险 | 影响 | 概率 | 缓解 |
|---|---|---|---|
| 目标库连接不稳定 | 发布失败 | 中 | 失败快速终止，不自动重试；单步重试人工介入 |
| 大目录发布超时 | 用户体验差 | 中 | 支持拆分目录；明细日志可定位 |
| `release_sql_log` 膨胀 | 存储压力 | 高 | 按月分区 + 归档策略 |
| 平台元数据库单点 | 平台不可用 | 中 | 主从 + 定期备份 |
| `base_path` 被误删 | 发布失败 | 低 | 扫描前二次校验，目录不存在则 SKIPPED |
| 多实例部署破坏锁 | 步骤重复执行 | — | 一期强制单实例；文档明确约束 |

---

## 17. 附录

### 17.1 状态枚举

```java
public enum PlanStatus {
    DRAFT, RUNNING, WAITING, PAUSED, COMPLETED, FAILED, SKIPPED
}

public enum StepStatus {
    PENDING, RUNNING, SUCCESS, FAIL, WAITING_CONTINUE, SKIPPED
}

public enum AfterMode {
    CONTINUE, WAIT
}

public enum RunStatus {
    SUCCESS, FAIL, ERROR, TIMEOUT
}
```

### 17.2 模块划分

```
sql-pipeline-parent
├── sql-pipeline-common         # 通用：错误码、工具、响应体
├── sql-pipeline-datasource     # DataSourceRegistry、SqlGuard、DynamicSqlExecutor
├── sql-pipeline-health         # 健康检查：Service、Scheduler、AssertEvaluator
├── sql-pipeline-release        # 发布：Scanner、Orchestrator、状态机
├── sql-pipeline-web            # Controller、DTO、全局异常
└── sql-pipeline-boot           # 启动类、配置
```

### 17.3 术语缩写

| 缩写 | 全称 |
|---|---|
| TDD | Technical Design Document |
| ADR | Architecture Decision Record |
| CR | Change Request |
| SSE | Server-Sent Events |
| AST | Abstract Syntax Tree |
| DDL | Data Definition Language |
| DML | Data Manipulation Language |

---

## 18. 实现修订（v1.1，与代码同步）

本章记录 v1.1 实现过程中对前述章节的修订、需求新增与落地状态。**与前文冲突之处以本章为准。**

### 18.1 平台选型修订（§5）

| 项 | v1.0 设计 | v1.1 实现 |
|---|---|---|
| 元数据库 / 目标库 | 未限定（示例 MySQL） | **仅 PostgreSQL**（平台库 + 目标库均内置 PG 驱动；DDL 全量 PG 方言：IDENTITY / JSONB / TEXT / COMMENT ON） |
| 动态 SQL 执行 | 原生 JDBC | 不变（ADR-01 维持） |
| 密码加密 | AES-256（模式未定） | AES-256-GCM（随机 IV 前置），密钥经 `SQL_PIPELINE_AES_KEY` 注入，缺失启动失败；同时用于告警通道凭据 |
| SQL 解析 | JSqlParser | 维持；另增自研 `SqlSplitter`（状态机，支持 PG dollar-quote、嵌套块注释） |

### 18.2 数据模型修订（§6）

| 表 | 修订 |
|---|---|
| 全部 | DDL 改 PostgreSQL；`db_connection` 增 `default_schema`（Hikari setSchema，支持 share folder 目标库的 schema 指定） |
| `sql_definition` | `conn_id` → `conn_key`；`params_json` 语义改为 JSON **对象**（`${name}` 命名占位符）；增 `assert_op`、`assert_value`（断言简化，见 18.4），`assert_config` 保留兼容 |
| `sql_definition_history` | 同步增 `assert_op`/`assert_value`；不设 `(sql_def_id, version)` 唯一键 |
| `health_check_run` | 不变（result_head 前 20 行 JSON，截断 2000 字符） |
| `release_plan` | 增 `release_type`（FOLDER / ZIP）、`release_path`（发布路径，支持 share folder 绝对路径）、`default_conn_key`；`step_config` 结构改为 `[{dirName, stepNo, connKey, afterMode, executor}]`（目录名 ↔ 运行编号映射） |
| `release_step` | `conn_id` → `conn_key`；增 `confirm_by`/`confirm_at`（WAIT 人工确认审计）、`retry_remark`（重试备注）、`script_hash`（脚本变更检测） |
| `release_sql_log` | 增 `step_no`、`run_seq`、`operator`（冗余列：步骤删除/多轮重跑后日志仍可按计划查询与区分轮次） |
| **新增** `notify_channel` | 告警通道：type（XMATTERS）、url、auth_type（NONE/BASIC/API_KEY）、secret_enc、events（JSONB 订阅事件数组）、hc_fail_threshold、enabled |
| **新增** `notify_log` | 推送日志：channel、event、status、response、payload |

### 18.3 Release Runbook 重设计（§7.5）

- **定位更名**：Release Orchestration → **Release Plan / Runbook**。
- **创建模型**：plan_name 默认 `release_yyyyMMdd`；`release_type = FOLDER / ZIP`（ZIP 解压占位未实现，scan/start 明确提示）；`release_path` 必填（share folder 绝对路径或 base-path 相对路径，防穿越校验）。
- **SCAN 与可重编号**：SCAN 读取 release_path 下数字子目录（asc，`^[1-9]\d*$`，拒绝 0 与前导零），生成可编辑步骤行；运行编号默认 = 目录名数字，可调整，启动按配置编号排序执行（重复编号拒绝）。
- **编辑权限**：`PUT /api/releases/{id}` 仅 DRAFT 可编辑（类型/路径/默认连接/步骤配置；planName 锁定）；非 DRAFT 返回 409（error.rl.editOnlyDraft），运行过的计划只能 View（全只读弹窗）。
- **异步驱动**：HTTP 请求（start/continue/retry/rerun）仅同步完成校验与元数据写入（元数据事务用 `TransactionTemplate` 保证原子），实际驱动提交 `release-driver` 线程池（§10.1 预留项落地），进度经 SSE 推送。
- **plan 级互斥**：§7.5.5 的 JVM `ReentrantLock` 改为 **PG advisory lock**（`pg_try_advisory_lock`，等待 15s 超时返回 409 RL0008）——崩溃自动释放、支持多实例（ADR-09）。
- **步骤执行**：维持"一目录一事务"；新增 ① 步骤总超时（`step-total-timeout-sec`，语句间检查）；② 目录含 `_nontransactional` 标记文件时逐条自动提交（用于 `CREATE INDEX CONCURRENTLY` 等非事务 DDL，事务模式下检测到 CONCURRENTLY 直接拒绝并提示）；③ 失败不自动重试，连接失败同样置 FAIL。
- **审计与恢复**：WAIT continue 记录 confirm_by/at；retry 带可选备注；启动恢复（`ApplicationReadyEvent`）把 RUNNING 步骤置 FAIL（错误信息明确提示"可能已在目标库提交，重试前人工核对"）、RUNNING 计划按步骤状态收敛为 FAILED / WAITING / COMPLETED（补记摘要）。
- **脚本变更检测**：start 时计算步骤目录 SHA-256 存档，详情接口实时比对返回 `scriptChanged`。
- **Pipeline 可视化**：前端步骤以事件节点流呈现（横向/纵向切换、分段进度条、`⏸ Manual gate` 卡点标记、节点点击查看运行结果与 SQL 明细），SSE 驱动实时刷新。

### 18.4 健康检查修订（§7.3/7.4）

- **参数绑定**：§7.3 的 `?` 位置绑定改为 **`${name}` 命名占位符** + JSON 对象参数（默认 `{}`，手动执行可按名覆盖）；解析在 SqlGuard 校验之前进行（占位符缺失值在保存/执行时拒绝）。
- **断言简化**：§7.4.1 的三类断言（VALUE/ROWCOUNT/RECORD + JSON 配置 + expr 表达式）简化为**三要素**：`assertType`（VALUE=第一行第一列 / ROWS=返回行数）+ `assertOp`（`== != > >= < <=`）+ `assertValue`（数值）。非数值实际值判 FAIL；操作符白名单实现不变（ADR-08 维持）。

### 18.5 告警通知模块（新增，§12/13 扩展）

- **通道**（`notify_channel`，存平台库）：xMatters API（POST JSON 至 Inbound Integration / Flow Webhook 触发地址），认证 NONE / BASIC / API_KEY（Header 可配，默认 `apikey`），凭据 AES-256-GCM 加密。
- **事件**：`HC_FAIL`（连败达到通道阈值触发一次，成功清零计数；阈值=1 即每次失败告警）、`RELEASE_STEP_FAIL`、`PLAN_FAIL`、`RELEASE_STEP_SUCCESS`、`PLAN_COMPLETED`（后两类需显式订阅）。
- **可靠性**：推送走独立线程池（CallerRunsPolicy，不丢事件）；推送失败不影响业务主流程；每次推送落 `notify_log`（含响应摘要与错误）。
- **API**：`/api/notify/channels` CRUD + `/{id}/test`（同步 TEST 事件）+ `/logs`。

### 18.6 国际化（新增）

- 前端：`static/js/i18n.js` 集中字典（en 默认 / zh），`data-i18n` 属性渲染，语言偏好 localStorage。
- 后端：Spring MessageSource（`messages*.properties`），`BizException.i18n(errorCode, key, args...)` 全量替换硬编码消息，`GlobalExceptionHandler` 按 locale 渲染。
- 语言选择：前端切换写 `LANG` Cookie（后端 `CookieLocaleResolver` 默认 ENGLISH + `?lang=` 参数同步）。

### 18.7 Web 管理界面（新增，文档 v1.0 未覆盖）

jQuery 3.7.1 + Bootstrap 5.2.3（本地化内置，离线可用），五个页面：总览 / 连接管理 / 健康检查 / Release Plan/Runbook（Pipeline 可视化）/ 告警通知；操作者身份经 `X-Operator` 头（localStorage）；统一响应体与错误 toast；TraceId 贯穿。

### 18.8 ADR 增补

| ID | 决策 | 理由 | 替代方案 |
|---|---|---|---|
| ADR-09 | plan 级互斥用 PG advisory lock | 崩溃自动释放、多实例可用、无需引入 Redis/ZK | JVM ReentrantLock（v1.0）/ Redis 分布式锁 |
| ADR-10 | 健康检查参数用 `${name}` 命名占位符 + JSON 对象 | 需求方指定；SQL 可读性优于 `?` 位置绑定 | `?` 位置绑定 |
| ADR-11 | 断言简化为三要素模型 | 需求方简化；覆盖数值/行数判定的核心场景 | 三类断言 + JSON 配置 |
| ADR-12 | 告警通道配置存库（notify_channel）+ Sender SPI | 新增通道类型（钉钉/飞书等）零代码侵入路由 | 配置文件静态通道 |
| ADR-13 | 告警标题/消息经 MessageSource 渲染 | 与平台 i18n 一致；后台线程取系统 locale | 双语硬编码 |
| ADR-14 | ZIP 类型占位（不实现解压） | 需求方明确"先放着"，接口与数据模型预留 | 直接不出现该类型 |

### 18.9 约束与风险更新（§16.2）

| 项 | v1.0 | v1.1 |
|---|---|---|
| 单实例约束 | JVM 锁，强制单实例 | 已解除（advisory lock）；多实例需共享发布目录；健康检查连败计数为实例内各自统计 |
| 崩溃恢复 | 未设计 | 已实现（§18.3）；残余风险：commit 后崩溃的步骤可能已提交，恢复提示人工核对后重试 |
| 大目录发布 | 分批提交待定 | 已支持 600+ 文件目录（异步驱动 + 步骤总超时兜底）；`_nontransactional` 提供"逐条提交"逃生舱 |
| `release_sql_log` 膨胀 | 按月分区 / 归档 | 未实现（待二期） |

---

**文档结束**

如需针对某一部分展开（例如 `SqlSplitter` 的完整实现、SSE 推送设计、多实例分布式锁改造方案），可以继续提出。