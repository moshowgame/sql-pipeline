# sql-pipeline 优化方向研究报告

| 项 | 内容 |
|---|---|
| 研究对象 | sql-pipeline（健康检查 + 发布编排平台，一期设计见 tech_design.md v1.0） |
| 技术约束 | 全栈 PostgreSQL（平台元数据库 + 目标业务库），不考虑 MySQL / Oracle |
| 研究方法 | 设计文档瓶颈分析 + 业界方案文献调研（P0 官方文档优先，P1/P2 交叉验证） |
| 状态 | Draft v1.0 |

## 1. 执行摘要

设计文档 §14.3 与 §16.2 已自认四类瓶颈：大目录发布耗时、`release_sql_log` 膨胀、SSE 连接容量、单实例 JVM 锁约束。在「全栈 PostgreSQL」的新前提下，多数瓶颈可以借助 PostgreSQL 原生能力低成本化解，无需引入新中间件。研究结论按优先级归纳为八个方向：

1. **利用事务性 DDL 强化发布事务边界**——PostgreSQL 中 DDL 可回滚，「一步一事务」语义比设计文档假设的（DDL 隐式提交场景）更强，可在 COMMIT 前插入断言校验。
2. **服务端强制只读**——用 `default_transaction_read_only` / 只读角色 / `GRANT SELECT` 把设计文档的 L3/L4 层从「驱动侧约定」升级为「数据库侧强制」。
3. **多实例扩展**——用 PG advisory lock（会话级）或 `SKIP LOCKED` 任务认领替代 JVM 内 `ReentrantLock`，解除一期单实例约束，无需 Redis / ZooKeeper。
4. **发布幂等与重跑**——借鉴 Flyway repeatable migration 的 checksum 模式，为 UAT 全流程重跑提供「跳过已成功且未变更文件」的能力。
5. **执行层细节优化**——PG JDBC 流式读取需要 `autoCommit=false` + `setFetchSize`（当前设计 `setAutoCommit(true)` 无法流式）；连接池容量按公式全局预算而非每连接 10。
6. **存储与归档**——`release_sql_log` 用 PG 声明式分区 + pg_partman 自动滚动，替代设计文档中模糊的「按月分区 / 定期归档」。
7. **可观测性**——用 `pg_stat_statements` / `pg_stat_activity` 落地设计文档 §13.3 的「P95 回归告警」；SSE 容量按文件描述符评估。
8. **调度可靠性**——`@Scheduled` / 手工 `TaskScheduler` 无持久化、无多实例互斥；用 ShedLock（轻量）或 JobRunr（带持久化与面板）补齐。

其中方向 1–3 是 PostgreSQL 化带来的「免费红利」，实现成本低、收益确定性高，建议在一期实现中直接吸收；方向 4–8 可按二期路线推进。

## 2. 研究背景与议题提炼

### 2.1 设计文档中的已知瓶颈

tech_design.md 明确列出的瓶颈与约束包括：

| 出处 | 瓶颈 / 约束 | 文档中的临时缓解 |
|---|---|---|
| §14.3 | 单个大目录发布耗时过长 | 「分批提交（需业务确认）」 |
| §14.3 | 健康检查结果集过大 | 强制 `maxRows`，断言用 `COUNT(*)` |
| §14.3 | `release_sql_log` 表膨胀 | 「按月分区 / 定期归档」（未定方案） |
| §14.3 | SSE 连接数过多 | 「改用轮询 + 增量拉取」 |
| §15.2 / ADR-07 | 单实例部署，JVM 内锁 | 「多实例需改数据库悲观锁或分布式锁」 |
| §16.2 | 目标库连接不稳定、元数据库单点 | 快速失败、主从备份 |

### 2.2 PostgreSQL 化引入的新变量

用户约束将平台库与目标库统一为 PostgreSQL。这改变了三个设计前提：

- 原设计多处参考 MySQL 语义（如 DDL 隐式提交、`TINYINT`、`COMMENT` 内联语法），PostgreSQL 下 DDL 事务语义完全不同，且更强。
- PostgreSQL 自带 advisory lock、声明式分区、`pg_stat_statements`、`LISTEN/NOTIFY` 等能力，设计文档为单实例/MySQL 假设准备的若干「暂缓项」可以提前落地。
- PG JDBC 驱动的行为与 MySQL 驱动不同（如流式读取前提条件、`setReadOnly` 的实际效果），部分「兜底」代码需要重新核实。

