# sql-pipeline

SQL 健康检查与发布编排平台：只读守卫（SqlGuard）、定时健康检查、按目录顺序的发布编排、WAIT/CONTINUE 人工卡点。

> 按技术设计文档 `tech_design.md`（v1.0）实现。**平台元数据库与目标业务库均以 PostgreSQL 为主**（一期不考虑 Oracle / MySQL）。

## 功能总览

| 模块 | 能力 |
|---|---|
| 数据源管理 | 平台内定义 PostgreSQL 连接，HikariCP 连接池运行期动态注册 / 热更新 / 连通性测试；密码 AES-256-GCM 加密存储，接口仅返回掩码 |
| 健康检查 | 只读 SQL（JSqlParser AST 级 SELECT-only 校验，保存与执行双重拦截 + `setReadOnly` 兜底）、三类断言（VALUE / ROWCOUNT / RECORD）、Spring Cron 动态调度、修改记录（版本快照）与执行记录 |
| 发布编排 | 按 `release_YYYYMMDD/1..9` 数字目录顺序执行、每步目标库 / after_mode（CONTINUE / WAIT）/ 执行者可配置、CR Number + Remark 启动、单步重试（不触发后续编排）、全流程重跑（UAT 计时摘要）、目录缺号 / 不存在自动忽略、SSE 进度推送 |

## 模块结构（对齐设计文档 §17.2）

```
sql-pipeline-parent
├── sql-pipeline-common        # 错误码、异常、统一响应体、JSON 工具
├── sql-pipeline-datasource    # DataSourceRegistry、SqlGuard、SqlSplitter、DynamicSqlExecutor、CryptoService
├── sql-pipeline-health        # 健康检查：AssertEvaluator、HealthCheckScheduler、HealthCheckService
├── sql-pipeline-release       # 发布：ReleaseScanner、ReleaseOrchestrator（状态机）、ReleaseEventPublisher
├── sql-pipeline-web           # Controller、DTO、全局异常、TraceId
└── sql-pipeline-boot          # 启动类、application.yml、PostgreSQL 初始化脚本
```

技术栈：Spring Boot 3.5 / Java 17+（在 JDK 17~25 上均可运行）/ MyBatis / HikariCP / JSqlParser / PostgreSQL / Micrometer。

## 快速开始

### 1. 准备 PostgreSQL（平台库）

```bash
docker compose -f docker/docker-compose.yml up -d
# 库：sql_pipeline  用户：pipeline  密码：pipeline
```

建表脚本 `sql-pipeline-boot/src/main/resources/schema.sql` 会在应用首次启动时自动执行（幂等 `CREATE TABLE IF NOT EXISTS`）。

### 2. 生成并注入 AES 密钥

```bash
# 方式一：openssl
export SQL_PIPELINE_AES_KEY=$(openssl rand -base64 32)
# 方式二：运行项目自带工具（构建后）
java -cp sql-pipeline-boot/target/classes com.sqlpipeline.boot.tools.AesKeyGen
```

> 密钥用于加密 `db_connection.password_enc`。**更换密钥后已保存的连接将无法解密**，需重新录入密码。

### 3. 构建与启动

```bash
./mvnw -DskipTests package
SQL_PIPELINE_AES_KEY=$SQL_PIPELINE_AES_KEY \
RELEASE_BASE_PATH=$(pwd)/sample/releases \
java -jar sql-pipeline-boot/target/sql-pipeline-boot-1.0.0-SNAPSHOT.jar
```

启动后访问 `http://localhost:8080`（API）、`http://localhost:8080/actuator/prometheus`（指标）。

## 配置说明

| 配置 | 默认值 | 说明 |
|---|---|---|
| `spring.datasource.*` | localhost:5432/sql_pipeline | 平台元数据库（PostgreSQL） |
| `SQL_PIPELINE_AES_KEY` | 无（必填，缺失启动失败） | 连接密码加密密钥 |
| `sql-pipeline.release.base-path` | `./data/releases` | 发布目录根路径，发布者对它只写、平台只读 |
| `sql-pipeline.release.sql-suffix` | `.sql` | 步骤目录内纳入执行的文件后缀 |
| `sql-pipeline.release.max-steps` | `99` | 单计划最大步骤数 |
| `sql-pipeline.release.statement-timeout-sec` | `60` | 发布单条 SQL 超时 |
| `sql-pipeline.health.scheduler.pool-size` | `8` | 健康检查调度线程池（Boot 默认 1，必须显式设置） |
| `sql-pipeline.health.default-timeout-sec` | `30` | 健康检查 SQL 默认超时 |
| `sql-pipeline.health.default-max-rows` | `1000` | 默认最大返回行数（取连接配置 max_rows 优先） |

