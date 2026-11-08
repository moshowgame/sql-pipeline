package com.sqlpipeline.ldap;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.web.auth.DirectoryAuthenticator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.StartTlsRequest;
import javax.naming.ldap.StartTlsResponse;
import java.util.Hashtable;
import java.util.Optional;
import java.util.Set;

import static org.springframework.util.StringUtils.hasText;

/**
 * LDAP / AD 目录认证器（JDK 原生 JNDI 实现，零额外依赖）。
 * <p>
 * 两种认证模式：
 * <ul>
 *   <li>search-then-bind（推荐，AD 常规）：managerDn 配置后先以服务账号搜索定位用户 DN（顺带取 displayName / memberOf），
 *       再以用户 DN bind 验证密码；</li>
 *   <li>direct bind：managerDn 未配置而 userDnPattern 配置时，按模板拼用户 DN 直接 bind（取不到 displayName / memberOf）。</li>
 * </ul>
 * <p>
 * 安全设计：filter/DN 注入防护（RFC 4515/4514 转义）；连接/读超时兜底；每次登录新建连接并在 finally 关闭；
 * 密码类配置与凭据绝不入日志；目录不可用默认降级回退本地认证（failClosed=true 时抛 503）。
 */
@Slf4j
@RequiredArgsConstructor
public class LdapAuthenticator implements DirectoryAuthenticator {

    private static final String CONNECT_TIMEOUT_KEY = "com.sun.jndi.ldap.connect.timeout";
    private static final String READ_TIMEOUT_KEY = "com.sun.jndi.ldap.read.timeout";
    private static final Set<Character> DN_SPECIALS = Set.of('\\', ',', '+', '"', '<', '>', ';');

    private final LdapProperties props;

    @Override
    public Optional<DirectoryUser> authenticate(String username, String password) {
        if (!hasText(username) || !hasText(password) || !hasText(props.getUrl())) {
            return Optional.empty();
        }
        try {
            if (hasText(props.getManagerDn())) {
                return searchThenBind(username, password);
            }
            if (hasText(props.getUserDnPattern())) {
                return directBind(username, password);
            }
            log.error("LDAP 配置不完整：manager-dn 与 user-dn-pattern 均未配置，无法认证");
            return Optional.empty();
        } catch (BizException e) {
            throw e; // failClosed → AU_LDAP_UNAVAILABLE，交由 AuthService 直接上抛
        } catch (NamingException e) {
            return handleUnavailable(e);
        }
    }

    // ---------- 认证模式 ----------

    /** search-then-bind：manager 定位用户 DN → 用户 DN bind 验密 → displayName/memberOf → 角色映射。 */
    private Optional<DirectoryUser> searchThenBind(String username, String password) throws NamingException {
        LdapContext manager = null;
        try {
            manager = connect(props.getManagerDn(), props.getManagerPassword());
            SearchResult entry = findUser(manager, username);
            if (entry == null || !bindVerify(entry.getNameInNamespace(), password)) {
                return Optional.empty(); // 目录中无此用户，或用户 DN bind 失败（密码错误）
            }
            String displayName = attrFirst(entry.getAttributes(), "displayName");
            Set<String> memberOf = attrAll(entry.getAttributes(), "memberOf");
            return Optional.of(new DirectoryUser(username, displayName, resolveRole(memberOf)));
        } finally {
            close(manager);
        }
    }

    /** direct bind：userDnPattern 拼 DN（RFC 4514 转义）直接验密，无 displayName/memberOf，角色取默认值。 */
    private Optional<DirectoryUser> directBind(String username, String password) throws NamingException {
        String userDn = props.getUserDnPattern().replace("{0}", escapeDn(username));
        if (!bindVerify(userDn, password)) {
            return Optional.empty();
        }
        return Optional.of(new DirectoryUser(username, "", props.getDefaultRole()));
    }

    /** 以用户 DN + 输入密码 bind 验证：AuthenticationException=密码错误（false）；其余异常上抛按服务不可用处理。 */
    private boolean bindVerify(String userDn, String password) throws NamingException {
        LdapContext ctx = null;
        try {
            ctx = connect(userDn, password);
            return true;
        } catch (javax.naming.AuthenticationException e) {
            return false;
        } finally {
            close(ctx);
        }
    }

    /** manager 上下文内按 userSearchFilter 精确搜索用户，最多取 1 条；无结果/目录无此用户返回 null。 */
    private SearchResult findUser(LdapContext manager, String username) throws NamingException {
        SearchControls sc = new SearchControls();
        sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
        sc.setReturningAttributes(new String[]{"displayName", "memberOf"});
        sc.setCountLimit(1);
        String filter = props.getUserSearchFilter().replace("{0}", escapeFilter(username));
        try {
            NamingEnumeration<SearchResult> results = manager.search(props.getBaseDn(), filter, sc);
            return results.hasMore() ? results.next() : null;
        } catch (NameNotFoundException e) {
            return null; // baseDn 配错或用户不存在，按无此用户处理
        }
    }