## 3. 方向一：事务性 DDL 强化发布事务边界（P0）

### 3.1 依据

PostgreSQL 中绝大多数 DDL（`CREATE TABLE`、`ALTER TABLE` 等）都可以包含在 `BEGIN ... COMMIT` 事务内并整体回滚，失败时 schema 回到事务前状态；这与 MySQL 每条 DDL 隐式提交形成本质差异 [cite:1][cite:2]。业界实践明确建议：在 PostgreSQL 上把相关 DDL 包进同一事务，失败即整体回滚、不留半应用状态 [cite:3]。更进一步，PostgreSQL 允许「迁移 + 验证查询 + 由断言决定是否 COMMIT」在同一事务内完成，断言失败则相当于迁移从未发生 [cite:4]。

### 3.2 对 sql-pipeline 的优化点

- **步骤事务语义升级**：设计文档 ADR-05「步骤即事务边界」在 MySQL 假设下只对 DML 可靠（DDL 会隐式提交破坏回滚）；在 PostgreSQL 下，一个步骤目录内的 DDL + DML 全部可回滚，「失败整目录回滚」从理想变为现实。
- **COMMIT 前卡点**：`runStep()` 可在 `conn.commit()` 前执行可选验证 SQL（如检查列/行是否就位），验证失败则 rollback 并将步骤置 FAIL——这是设计文档未覆盖的免费能力。
- **注意事项**：并非所有语句可在事务内执行（如 `CREATE INDEX CONCURRENTLY`、`VACUUM`），扫描器应维护不可事务化语句黑名单，遇到时单独拆步执行或拒绝 [cite:2][cite:3]。
- **实现代价**：仅需在发布执行器中坚持 `setAutoCommit(false)` 并覆盖 DDL 语句类型；无新增组件。

## 4. 方向二：服务端强制只读（P0）

### 4.1 依据

PostgreSQL 提供多层只读强制：`SET TRANSACTION READ ONLY`（事务级）、`SET default_transaction_read_only = on`（会话/数据库级）、`ALTER ROLE ... SET default_transaction_read_only = on`（角色级），命中时写操作直接报错 [cite:8][cite:9][cite:11]。最佳实践是为只读场景建立专用角色，仅 `GRANT SELECT`，并将 `default_transaction_read_only = on` 设为该角色的默认值；写迁移则用独立的、显式声明的迁移角色 [cite:10]。设计文档 §7.2.3 的 L4「目标库只读账号（推荐）」恰好与该模式对应。

### 4.2 对 sql-pipeline 的优化点

- **L3 层从「提示」变「强制」**：设计文档 §7.3 中健康检查路径的 `conn.setReadOnly(true)` 在 MySQL 驱动上主要是客户端提示，在 PG JDBC 上会发出 `SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`，服务端会真正拒绝写语句——L3 层的有效性因换库而实质性增强。
- **L4 层产品化**：连接注册时建议 UI/接口校验凭据对应的是只读角色（可探测 `current_user`、`rolsuper`、`rolbypassrls`、`has_database_privilege` 等），引导管理员使用 `GRANT SELECT` 专用账号 [cite:10]。
- **健康检查执行路径再收紧**：在 `query()` 中使用显式 `BEGIN READ ONLY` 事务（配合方向五的 `autoCommit=false`），即使连接凭据被误配为高权限账号，事务层仍拒绝写入 [cite:9][cite:11]。
- **实现代价**：低。修改 `DynamicSqlExecutor.query()` 与连接注册校验逻辑，无新依赖。

## 5. 方向三：多实例与锁扩展（P1）

### 5.1 依据

设计文档 ADR-07 强制单实例（JVM 内 `ReentrantLock`）。多实例化的三个候选方案：

| 方案 | 机制 | 代价 | 适用 |
|---|---|---|---|
| PG advisory lock | 会话级应用锁，存于服务端锁表，`pg_try_advisory_lock(key)` / `pg_advisory_unlock(key)` | 零新依赖；长任务占用一个会话连接 | 发布编排互斥（任务数少、持锁时间长）[cite:5][cite:6] |
| `SKIP LOCKED` 任务认领 | 步骤入库为待办行，多实例 `FOR UPDATE SKIP LOCKED` 抢占 | 零新依赖；天然形成轻量队列 | 步骤级并行与失败转移 [cite:7] |
| ShedLock / Quartz / JobRunr | 调度框架级互斥 | 新依赖 | 定时健康检查多实例不重复触发 [cite:13][cite:14][cite:15] |

