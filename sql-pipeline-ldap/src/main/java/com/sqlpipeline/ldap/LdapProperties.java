package com.sqlpipeline.ldap;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * LDAP / AD 目录认证配置（sql-pipeline.auth.ldap），由 boot 的 @ConfigurationPropertiesScan 自动注册。
 * enabled=false（默认）时 LdapAuthenticator Bean 不创建，登录行为与历史完全一致。
 */
@Data
@ConfigurationProperties(prefix = "sql-pipeline.auth.ldap")
public class LdapProperties {

    /** 是否启用 LDAP 认证。 */
    private boolean enabled = false;

    /** 目录服务地址：ldap://ad.corp.com:389 或 ldaps://ad.corp.com:636（LDAPS 需 CA 证书入 JVM truststore）。 */
    private String url;

    /** 搜索基础 DN，如 DC=corp,DC=com。 */
    private String baseDn;

    /** 用户搜索过滤器，{0} 为登录名（自动做 RFC 4515 防注入转义）。 */
    private String userSearchFilter = "(sAMAccountName={0})";

    /** 用户 DN 模板（如 cn={0},ou=users,dc=corp,dc=com）：managerDn 未配置时的直接 bind 模式，{0} 为转义后的登录名。 */
    private String userDnPattern;

    /** 只读服务账号 DN（search-then-bind 模式：先以 manager 搜索定位用户 DN，再以用户 DN bind 验密码）；配置后优先于 userDnPattern。 */
    private String managerDn;

    /** 服务账号密码，生产环境务必通过环境变量覆盖。 */
    private String managerPassword;

    /** AD 组 DN 列表：首次开通时 memberOf 命中任一组 → ADMIN，否则 defaultRole。 */
    private List<String> adminGroups = new ArrayList<>();

    /** 首次开通时的默认角色（ADMIN / VIEWER），默认 VIEWER 只读。 */
    private String defaultRole = "VIEWER";

    /** 连接超时（毫秒）。 */
    private int connectTimeoutMs = 3000;

    /** 读超时（毫秒）。 */
    private int readTimeoutMs = 5000;

    /** 是否对 ldap:// 连接启用 StartTLS 升级加密（ldaps:// 时无需开启）。 */
    private boolean startTls = false;

    /**
     * 目录服务不可用时的策略：
     * false（默认）= 降级回退本地密码校验并 log.warn；
     * true = 抛 AU_LDAP_UNAVAILABLE（503）拒绝登录。
     */
    private boolean failClosed = false;
}