    // ---------- 连接管理 ----------

    /**
     * 新建目录连接（simple bind）：startTls=true 时先建立匿名连接再 StartTLS 升级，随后注入凭据重连。
     * 每次登录独立建连，杜绝共享连接池的凭据残留；连接/读超时取自配置。
     */
    private LdapContext connect(String principal, String credentials) throws NamingException {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, props.getUrl());
        env.put(CONNECT_TIMEOUT_KEY, String.valueOf(props.getConnectTimeoutMs()));
        env.put(READ_TIMEOUT_KEY, String.valueOf(props.getReadTimeoutMs()));
        if (props.isStartTls()) {
            LdapContext ctx = new InitialLdapContext(env, null);
            try {
                StartTlsResponse tls = (StartTlsResponse) ctx.extendedOperation(new StartTlsRequest());
                try {
                    tls.negotiate(); // TLS 握手升级为加密通道
                } catch (java.io.IOException e) {
                    throw rethrowNaming(e); // TLS 协商失败
                }
                ctx.addToEnvironment(Context.SECURITY_AUTHENTICATION, "simple");
                ctx.addToEnvironment(Context.SECURITY_PRINCIPAL, principal);
                ctx.addToEnvironment(Context.SECURITY_CREDENTIALS, credentials);
                ctx.reconnect(null); // 在已加密通道上重新以 simple bind 验证凭据
                return ctx;
            } catch (NamingException e) {
                close(ctx);
                throw e;
            }
        }
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, principal);
        env.put(Context.SECURITY_CREDENTIALS, credentials);
        return new InitialLdapContext(env, null);
    }

    /** 目录服务不可用处理：failClosed 抛 AU_LDAP_UNAVAILABLE(503)，默认降级 log.warn 后回退本地认证。 */
    private Optional<DirectoryUser> handleUnavailable(NamingException e) {
        if (props.isFailClosed()) {
            throw BizException.i18n(ErrorCode.AU_LDAP_UNAVAILABLE, "error.auth.ldapUnavailable");
        }
        log.warn("LDAP 服务不可用，降级回退本地认证: {}", e.getClass().getSimpleName());
        return Optional.empty();
    }

    /** bind 失败语义区分：凭据错误（AuthenticationException/NameNotFound）→ empty 走回退；其余视为服务不可用。 */
    private NamingException rethrowNaming(Exception e) {
        return e instanceof NamingException ne ? ne
                : new NamingException(e.getClass().getSimpleName());
    }

    // ---------- 角色映射 ----------

    /** memberOf 与 adminGroups 交集非空 → ADMIN（仅首次开通生效），否则默认角色。 */
    private String resolveRole(Set<String> memberOf) {
        for (String adminGroup : props.getAdminGroups()) {
            if (memberOf.contains(adminGroup)) {
                return "ADMIN";
            }
        }
        return props.getDefaultRole();
    }

    // ---------- 属性读取 ----------

    private String attrFirst(Attributes attrs, String name) throws NamingException {
        if (attrs == null) {
            return "";
        }
        Attribute attr = attrs.get(name);
        return attr != null && attr.get() != null ? String.valueOf(attr.get()) : "";
    }

    private Set<String> attrAll(Attributes attrs, String name) throws NamingException {
        if (attrs == null) {
            return Set.of();
        }
        Attribute attr = attrs.get(name);
        if (attr == null) {
            return Set.of();
        }
        Set<String> values = new java.util.HashSet<>();
        NamingEnumeration<?> enumeration = attr.getAll();
        while (enumeration.hasMore()) {
            values.add(String.valueOf(enumeration.next()));
        }
        return values;
    }

    private void close(LdapContext ctx) {
        if (ctx != null) {
            try {
                ctx.close();
            } catch (NamingException e) {
                log.debug("LDAP 连接关闭失败: {}", e.getClass().getSimpleName());
            }
        }
    }

    // ---------- 注入防护 ----------

    /** RFC 4515 过滤器转义：\ * ( ) NUL。 */
    static String escapeFilter(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\5c");
                case '*' -> sb.append("\\2a");
                case '(' -> sb.append("\\28");
                case ')' -> sb.append("\\29");
                case 0 -> sb.append("\\00");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** RFC 4514 DN 转义：\ + , " < > ; NUL 及首尾空格、前导 #。 */
    static String escapeDn(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0) {
                sb.append("\\00");
            } else if (DN_SPECIALS.contains(c)) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0 && sb.charAt(0) == ' ') {
            sb.insert(0, '\\');
        }
        int last = sb.length() - 1;
        if (last > 0 && sb.charAt(last) == ' ') {
            sb.insert(last, '\\');
        }
        return sb.toString();
    }
}