advisory lock 的定位：应用自定义锁、不锁任何数据行、存于数据库内存，适合「已依赖数据库、需要强单一事实来源语义」的场景；其代价是会话绑定——长任务期间占用一个连接，作业数多时应改用锁表 + 短事务 [cite:5][cite:6]。

### 5.2 对 sql-pipeline 的优化点

- **`ReleaseLockRegistry` 改造路径**：一期保持 JVM 锁；二期把 `lockFor(planId)` 的实现换成「advisory lock + 本地锁」两级（本地锁减少数据库往返，advisory lock 保证跨实例互斥）。plan 数量少（§14.1 假设 ≤100/月、并发 ≤5），会话占用可接受 [cite:5]。
- **健康检查调度互斥**：手工 `TaskScheduler` 注册 Cron 的方案在多实例下会重复执行；引入 ShedLock（注解 + 一次 DB 写）是最小改动，JobRunr 则额外提供持久化、重试与监控面板 [cite:13][cite:14][cite:15]。
- **步骤认领（可选）**：若未来需要单计划步骤级并行（不同步骤打到不同目标库），`SKIP LOCKED` 是无中间件的自然延伸 [cite:7]。
- **实现代价**：中。核心是锁接口抽象（`PlanLock` 接口隔离 JVM/PG 两种实现），调度侧引入一个依赖。

## 6. 方向四：发布幂等与重跑（P1）

### 6.1 依据

Flyway 的 repeatable migration 模式：同一文件以 `R__` 前缀维护单一最新版本，checksum 变化时自动重跑 [cite:20]；checksum 机制同时用于检测数据库漂移（文件被改动而库未跟上）[cite:21]。

### 6.2 对 sql-pipeline 的优化点

设计文档 RL-9 的「全流程重跑（UAT 统计执行时间）」目前语义是「清库重来」，大计划重跑代价高且对不可重复执行的 SQL（如 `INSERT`）不安全。优化：

- **扫描期计算 checksum**：`ReleaseScanner` 对每个 `.sql` 文件计算 SHA-256，连同步骤落库。
- **重跑策略分级**：`rerunAll` 提供两种模式——`FULL`（现状，清库重来）与 `SMART`（跳过「上次执行 SUCCESS 且本次 checksum 未变」的文件，仅执行新增/变更/上次失败的文件）。这与 Flyway checksum 语义一致 [cite:20][cite:21]。
- **漂移告警**：重跑前比对 checksum，发现「已成功但文件已变更」时在计划详情中标记 `DRIFT`，提示操作者该文件将被重新执行。
- **实现代价**：低到中。`release_sql_log` 增加 `file_hash` 列，重跑逻辑增加比对分支。

## 7. 方向五：执行层优化（P0，含两处必须修复的设计缺陷）

### 7.1 结果集流式读取（缺陷修复级）

PG JDBC 官方文档明确：`setFetchSize > 0` 的游标模式生效前提是 **`autoCommit=false`**；`autoCommit=true` 时驱动会一次性取回并缓存全部结果行 [cite:25][cite:26]。设计文档 §7.3 的 `query()` 调用了 `conn.setAutoCommit(true)`，意味着 `maxRows` 只能靠驱动侧截断，服务端仍会产出全量结果、网络仍会传输（直到 `maxRows` 行为止的窗口取决于驱动实现），大结果集场景存在内存与带宽浪费。

优化：健康检查查询路径改为 `autoCommit=false` + `setFetchSize(min(500, maxRows))`，读完显式 `rollback()`（只读事务无需提交，且与方向二呼应）；结果集聚合进 `QueryResult` 的逻辑不变。`maxRows` 保留为最终护栏 [cite:25][cite:26]。

### 7.2 连接池容量（配置级）

设计文档 §14.2 假设「poolSize=10，50 个并发任务 → 需 5×10=50 连接」。HikariCP 官方池容量公式为 `connections = (core_count × 2) + effective_spindle_count`（SSD/NVMe 下 spindle 记 1），且强调「小池反而更快」、该预算是**整个数据库**的上限而非每应用实例的配额，应结合 Little's Law 按目标查询时长折算 [cite:16][cite:17][cite:18][cite:19]。

优化：

