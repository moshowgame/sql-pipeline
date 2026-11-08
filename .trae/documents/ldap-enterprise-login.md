# 新增企业认证：/login 可选 LDAP / AD 目录认证（独立扩展模块 sql-pipeline-ldap）

## Context

当前登录体系（`sql-pipeline-web/com.sqlpipeline.web.auth` 包）由 `UserStore`（platform_user 表）+ `PasswordEncryptor`（BCrypt）+ `TokenManager`（HMAC）组合而成，`UserStore` 接口注释已预留外部认证扩展点。用户要求为 `/api/auth/login` 新增**可选的 LDAP/AD 企业目录认证**，并明确要求**实现为独立扩展模块 `sql-pipeline-ldap`**，以扩展能力形式提供。

已确认的产品决策：
- **角色分配**：LDAP 首次登录自动开通，默认 VIEWER；可通过 `admin-groups`（AD 组 DN 列表）在开通时映射为 ADMIN
- **实现方式**：JDK 原生 JNDI，零新增运行时依赖
- **模块化**：LDAP 逻辑全部收在新模块 `sql-pipeline-ldap`，web 模块只新增一个 SPI 接口；`ldap.enabled=false`（默认）时行为完全不变
- **兼容性**：LDAP 开启后本地 platform_user 账号仍可用（LDAP 优先，回退本地密码校验）

## 模块装配机制（已核实）