## 发布目录约定

```
{base-path}/release_20261003/     # 一个目录 = 一个发布计划（plan_name）
├── 1/                            # 一个数字目录 = 一个步骤 = 一个事务
│   ├── 01_create_table.sql       # 目录内按文件名字典序执行（建议 01_、02_ 前缀）
│   └── 02_init_data.sql
├── 2/10_insert.sql
└── 5/01_verify.sql               # 目录 3、4 缺号：自动忽略，5 正常执行
```

规则：一级子目录名必须为纯数字；非数字目录、空目录（无 `.sql`）忽略；不递归子目录；整个计划目录不存在 → 计划标记 `SKIPPED`；步骤执行前目录被删 → 该步骤 `SKIPPED` 继续。

## API 一览

统一响应体：`{"code":"0","message":"ok","data":{},"traceId":"..."}`；操作者身份经请求头 `X-Operator` 传递（一期无登录态）。

### 连接管理 `/api/connections`

```bash
# 注册目标库连接（这里指向平台库自身作演示）
curl -s -X POST localhost:8080/api/connections -H 'Content-Type: application/json' -H 'X-Operator: admin' -d '{
  "connKey": "bizdb", "displayName": "业务演示库",
  "jdbcUrl": "jdbc:postgresql://localhost:5432/sql_pipeline",
  "username": "pipeline", "password": "pipeline",
  "maxRows": 500, "queryTimeoutS": 15
}'

curl -s -X POST localhost:8080/api/connections/1/test     # 连通性测试
curl -s -X POST localhost:8080/api/connections/1/reload   # 热更新连接池
```

### 健康检查 `/api/health-checks`

```bash
# 创建（保存时即做 L1 只读校验；含断言与 Cron）
curl -s -X POST localhost:8080/api/health-checks -H 'Content-Type: application/json' -H 'X-Operator: admin' -d '{
  "name": "演示库连通性", "connKey": "bizdb", "sqlText": "SELECT 1 AS ok",
  "assertType": "VALUE", "assertConfig": "{\"type\":\"VALUE\",\"expr\":\"cell(0,0)\",\"op\":\"==\",\"value\":1}",
  "cronExpr": "0 */5 * * * *", "timeoutSec": 10
}'

# 尝试提交写操作会被拦截（SG0002）
curl -s -X POST localhost:8080/api/health-checks -H 'Content-Type: application/json' -d '{
  "name": "bad", "connKey": "bizdb", "sqlText": "DELETE FROM release_demo_audit"
}'

curl -s -X POST localhost:8080/api/health-checks/1/run    # 手动执行
curl -s 'localhost:8080/api/health-checks/1/runs?page=1&size=10'  # 执行记录
curl -s localhost:8080/api/health-checks/1/history        # 修改记录（版本快照）
```

断言配置（`assert_config`，JSONB）示例：

```jsonc
{"type":"VALUE","expr":"cell(0,0)","op":"==","value":1}          // 也支持 expr=rowCount
{"type":"ROWCOUNT","op":">=","value":1}
{"type":"RECORD","mode":"ALL","rules":[{"field":"status","op":"not_null"},{"field":"type","op":"in","value":["A","B"]}]}
```

操作符：`== != > >= < <= not_null is_null in not_in regex`。Cron 为 Spring 6 段格式（秒 分 时 日 月 周）。

### 发布 `/api/releases`