- 连接定义增加「同库多连接去重预算」校验：同一 `jdbc_url` 上所有池的 `maximumPoolSize` 之和 ≤ 公式值 × 实例数折扣。
- 健康检查默认池大小建议下调（典型 4 核目标库给 8–10 足够），用 `pg_stat_activity` 实测并发峰值后再调 [cite:19]。
- 多实例或池总和超预算时引入 PgBouncer（事务模式）作为前置，避免 `max_connections` 打爆。

### 7.3 明细日志批量写入（性能级）

`runStep()` 目前逐条 `INSERT release_sql_log`。一个 100 文件 × 平均 10 语句的步骤将产生 1000 次独立往返。优化：日志行先聚合在内存，事务边界处一次 JDBC batch 写入（与步骤同事务，回滚时日志语义需单独评估——可改为「步骤失败时仍以独立短事务落日志」以保留排障现场）。

## 8. 方向六：存储与归档（P1）

### 8.1 依据

PostgreSQL 原生声明式分区：建表时 `PARTITION BY RANGE (key)`，按范围创建子分区，查询按分区键裁剪 [cite:11]。pg_partman 提供分区自动创建、保留期管理与「过期分区归档到另一 schema / DETACH 而非直接删除」的自动化 [cite:12]。

### 8.2 对 sql-pipeline 的优化点

- `release_sql_log` 改为 `PARTITION BY RANGE (executed_at)` 按月分区，主键调整为 `(executed_at, id)`（分区键必须包含在主键/唯一约束内）[cite:11][cite:12]。
- 引入 pg_partman（或平台内置的轻量分区维护 Job）自动预建下月分区、按保留期（如 6 个月）`DETACH PARTITION` 后归档，落实设计文档 §14.3 的「按月分区 / 定期归档」[cite:12]。
- `health_check_run.result_head` 与 `release_step` 的耗时明细可直接用 `JSONB`（文档 DDL 已用 JSON 类型），配合 GIN 索引支持按结果内容检索。
- `db_connection.password_enc` 之外，建议连接串凭据同步依赖 PG 的 `SCRAM-SHA-256`（PG 10+ 默认）而非 `MD5`。

## 9. 方向七：可观测性（P1）

### 9.1 依据

`pg_stat_statements` 是查询监控的基础设施：需 `shared_preload_libraries` 预加载并 `CREATE EXTENSION`，提供每类语句的调用次数、总耗时、均值耗时、标准差与 IO 统计，可用于建立基线并按 `total_exec_time`（容量瓶颈）与 `mean_exec_time`（离群值）两个维度排序 [cite:27][cite:28][cite:29]；排障时 `pg_stat_activity` 是查看当前运行语句、等待事件与锁的第一站 [cite:29]。

### 9.2 对 sql-pipeline 的优化点

- **P95 告警落地**：设计文档 §13.3「步骤耗时超过历史 P95 的 2 倍」可直接基于 `health_check_run` / `release_step` 的历史耗时计算（平台自身数据），同时用 `pg_stat_statements` 的 `mean_exec_time` 与 `stddev_exec_time` 交叉判断「持续变慢」还是「偶发抖动」[cite:27][cite:28]。
- **健康检查超时升级**：JDBC `setQueryTimeout` 之外，对卡死的后端提供管理面手段（记录 `pg_stat_activity.pid`，超时后可 `pg_cancel_backend`），避免连接池被慢查询占满。
- **SSE 容量修正**：文献对「SSE 是否占线程」存在分歧——一方认为 MVC `SseEmitter` 每连接占用一个异步 servlet 线程，数千级连接应选 WebFlux [cite:22]；另一方实测指出异步模式下请求线程会归还线程池，真正约束是文件描述符（`ulimit -n` 默认可能低至 1024）与异步上下文 [cite:23][cite:24]。两者在「FD 是硬上限、需调大 ulimit 与容器限制」上结论一致。对 sql-pipeline 而言，观看发布的并发查看者数量极小（≤10 人），一期 SSE 可保留，但应加心跳与超时回收 [cite:24]；若将来多实例部署，SSE 需粘性会话或以 PG `LISTEN/NOTIFY` 作为跨实例事件总线。
- **指标对齐**：设计文档 §13.2 的 `db_pool_*` Gauge 可直接取 HikariCP 内置 Micrometer 绑定，无需自研。

## 10. 方向八：调度可靠性（P1）

### 10.1 依据