[SqlPipelineApplication.java](file:///d:/workspace/java/sql-pipeline/sql-pipeline-boot/src/main/java/com/sqlpipeline/boot/SqlPipelineApplication.java) 使用 `@SpringBootApplication(scanBasePackages = "com.sqlpipeline")` + `@ConfigurationPropertiesScan("com.sqlpipeline")`——新模块 `com.sqlpipeline.ldap` 只需加入 boot 依赖即可被组件扫描与属性扫描自动发现，**无需 spring.factories / AutoConfiguration.imports**。

## 实现方案

### 1. web 模块：新增 SPI 接口（唯一的 web 改动面）

新建 `sql-pipeline-web/.../web/auth/DirectoryAuthenticator.java`：

```java
/** 外部目录认证 SPI：LDAP/AD 等企业目录实现本接口并注册为 Spring Bean 即可启用。 */
public interface DirectoryAuthenticator {
    /** 认证成功返回目录用户（含建议角色）；凭据错误返回 empty；服务不可用按实现方策略处理。 */
    Optional<DirectoryUser> authenticate(String username, String password);

    record DirectoryUser(String username, String displayName, String role) {}
}
```

[AuthService.java](file:///d:/workspace/java/sql-pipeline/sql-pipeline-web/src/main/java/com/sqlpipeline/web/auth/AuthService.java) 构造注入 `ObjectProvider<DirectoryAuthenticator>`，login() 流程：

```
dirUser = dirAuth?.authenticate(username, raw)   // 服务不可用：failClosed 抛 503，否则 log.warn 走回退
if dirUser present:
    account = userStore.findByUsername(u).orElseGet(() -> provision(dirUser))  // 自动开通
    enabled=0 → AU_USER_DISABLED；TPAM 过期 → AU_TPAM_EXPIRED（LDAP 登录同样受本地禁用约束）
    return issue(username, account.role())   // 本地已有账号沿用本地 role（组映射只在开通时初始化）
// ↓ 原有本地 BCrypt 流程原样保留
```

- 自动开通：`userStore.createUser`，`password_hash` 写固定标记 **`"{LDAP}"`**（BCrypt 遇非 `$2a$` 前缀直接不匹配，零计算成本且 DBA 可一眼识别 LDAP 管理账号），role 取 `DirectoryUser.role()`
- AuthService 本身不感知 LDAP 细节（配置、JNDI 全在 ldap 模块）

### 2. 新模块 sql-pipeline-ldap（com.sqlpipeline.ldap）

```
sql-pipeline-ldap/
├── pom.xml                                  # 依赖：sql-pipeline-web（SPI）、sql-pipeline-common、lombok；test 依赖见 §5
└── src/main/java/com/sqlpipeline/ldap/
    ├── LdapProperties.java                  # @ConfigurationProperties("sql-pipeline.auth.ldap")
    ├── LdapAuthenticator.java               # JNDI 实现，implements DirectoryAuthenticator
    └── LdapAutoConfiguration.java           # @Configuration + 条件注册 Bean
```

- **LdapProperties**（`@ConfigurationPropertiesScan` 自动注册，无需额外开关）：

```java
private boolean enabled = false;                    // 默认关闭，零影响
private String url;                                 // ldap://ad.corp:389 或 ldaps://:636
private String baseDn;                              // DC=corp,DC=com
private String userSearchFilter = "(sAMAccountName={0})";
private String userDnPattern;                       // managerDn 为空时直接 bind（如 cn={0},ou=users,...）
private String managerDn;                           // 只读服务账号（search-then-bind）
private String managerPassword;
private List<String> adminGroups = new ArrayList<>(); // AD 组 DN，开通时命中→ADMIN
private String defaultRole = "VIEWER";
private int connectTimeoutMs = 3000;
private int readTimeoutMs = 5000;
private boolean startTls = false;                   // ldap:// + StartTlsResponse
private boolean failClosed = false;                 // true=LDAP 不可用返回 503；默认降级回退本地
```

- **LdapAuthenticator**：

```java
public Optional<DirectoryAuthenticator.DirectoryUser> authenticate(String username, String password);
```

  - **search-then-bind**：managerDn 非空 → manager 绑定后按 `userSearchFilter` 搜索取 DN/displayName/memberOf，再以用户 DN bind 验密码；managerDn 为空 → 按 `userDnPattern` 直接 bind（displayName 取不到则为空）
  - 组映射：`memberOf` 与 `adminGroups` 交集非空 → ADMIN，否则 `defaultRole`（角色判定在 ldap 模块内完成）
  - 超时：`com.sun.jndi.ldap.connect.timeout` / `read.timeout`；每次登录新建 `InitialDirContext`，finally 关闭，杜绝泄漏
  - LDAPS 原生支持（url=ldaps://）；startTls=true 时用 JDK `StartTlsResponse` 后再 bind
  - **防注入**：filter 值按 RFC 4515 转义（`\ * ( ) NUL`），DN 按 RFC 4514 转义；工具方法静态放同类内

- **LdapAutoConfiguration**：

```java
@Configuration(proxyBeanMethods = false)
public class LdapAutoConfiguration {
    @Bean
    @ConditionalOnProperty(name = "sql-pipeline.auth.ldap.enabled", havingValue = "true")
    public DirectoryAuthenticator directoryAuthenticator(LdapProperties properties) {
        return new LdapAuthenticator(properties);
    }
}
```

### 3. 构建接线

- [pom.xml](file:///d:/workspace/java/sql-pipeline/pom.xml)（parent）：`<modules>` 加 `sql-pipeline-ldap`；`dependencyManagement` 加对应条目
- [sql-pipeline-boot/pom.xml](file:///d:/workspace/java/sql-pipeline/sql-pipeline-boot/pom.xml)：加 `sql-pipeline-ldap` 依赖（扩展模块随主程序分发，靠配置开关启停）

### 4. 错误码与 i18n

- [ErrorCode.java](file:///d:/workspace/java/sql-pipeline/sql-pipeline-common/src/main/java/com/sqlpipeline/common/error/ErrorCode.java)：新增 `AU_LDAP_UNAVAILABLE("AU0008", 503, "企业目录认证服务暂不可用")`
- **默认 failClosed=false**：LDAP 不可用 → log.warn（仅异常类名，不含配置细节）→ 回退本地校验；最终失败统一抛 AU_LOGIN_FAILED，防用户枚举；AU0008 仅 failClosed=true 时返回
- messages.properties / messages_zh.properties 新增 `error.auth.ldapUnavailable` 双语文案
- application.yml 追加 `sql-pipeline.auth.ldap` 配置示例段（默认注释掉）
- 前端 login.js 无需改动（表单仍为 username/password）
- schema.sql `password_hash` 注释补充 `"{LDAP}"` 标记说明

### 5. 测试（全部放 sql-pipeline-ldap 模块）

ldap 模块 pom 加 test 依赖：`spring-boot-starter-test` + `com.unboundid:unboundid-ldapsdk`（test scope，内嵌 InMemoryDirectoryServer 模拟 AD：ou=people + uid 条目含 displayName/memberOf）。用例（JUnit5 + AssertJ，对齐现有测试风格）：

- **LdapAuthenticatorTest**：search+bind 成功取 displayName/memberOf；错密码/不存在用户→empty；filter 注入用户名 `*)(uid=*))(|uid=*`→empty；admin-group 命中→ADMIN / 未命中→VIEWER；userDnPattern 直接 bind 模式；服务宕机→failClosed 抛 AU0008 / 默认 empty
- **AuthServiceLdapIntegrationTest**（Mockito mock DirectoryAuthenticator/UserStore）：LDAP 成功自动开通（验证 passwordHash=`"{LDAP}"`）；本地已有沿用本地 role；本地 disabled 拦截；SPI Bean 缺失（getIfAvailable=null）走本地；LDAP 不可用降级本地成功

## 文件清单

| 操作 | 文件 |
|---|---|
| 新 | sql-pipeline-ldap/pom.xml、LdapProperties、LdapAuthenticator、LdapAutoConfiguration |
| 新 | sql-pipeline-web/.../auth/DirectoryAuthenticator.java（SPI 接口） |
| 改 | sql-pipeline-web/.../auth/AuthService.java（ObjectProvider 注入 + 目录优先/回退 + provision） |
| 改 | sql-pipeline-common/.../error/ErrorCode.java（AU0008） |
| 改 | pom.xml（modules + dependencyManagement）、sql-pipeline-boot/pom.xml（依赖） |
| 改 | sql-pipeline-boot/src/main/resources/application.yml（ldap 配置示例，默认注释） |
| 改 | sql-pipeline-boot/src/main/resources/messages.properties、messages_zh.properties |
| 改 | sql-pipeline-boot/src/main/resources/schema.sql（password_hash 注释） |
| 新 | sql-pipeline-ldap/src/test/.../LdapAuthenticatorTest.java、AuthServiceLdapIntegrationTest.java |

## 安全要点

- managerPassword / 用户密码绝不入日志；日志仅记 url、结果、异常类名
- 连接/读超时兜底，LDAP 故障默认不阻塞本地登录；认证失败统一 AU_LOGIN_FAILED 防枚举
- enabled 默认 false；LDAP 分支同样受本地 enabled=0 与 TPAM 过期约束
- 扩展性：后续 OAuth2/内部账号中心同样实现 `DirectoryAuthenticator` SPI 即可接入，web/AuthService 不再改动

## 验证

1. `mvn test` 全模块编译 + 单测通过（重点 sql-pipeline-ldap 新增测试）
2. 手动验证默认行为不变：`ldap.enabled` 不配置时启动登录 admin/admin123 正常（LdapAuthenticator Bean 不存在，走原路径）
3. 端到端（可选）：启动 unboundid 测试 LDAP 或指向真实 AD，配置 `sql-pipeline.auth.ldap.enabled=true` 后用目录账号登录，确认自动开通（platform_user 出现 `{LDAP}` 标记记录）与角色分配