```bash
# 1) 预览目录扫描结果（不落库）
curl -s -X POST localhost:8080/api/releases/scan -H 'Content-Type: application/json' -d '{
  "planName": "release_20261003", "defaultConnKey": "bizdb",
  "steps": [{"stepNo":2,"afterMode":"WAIT","executor":"zhangsan"}]
}'

# 2) 创建计划（DRAFT），步骤配置保存于 plan
curl -s -X POST localhost:8080/api/releases -H 'Content-Type: application/json' -H 'X-Operator: admin' -d '{
  "planName": "release_20261003", "defaultConnKey": "bizdb",
  "steps": [{"stepNo":2,"afterMode":"WAIT","executor":"zhangsan"}]
}'

# 3) 执行者凭 CR + Remark 启动（同步驱动：CONTINUE 步骤连跑，到 WAIT 步骤停下）
curl -s -X POST localhost:8080/api/releases/1/start -H 'Content-Type: application/json' -H 'X-Operator: zhangsan' \
  -d '{"crNumber":"CR-20261003-001","remark":"10月迭代 UAT 发布"}'

# 4) WAIT 步骤人工确认后推进
curl -s -X POST localhost:8080/api/releases/1/continue -H 'X-Operator: zhangsan'

# 失败处理
curl -s -X POST localhost:8080/api/releases/1/steps/3/retry -H 'X-Operator: zhangsan'  # 单步重试（不触发 continue 规则）
curl -s -X POST localhost:8080/api/releases/1/rerun -H 'X-Operator: admin'             # 全流程重跑（UAT 计时）

# 查询
curl -s localhost:8080/api/releases/1                 # 计划详情（含步骤状态与耗时）
curl -s 'localhost:8080/api/releases/1/logs?stepNo=2' # SQL 明细日志
curl -s localhost:8080/api/releases/1/summaries       # 每次运行摘要（UAT 统计）
curl -N localhost:8080/api/releases/1/stream          # SSE 实时进度（step / plan 事件）
```

### 状态机

```
DRAFT --start(CR,Remark)--> RUNNING --全部完成--> COMPLETED
                              │  ├─ 步骤成功 & after=WAIT ──> WAITING --continue--> RUNNING
                              │  └─ 步骤失败 ──> FAILED --retry成功--> WAITING --continue--> RUNNING
                              │                            └──rerun--> RUNNING（rerun_count+1）
                              └─ 目录不存在 ──> SKIPPED
```

## 可观测性

- 日志：健康检查执行（INFO）、SqlGuard 拦截、发布步骤执行、未捕获异常（含 traceId）
- 指标（`/actuator/prometheus`）：`hc_exec_total`、`hc_exec_duration_ms`、`release_step_total`、`release_step_duration_ms`、`db_pool_active/idle/pending`（tag `conn_key`）

## 与设计文档的实现差异说明

| # | 差异 | 原因 |
|---|---|---|
| 1 | DDL 全部由 MySQL 方言改为 PostgreSQL（`IDENTITY` / `TIMESTAMP` / `JSONB` / `TEXT` / `COMMENT ON`），文档 §6.2 的 MySQL DDL 未采用 | 需求明确以 pgsql 为主 |
| 2 | `sql_definition` / `release_step` 用 `conn_key`（而非 `conn_id`）引用连接 | 文档 §7 代码本身按 `connKey` 路由（`registry.get(connKey)`）；conn_key 唯一且稳定 |
| 3 | `release_plan` 增加 `default_conn_key`、`step_config` 两列 | 文档未明确 RL-2/3/4 步骤配置的存储位置；创建计划时录入，启动扫描时合并落库 |
| 4 | `sql_definition_history` 未设 `(sql_def_id, version)` 唯一键（改为普通索引） | 文档方案存在冲突：CREATE 与首次 UPDATE 都会产生 version=1 的快照行 |
| 5 | `retryStep` 成功后计划置 WAITING；此时 `continueNext` 无等待步骤时直接校验下一步执行者并推进 | 串起文档 §7.5.3 中 retry → continue 的状态闭环 |
| 6 | `HealthCheckRun.rowCount` 受 `max_rows` 截断 | 文档 §14 要求 `setMaxRows` + `result_head` 截断；行数断言建议用 `SELECT COUNT(*)` |

## 已知约束（一期）

- **单实例部署**：发布编排基于 JVM 内 `ReentrantLock`，多实例需改造为数据库悲观锁或分布式锁（文档 §15.2）
- 目标库驱动仅内置 PostgreSQL；`release` 目录内的 SQL 应使用 PostgreSQL 方言（SqlSplitter 已支持 dollar-quote 函数体）
- 应用运行中崩溃导致步骤卡在 RUNNING：请使用 rerun 重跑该计划
- 一期不做：SQL 编辑器、多级审批、分布式事务、执行计划可视化、多租户

## 构建

```bash
./mvnw package          # 构建
./mvnw test             # 单元测试（SqlSplitter / SqlGuard / Crypto / 断言 / 目录扫描）
```