Spring `@Scheduled` 简单但不持久化作业、不处理多实例 [cite:13]。社区对比：ShedLock 以注解方式为定时任务加分布式锁（DB/Redis/ZooKeeper 后端），每次触发一次 DB 写，定位是「防重复执行」而非通用锁 [cite:14][cite:15]；Quartz 提供 DB 集群但配置重；JobRunr 是较新的选择，lambda 定义作业、持久化、自动重试、自带 Dashboard [cite:13][cite:14]。

### 10.2 对 sql-pipeline 的优化点

- 一期手工 `TaskScheduler` + `ScheduledFuture` 注册表方案继续可用（单实例），但需补两点：重启后从 `sql_definition` 重建全部调度（当前设计已有 init，需确保与 DB 为准而非内存）；调度触发的执行记录带 `trigger_type=CRON` 且加「同 defId 进行中则跳过」防重入（依赖 DB 状态查询，天然多实例安全）。
- 二期若多实例：优先 ShedLock 包一层（改动最小）；若需要「检查任务历史可视化、失败自动重试、管理面板」，直接评估 JobRunr [cite:13][cite:14][cite:15]。

## 11. 优先级路线图

| 优先级 | 方向 | 关键动作 | 对应文档章节 | 依赖 |
|---|---|---|---|---|
| P0（实现期直接吸收） | 事务性 DDL | 步骤事务覆盖 DDL；COMMIT 前验证卡点；不可事务化语句黑名单 | §7.5.4、ADR-05 | 无 |
| P0（缺陷修复） | 流式读取 | `query()` 改 `autoCommit=false` + `setFetchSize` + rollback | §7.3 | 无 |
| P0 | 服务端只读 | 只读角色 + `GRANT SELECT`；`BEGIN READ ONLY`；注册时凭据探测 | §7.2.3、§12.2 | 无 |
| P0 | 池容量 | 全库预算校验、默认值下调、PgBouncer 预案 | §10.2、§14.2 | 无 |
| P1（一期收尾/二期） | 存储分区 | `release_sql_log` 按月分区 + pg_partman | §14.3 | 元库迁移 |
| P1 | 幂等重跑 | checksum + SMART/FULL 双模式 + DRIFT 标记 | RL-9 | 日志表加列 |
| P1 | 多实例锁 | `PlanLock` 抽象 + advisory lock 实现；ShedLock/JobRunr 评估 | ADR-07、§10.3 | 锁接口抽象 |
| P1 | 可观测性 | pg_stat_statements 接入、P95 告警、SSE 心跳与 FD 评估 | §13 | 运维配合 |
| P2 | 步骤级并行 | `SKIP LOCKED` 认领模式 | §10.3 | 多实例先行 |

## 12. 结论

全栈 PostgreSQL 是对本项目的一次「约束红利」：设计文档为异构库/MySQL 假设保留的保守设计（单实例锁、日志归档悬而未决、只读兜底偏弱、DDL 回滚不确定）大多可以用 PG 原生能力一次性收编——事务性 DDL、角色级只读、advisory lock、声明式分区、`pg_stat_statements`。建议在实现一期时直接吸收 P0 四项（均为低实现代价，其中流式读取属缺陷修复），将分区、幂等重跑、多实例锁列入二期，并保留 `PlanLock`、分区维护、调度框架三个可替换抽象点，避免二期改造伤筋动骨。

## Sources

