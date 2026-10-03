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

启动后访问 `http://localhost:8080`（Web 管理界面）、`http://localhost:8080/api/**`（REST API）、`http://localhost:8080/actuator/prometheus`（指标）。

## Web 管理界面

基于 **jQuery 3.7.1 + Bootstrap 5.2.3**（已内置于 `static/lib/`，离线可用），启动即用，无需单独部署前端：

| 页面 | 功能 |
|---|---|
| `/`（总览） | 连接 / 健康检查 / 发布计划数量卡片、最近发布计划、平台健康状态 |
| `/connections.html` | 连接 CRUD（密码留空=不变、connKey 不可改）、连通性测试、连接池重载、启停（热更新） |
| `/health-checks.html` | 定义 CRUD（断言配置模板一键填入、客户端 JSON 校验）、手动执行并查看结果、执行记录分页、修改历史（版本快照）、启停调度 |
| `/releases.html` | 计划列表与详情、创建（步骤编排配置 + 目录扫描预览）、凭 CR+备注启动、WAIT 人工继续、失败单步重试、全流程重跑、SQL 明细日志（按步骤过滤）、UAT 运行摘要、**SSE 实时刷新步骤/计划状态** |

右上角「操作者」输入框对应请求头 `X-Operator`（localStorage 持久化）：发布步骤指定了执行者时，启动/继续/重试会以该身份校验。

## 国际化（i18n）

界面与后端错误消息支持 **English（默认）/ 简体中文**，导航栏右侧下拉切换：

- 前端：集中式字典 `static/js/i18n.js`（`I18N.t(key, params)`），页面元素通过 `data-i18n` / `data-i18n-placeholder` / `data-i18n-title` 属性渲染；语言偏好存 localStorage，默认英文。
- 后端：标准 Spring `MessageSource`（`messages.properties` 英文默认 + `messages_zh.properties` 中文），所有 `BizException` 支持 messageKey + args，由 `GlobalExceptionHandler` 按当前 locale 渲染。
- 语言选择同步机制：前端切换时写入 `LANG` Cookie；后端由 `CookieLocaleResolver`（默认 `Locale.ENGLISH`）+ `LocaleChangeInterceptor`（支持 `?lang=zh`）解析。
- 新增文案：前端在 i18n.js 两个字典中补同名 key；后端在两份 messages 文件中新增 `error.*` 键，抛出时使用 `BizException.i18n(ErrorCode.X, "error.xxx", args...)`。

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

规则：一级子目录名必须为纯数字且 ≥1（`0`、`007` 等忽略）；非数字目录、空目录（无 `.sql`）忽略；不递归子目录；整个计划目录不存在 → 计划标记 `SKIPPED`；步骤执行前目录被删 → 该步骤 `SKIPPED` 继续。

### 执行模型与高级特性

- **异步驱动**：start/continue/retry/rerun 的 HTTP 请求仅完成校验与元数据写入后立即返回，实际执行在 `release-driver` 线程池进行，进度经 SSE 推送（`sql-pipeline.release.driver-pool-size` / `driver-queue-capacity`）。
- **plan 级互斥（PG advisory lock）**：同一计划的驱动操作串行化，应用崩溃锁自动释放，天然支持多实例部署（持锁期间占用一个平台库连接）。
- **步骤事务**：一个目录 = 一个事务，失败整目录回滚。若目录内放置**空的 `_nontransactional` 标记文件**，该步骤逐条自动提交、失败不回滚——用于 `CREATE INDEX CONCURRENTLY` 等 PostgreSQL 非事务 DDL（事务模式下出现 CONCURRENTLY 会直接拒绝并提示）。
- **步骤总超时**：`sql-pipeline.release.step-total-timeout-sec`（默认 3600，0 = 不限制），语句间检查，超时回滚置 FAIL。
- **脚本变更检测**：start 时记录各步骤脚本内容 SHA-256，详情页对当前目录实时对比，变更显示「脚本已变更 ⚠」。
- **确认与重试审计**：WAIT 步骤 continue 时记录确认人/时间（confirm_by/confirm_at）；retry 支持可选备注（retry_remark）。
- **日志轮次**：SQL 明细日志带 run_seq 与 operator，可按轮次过滤查看；失败与成功的运行均生成运行摘要。
- **成功事件**：通道可订阅 `RELEASE_STEP_SUCCESS` / `PLAN_COMPLETED`（默认只订阅失败事件），与失败事件共用 xMatters 推送链路。

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
# SQL 中的参数用 ${name} 占位，paramsJson 为 JSON 对象（key 对应占位符名）
# 断言 = 断言类型 + 操作符 + 期望值：VALUE（第一行第一列）/ ROWS（返回行数）
curl -s -X POST localhost:8080/api/health-checks -H 'Content-Type: application/json' -H 'X-Operator: admin' -d '{
  "name": "订单积压检查", "connKey": "bizdb",
  "sqlText": "SELECT count(*) AS pending FROM t_order WHERE created_at >= ${date} AND status = ${status}",
  "paramsJson": "{\"date\":\"2026-10-01\",\"status\":1}",
  "assertType": "VALUE", "assertOp": ">=", "assertValue": 0,
  "cronExpr": "0 */5 * * * *", "timeoutSec": 10
}'

# 手动执行：params 可按名覆盖默认参数（未覆盖的用默认值）
curl -s -X POST localhost:8080/api/health-checks/1/run \
  -H 'Content-Type: application/json' -d '{"params":{"date":"2026-10-02"}}'

# 尝试提交写操作会被拦截（SG0002）
curl -s -X POST localhost:8080/api/health-checks -H 'Content-Type: application/json' -d '{
  "name": "bad", "connKey": "bizdb", "sqlText": "DELETE FROM release_demo_audit"
}'

curl -s 'localhost:8080/api/health-checks/1/runs?page=1&size=10'  # 执行记录
curl -s localhost:8080/api/health-checks/1/history        # 修改记录（版本快照）
```

断言配置（简化模型，三要素）：

| 字段 | 取值 | 说明 |
|---|---|---|
| `assertType` | `VALUE` / `ROWS` | 返回值（第一行第一列）/ 返回行数；空 = 不断言 |
| `assertOp` | `== != > >= < <=` | 操作符 |
| `assertValue` | 数值 | 期望值，如 `0`、`100`、`1.5` |

例：`{"assertType":"ROWS","assertOp":"==","assertValue":0}` 即"行数必须为 0"；`{"assertType":"VALUE","assertOp":">=","assertValue":1}` 即"第一行第一列 ≥ 1"（非数值的实际值判 FAIL）。

参数绑定：SQL 中用 `${name}` 命名占位符（如 `WHERE created_at >= ${date}`），`paramsJson` 为 JSON 对象 `{"date":"2026-10-01","status":1}`（缺省 `{}`）；保存与执行时都会校验每个占位符都有对应值；手动执行可传同名 key 覆盖。

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

## 已知约束

- ~~单实例部署~~：plan 级锁已改为 PG advisory lock，支持多实例（需共享发布目录）；多实例下健康检查连败计数为实例内各自统计
- 目标库驱动仅内置 PostgreSQL；`release` 目录内的 SQL 应使用 PostgreSQL 方言（SqlSplitter 已支持 dollar-quote 函数体）
- 一期不做：SQL 编辑器、多级审批、分布式事务、执行计划可视化、多租户

## 构建

```bash
./mvnw package          # 构建
./mvnw test             # 单元测试（SqlSplitter / SqlGuard / Crypto / 断言 / 目录扫描）
```
