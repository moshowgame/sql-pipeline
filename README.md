# sql-pipeline

SQL 健康检查与发布编排平台：只读守卫（SqlGuard）、定时健康检查、Release Plan/Runbook（按目录顺序的发布编排 + Pipeline 可视化）、WAIT/CONTINUE 人工卡点、xMatters 告警通知。

> 按技术设计文档 `tech_design.md`（v1.1，与实现同步）实现。**平台元数据库与目标业务库均以 PostgreSQL 为主**（一期不考虑 Oracle / MySQL）。

## AUTHOR
Powered by [Moshow郑锴](https://zhengkai.blog.csdn.net/)

## 功能总览

| 模块 | 能力 |
|---|---|
| 数据源管理 | 平台内定义 PostgreSQL 连接（可指定默认 Schema，支持 share folder 目标库），HikariCP 连接池运行期动态注册 / 热更新 / 连通性测试；密码 AES-256-GCM 加密存储，接口仅返回掩码 |
| 健康检查 | 只读 SQL（JSqlParser AST 级 SELECT-only 校验，保存与执行双重拦截 + `setReadOnly` 兜底）、`${name}` 命名参数占位符、简化断言（VALUE/ROWS + 操作符 + 期望值）、Spring Cron 动态调度、修改记录（版本快照）与执行记录 |
| Release Plan/Runbook | Release Type（FOLDER / ZIP 占位）+ Release Path（可指定 share folder）、SCAN 自动生成可重编号的步骤、Pipeline 可视化（横向/纵向）、DRAFT 可编辑 / 运行后只读、CR Number + Remark 启动、异步驱动、WAIT/CONTINUE 人工卡点、单步重试（含备注审计）、全流程重跑（UAT 计时摘要）、SSE 进度推送 |
| 告警通知 | xMatters API 通道（NONE/BASIC/API_KEY 认证）、订阅 HC_FAIL / RELEASE_STEP_FAIL / PLAN_FAIL / RELEASE_STEP_SUCCESS / PLAN_COMPLETED、健康检查连败阈值、推送日志（notify_log） |
| 国际化 | 界面与错误消息 English（默认）/ 简体中文，导航栏一键切换 |

## SCREENCAP
<image src="./screencap/index.png">
<image src="./screencap/connections.png">
<image src="./screencap/health_check_result.png">
<image src="./screencap/health_check_edit.png">
<image src="./screencap/release_plan.png">

## 模块结构（对齐设计文档 §17.2）

```
sql-pipeline-parent
├── sql-pipeline-common        # 错误码、异常（i18n messageKey）、统一响应体、JSON 工具
├── sql-pipeline-datasource    # DataSourceRegistry、SqlGuard、SqlSplitter、DynamicSqlExecutor、SqlParams、CryptoService
├── sql-pipeline-notify        # 告警通知：AlertService、XmattersSender、通知通道与推送日志
├── sql-pipeline-health        # 健康检查：AssertEvaluator、HealthCheckScheduler、HealthCheckService
├── sql-pipeline-release       # 发布：ReleaseScanner、ReleaseOrchestrator（状态机+advisory lock）、ReleaseEventPublisher
├── sql-pipeline-web           # Controller、DTO、全局异常（locale 渲染）、LocaleConfig、TraceId
└── sql-pipeline-boot          # 启动类、application.yml、messages*.properties、PostgreSQL 初始化脚本
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
| `/connections.html` | 连接 CRUD（密码留空=不变、connKey 不可改、可配置默认 Schema）、连通性测试、连接池重载、启停（热更新） |
| `/health-checks.html` | 定义 CRUD（`${name}` 命名参数、简化断言三要素）、手动执行（参数覆盖）、执行记录分页、修改历史（版本快照）、启停调度 |
| `/releases.html` | Release Plan/Runbook：计划列表（View / Edit 按钮，Edit 仅 DRAFT）、创建（类型/路径/默认连接 + SCAN 生成可重编号步骤）、**Pipeline 可视化（横向/纵向切换、分段进度条、人工卡点标记、节点点击查看运行结果与 SQL 明细）**、启动（CR+备注）、WAIT 人工继续（记录确认人）、失败单步重试（含备注）、全流程重跑、SQL 明细日志（按步骤/轮次过滤）、UAT 运行摘要、SSE 实时刷新 |
| `/notify.html` | 告警通道 CRUD（xMatters URL、认证方式、订阅事件、连败阈值）、通道连通性测试、推送日志 |

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
| `spring.datasource.*` | localhost:5432/postgres | 平台元数据库（PostgreSQL） |
| `spring.datasource.hikari.schema` | `${DB_SCHEMA:public}` | 平台库默认 Schema（连接建立时自动 `CREATE SCHEMA IF NOT EXISTS`） |
| `SQL_PIPELINE_AES_KEY` | 无（必填，缺失启动失败） | 连接密码与告警凭据加密密钥 |
| `sql-pipeline.release.base-path` | `./data/releases` | 发布目录根路径；Release Path 相对路径基于它解析 |
| `sql-pipeline.release.sql-suffix` | `.sql` | 步骤目录内纳入执行的文件后缀 |
| `sql-pipeline.release.max-steps` | `99` | 单计划最大步骤数 |
| `sql-pipeline.release.statement-timeout-sec` | `60` | 发布单条 SQL 超时 |
| `sql-pipeline.release.step-total-timeout-sec` | `3600` | 单步骤总超时（0 = 不限制） |
| `sql-pipeline.release.driver-pool-size` | `2` | 异步驱动线程池大小 |
| `sql-pipeline.release.driver-queue-capacity` | `200` | 驱动队列容量（满时退化为调用线程执行） |
| `sql-pipeline.health.scheduler.pool-size` | `8` | 健康检查调度线程池（Boot 默认 1，必须显式设置） |
| `sql-pipeline.health.default-timeout-sec` | `30` | 健康检查 SQL 默认超时 |
| `sql-pipeline.health.default-max-rows` | `1000` | 默认最大返回行数（取连接配置 max_rows 优先） |
| `sql-pipeline.notify.enabled` | `true` | 告警总开关（通道自身 enabled 独立生效） |
| `sql-pipeline.notify.timeout-ms` | `10000` | 告警推送 HTTP 超时 |
| `sql-pipeline.notify.pool-size` / `queue-capacity` | `2` / `1000` | 推送线程池（满时调用线程执行，不丢事件） |

## Release Plan / Runbook

**创建计划**（弹窗）：plan_name 默认自动填 `release_yyyyMMdd`（当天）；Release Type 选 `FOLDER`（目录，推荐）或 `ZIP`（解压暂未实现，仅占位）；**Release Path 必填**，通常是运行本应用主机可访问的 share folder 绝对路径（如 `/mnt/share/release_20261004`），相对路径则基于全局 base-path 解析。

**SCAN**：点击后自动读取 Release Path 下的数字子目录（asc），每个子目录生成一个可编辑的步骤行——步骤号默认等于目录名数字，**可直接修改以调整执行顺序**；摘要表仅统计各目录的 SQL 文件数（大目录友好）。

**编辑权限**：计划列表每行有 View / Edit 按钮——**仅 DRAFT 状态可编辑**（Release Type/Path/默认连接/步骤配置；planName 创建后锁定），一旦启动过（RUNNING/WAITING/COMPLETED/FAILED/SKIPPED）只能 View（全只读弹窗）。

**Pipeline 可视化**：详情页步骤渲染为事件节点流（横向/纵向切换、分段进度条、`⏸ Manual gate` 人工卡点标记、状态色图例），点击节点查看该步骤的运行结果与 SQL 明细（前 100 行）；RUNNING 节点呼吸动画，SSE 驱动实时刷新。

### 发布目录约定

```
<mount>/share_folder/release_20261004/   # Release Path 指向的发布目录
├── 1/                            # 一个数字目录 = 一个步骤 = 一个事务
│   ├── 01_create_table.sql       # 目录内按文件名字典序执行（建议 01_、02_ 前缀）
│   └── 02_init_data.sql
├── 2/10_insert.sql
└── 5/01_verify.sql               # 目录 3、4 缺号：自动忽略，5 正常执行
```

规则：一级子目录名必须为纯数字且 ≥1（`0`、`007` 等忽略）；非数字目录、空目录（无 `.sql`）忽略；不递归子目录；Release Path 不存在或无有效步骤 → 计划标记 `SKIPPED`；步骤执行前目录被删 → 该步骤 `SKIPPED` 继续。

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
# 1) SCAN：扫描 Release Path 下的数字子目录（asc），结果作为 release step（不落库）
curl -s -X POST localhost:8080/api/releases/scan -H 'Content-Type: application/json' -d '{
  "releaseType": "FOLDER",
  "releasePath": "/mnt/share/release_20261004",
  "defaultConnKey": "bizdb"
}'

# 2) 创建计划（DRAFT）：releasePath 必填；steps 的 dirName↔stepNo 即"目录→运行编号"映射（可重编）
curl -s -X POST localhost:8080/api/releases -H 'Content-Type: application/json' -H 'X-Operator: admin' -d '{
  "planName": "release_20261004", "releaseType": "FOLDER",
  "releasePath": "/mnt/share/release_20261004", "defaultConnKey": "bizdb",
  "steps": [{"dirName":"2","stepNo":1},{"dirName":"1","stepNo":2,"afterMode":"WAIT","executor":"zhangsan"}]
}'

# 2b) 编辑计划（仅 DRAFT 可编辑；planName 不可改；运行过后返回 409）
curl -s -X PUT localhost:8080/api/releases/1 -H 'Content-Type: application/json' -H 'X-Operator: admin' -d '{
  "releaseType": "FOLDER", "releasePath": "/mnt/share/release_20261004",
  "defaultConnKey": "bizdb",
  "steps": [{"dirName":"1","stepNo":1},{"dirName":"2","stepNo":2,"afterMode":"WAIT"}]
}'

# 3) 执行者凭 CR + Remark 启动（异步驱动：HTTP 立即返回，CONTINUE 步骤连跑，到 WAIT 步骤停下）
curl -s -X POST localhost:8080/api/releases/1/start -H 'Content-Type: application/json' -H 'X-Operator: zhangsan' \
  -d '{"crNumber":"CR-20261004-001","remark":"10月迭代 UAT 发布"}'

# 4) WAIT 步骤人工确认后推进（记录确认人 confirm_by）
curl -s -X POST localhost:8080/api/releases/1/continue -H 'X-Operator: zhangsan'

# 失败处理
curl -s -X POST localhost:8080/api/releases/1/steps/3/retry -H 'X-Operator: zhangsan' \
  -H 'Content-Type: application/json' -d '{"remark":"fixed table name"}'  # 单步重试（含备注审计，不触发 continue 规则）
curl -s -X POST localhost:8080/api/releases/1/rerun -H 'X-Operator: admin'             # 全流程重跑（UAT 计时）

# 查询
curl -s localhost:8080/api/releases/1                 # 计划详情（含步骤状态/耗时/确认人/脚本变更标记）
curl -s 'localhost:8080/api/releases/1/logs?stepNo=2&runSeq=1' # SQL 明细日志（按步骤/轮次过滤）
curl -s localhost:8080/api/releases/1/summaries       # 每次运行摘要（UAT 统计）
curl -N localhost:8080/api/releases/1/stream          # SSE 实时进度（step / plan 事件）
```

### 告警通知 `/api/notify`

```bash
# 创建 xMatters 通道：订阅事件 + 健康检查连败阈值（1 = 每次失败即告警）
curl -s -X POST localhost:8080/api/notify/channels -H 'Content-Type: application/json' -H 'X-Operator: admin' -d '{
  "name": "xmatters-prod", "type": "XMATTERS",
  "url": "https://yourco.xmatters.com/api/integration/1/functions/{id}/trigger",
  "authType": "API_KEY", "authHeaderName": "apikey",
  "secret": "<your-api-key>",
  "events": ["HC_FAIL","RELEASE_STEP_FAIL","PLAN_FAIL","RELEASE_STEP_SUCCESS","PLAN_COMPLETED"],
  "hcFailThreshold": 2, "enabled": 1
}'

curl -s -X POST localhost:8080/api/notify/channels/1/test  # 同步发送 TEST 事件（返回推送结果）
curl -s 'localhost:8080/api/notify/logs?limit=50'          # 推送日志（新→旧）
```

可订阅事件：`HC_FAIL`（健康检查失败，连败达到阈值触发一次，成功清零计数）、`RELEASE_STEP_FAIL`、`PLAN_FAIL`、`RELEASE_STEP_SUCCESS`、`PLAN_COMPLETED`。payload 为 JSON（source/event/severity/title/message/timestamp + 业务字段），xMatters Flow 按需取用。

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
| 3 | `release_plan` 增加 `default_conn_key`、`step_config`（dirName↔stepNo 映射）、`release_type`、`release_path` 列 | 承载 SCAN 生成 + 可重编号步骤与 FOLDER/ZIP 类型、share folder 路径 |
| 4 | `sql_definition_history` 未设 `(sql_def_id, version)` 唯一键（改为普通索引） | 文档方案存在冲突：CREATE 与首次 UPDATE 都会产生 version=1 的快照行 |
| 5 | `retryStep` 成功后计划置 WAITING；此时 `continueNext` 无等待步骤时直接校验下一步执行者并推进 | 串起文档 §7.5.3 中 retry → continue 的状态闭环 |
| 6 | `HealthCheckRun.rowCount` 受 `max_rows` 截断 | 文档 §14 要求 `setMaxRows` + `result_head` 截断；行数断言建议用 `SELECT COUNT(*)` |
| 7 | 断言模型从文档 §7.4.1 的三类（VALUE/ROWCOUNT/RECORD + JSON 配置）简化为三要素（VALUE/ROWS + 操作符 + 期望值） | 需求方简化；新列 `assert_op`/`assert_value`（`assert_config` 保留兼容） |
| 8 | 健康检查参数从文档 §7.3 的 `?` 位置绑定改为 `${name}` 命名占位符 + JSON 对象参数 | 需求方指定；SqlGuard 校验前先做占位符解析 |
| 9 | 文档 §7.5.5 的 JVM `ReentrantLock` 改为 PG advisory lock | 崩溃自动释放锁 + 支持多实例部署 |
| 10 | `release_step` 增加 `confirm_by/at`、`retry_remark`、`script_hash`；`release_sql_log` 增加 `step_no`、`run_seq`、`operator` | 人工确认/重试审计、脚本变更检测、多轮日志区分 |
| 11 | 新增文档未覆盖的 `notify_channel` / `notify_log` 表与 xMatters 告警模块 | 需求方新增的告警通知能力（含成功事件） |
| 12 | 新增文档未覆盖的 i18n（English 默认 / 简体中文，前后端全量） | 需求方新增 |

## 已知约束

- ~~单实例部署~~：plan 级锁已改为 PG advisory lock，支持多实例（需共享发布目录）；多实例下健康检查连败计数为实例内各自统计
- 目标库驱动仅内置 PostgreSQL；`release` 目录内的 SQL 应使用 PostgreSQL 方言（SqlSplitter 已支持 dollar-quote 函数体）
- ZIP 类型已占位但解压功能暂未实现（start/scan 会明确提示），当前请使用 FOLDER 类型
- 应用崩溃恢复会把 RUNNING 步骤置 FAIL 并提示人工核对——崩溃可能发生在目标库 commit 之后，重试前请核对是否已提交
- 一期不做：SQL 编辑器、多级审批、分布式事务、执行计划可视化、多租户

## 构建

```bash
./mvnw package          # 构建
./mvnw test             # 单元测试（SqlSplitter / SqlGuard / Crypto / 断言 / 目录扫描）
```