- [cite:1] The Schema Change Blueprint — PostgreSQL SME Cookbook — https://postgresql.codeguides.io/migrations-schema-change/schema-change-blueprint/
- [cite:2] Transactional DDL: PostgreSQL vs MySQL Migrations — monpg.app — https://monpg.app/blog/ddl-transactions-mysql-vs-postgresql
- [cite:3] Database Design: Schema Evolution & Migrations — Andrew's Notebook — https://andrewaltimit.github.io/Documentation/docs/technology/database-design/schema-evolution-and-migrations.html
- [cite:4] Test PostgreSQL migrations before COMMIT — pgmi — https://vvka-141.github.io/pgmi/articles/test-postgresql-migrations-before-commit/
- [cite:5] Methods to Prevent Duplicate Cron Execution Across Multiple Spring Boot Instances — tuanhnet — https://tuanhnet.hashnode.dev/methods-to-prevent-duplicate-cron-execution-across-multiple-spring-boot-instances
- [cite:6] Eliminating Race Conditions in Clustered Spring Boot with PostgreSQL Advisory Locks — DEV Community — https://dev.to/shreyash_kakde/eliminating-race-conditions-in-clustered-spring-boot-with-postgresql-advisory-locks-43k2
- [cite:7] Lightweight Distributed Locks with PostgreSQL: Skip Locked in Action — wearecommunity.io — https://wearecommunity.io/communities/mjc/articles/7438
- [cite:8] PostgreSQL mailing list — How to configure a read-only database server — https://www.postgresql.org/message-id/BANLkTi=kTOyocpgEUTuZXRHvyg=Y506mHA@mail.gmail.com
- [cite:9] How to fix Read only SQL transaction in PostgreSQL — DevErrors — https://deverrors.com/errors/postgresql-read-only-sql-transaction
- [cite:10] How to Fix PostgreSQL read-only transaction CREATE TABLE error — DEV Community — https://dev.to/abestdev/how-to-fix-postgresql-cannot-execute-create-table-in-a-read-only-transaction-error-3klj
- [cite:11] PostgreSQL 18 Documentation — 5.12 Table Partitioning — https://www.postgresql.org/docs/current/ddl-partitioning.html
- [cite:12] pg_partman automated partition management — Alibaba Cloud PolarDB Docs — https://www.alibabacloud.com/help/en/polardb/polardb-for-postgresql/pg-partman/
- [cite:13] Migrate to JobRunr (from Spring @Scheduled) — jobrunr.io — https://www.jobrunr.io/en/guides/migration/migrate-to-jobrunr/
- [cite:14] Scheduler 注意事项 — ShedLock vs Quartz 对比 — dailyrium — https://dailyrium.com/scheduler-%EC%A3%BC%EC%9D%98%EC%82%AC%EC%9D%B4%ED%95%AD-%EC%99%84%EB%B2%BD-%EC%A0%95%EB%A6%AC-%EC%8B%A4%EB%AC%B4%EC%97%90%EC%84%9C-%EB%B0%98%EB%93%9C%EC%8B%9C-%EC%95%8C%EC%95%84%EC%95%BC-%ED%95%A0/
- [cite:15] Spring Boot 定时任务多实例互斥执行 — CSDN — https://blog.csdn.net/q464042566/article/details/157364215
- [cite:16] Connection Pool Size Calculator — Chat2DB — https://chat2db.ai/tools/connection-pool-size-calculator
- [cite:17] How to Properly Configure and Tune HikariCP Connection Pool — springboot-123 — https://springboot-123.mizucoffee.com/en/blog/spring-boot-hikaricp-connection-pool-tuning-guide/
- [cite:18] Database Connection Pooling Strategies — MojoAuth — https://mojoauth.com/blog/database-connection-pooling-10000-concurrent-logins
- [cite:19] Connection Pool Sizing with Little's Law — Michal Drozd — https://www.michal-drozd.com/en/blog/connection-pool-littles-law/
- [cite:20] Timestamps and Repeatable Migrations — Flyway (Redgate) — https://flywaydb.org/blog/flyway-timestampsandrepeatables
- [cite:21] Flyway Database Drift and How it Happens — Redgate — https://www.red-gate.com/hub/product-learning/flyway/flyway-database-drift-and-how-it-happens/
- [cite:22] How to Implement SSE in Spring Boot — springboot-123 — https://springboot-123.mizucoffee.com/en/blog/spring-boot-server-sent-events-sse-guide/
- [cite:23] SSE vs WebSocket STOMP on Spring Boot 4: A Measured Guide — ankurmm — https://ankurm.com/spring-boot-4-sse-websocket-stomp/
- [cite:24] HTTP That Never Ends: Server-Sent Events in Production — buildwithmostafa — https://buildwithmostafa.hashnode.dev/server-sent-events-in-production
- [cite:25] Issuing a Query and Processing the Result — pgJDBC 官方文档 — https://jdbc.postgresql.org/documentation/query/
- [cite:26] PolarDB PostgreSQL JDBC 使用（FetchSize 与 OOM） — 阿里云 — https://help.aliyun.com/zh/polardb/polardb-for-oracle/jdbc-o-1-0
- [cite:27] Using pg_stat_statements for Query profiling and tuning — Valerie Parham-Thompson — https://valerieparhamthompson.com/posts/using-pg-stat-statements-for-profiling-tuning/
- [cite:28] PostgreSQL Monitoring Guide — Web-alert — https://web-alert.io/blog/postgresql-monitoring-production-slow-queries-replication-guide
- [cite:29] PostgreSQL monitoring reference — PlanetScale database-skills — https://raw.githubusercontent.com/planetscale/database-skills/main/skills/postgres/references/monitoring.md
