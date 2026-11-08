package com.sqlpipeline.ldap;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.web.auth.DirectoryAuthenticator;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Entry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LdapAuthenticator 集成单测：unboundid 内存 LDAP 模拟 AD（search-then-bind / direct bind / 注入防护 / 降级策略）。
 */
class LdapAuthenticatorTest {

    private static final String BASE_DN = "dc=corp,dc=com";
    private static final String ADMIN_GROUP_DN = "cn=DBA-Admins,ou=groups,dc=corp,dc=com";
    private static final String OPS_GROUP_DN = "cn=Ops-Leads,ou=groups,dc=corp,dc=com";

    private InMemoryDirectoryServer server;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0));
        // 关闭 schema 校验：测试条目使用 AD 特有属性（memberOf），无需完整 schema 定义
        config.setSchema(null);
        server = new InMemoryDirectoryServer(config);

        Entry root = entry(BASE_DN, "top", "dcObject", "organization");
        root.addAttribute("dc", "corp");
        root.addAttribute("o", "corp");
        server.add(root);

        Entry people = entry("ou=people," + BASE_DN, "top", "organizationalUnit");
        people.addAttribute("ou", "people");
        server.add(people);

        Entry service = entry("ou=service," + BASE_DN, "top", "organizationalUnit");
        service.addAttribute("ou", "service");
        server.add(service);

        Entry groups = entry("ou=groups," + BASE_DN, "top", "organizationalUnit");
        groups.addAttribute("ou", "groups");
        server.add(groups);

        Entry alice = entry("uid=alice,ou=people," + BASE_DN, "top", "person", "organizationalPerson", "inetOrgPerson");
        alice.addAttribute("uid", "alice");
        alice.addAttribute("cn", "Alice Zhang");
        alice.addAttribute("sn", "Zhang");
        alice.addAttribute("userPassword", "alicePass");
        alice.addAttribute("displayName", "Alice Zhang");
        alice.addAttribute("memberOf", ADMIN_GROUP_DN);
        server.add(alice);

        Entry bob = entry("uid=bob,ou=people," + BASE_DN, "top", "person", "organizationalPerson", "inetOrgPerson");
        bob.addAttribute("uid", "bob");
        bob.addAttribute("cn", "Bob Li");
        bob.addAttribute("sn", "Li");
        bob.addAttribute("userPassword", "bobPass");
        bob.addAttribute("displayName", "Bob Li");
        server.add(bob);

        // carol：无 displayName 属性 + memberOf 命中第二个 admin 组（多组映射用例）
        Entry carol = entry("uid=carol,ou=people," + BASE_DN, "top", "person", "organizationalPerson", "inetOrgPerson");
        carol.addAttribute("uid", "carol");
        carol.addAttribute("cn", "Carol Wang");
        carol.addAttribute("sn", "Wang");
        carol.addAttribute("userPassword", "carolPass");
        carol.addAttribute("memberOf", OPS_GROUP_DN);
        server.add(carol);

        // dave：深层嵌套 OU（子树搜索用例）
        Entry teams = entry("ou=teams,ou=people," + BASE_DN, "top", "organizationalUnit");
        teams.addAttribute("ou", "teams");
        server.add(teams);
        Entry dave = entry("uid=dave,ou=teams,ou=people," + BASE_DN,
                "top", "person", "organizationalPerson", "inetOrgPerson");
        dave.addAttribute("uid", "dave");
        dave.addAttribute("cn", "Dave Chen");
        dave.addAttribute("sn", "Chen");
        dave.addAttribute("displayName", "Dave Chen");
        dave.addAttribute("userPassword", "davePass");
        server.add(dave);

        Entry svc = entry("uid=svc,ou=service," + BASE_DN, "top", "person", "organizationalPerson", "inetOrgPerson");
        svc.addAttribute("uid", "svc");
        svc.addAttribute("cn", "Service Account");
        svc.addAttribute("sn", "Service");
        svc.addAttribute("userPassword", "svcPass");
        server.add(svc);

        server.startListening();
    }

    /** 构造条目（objectClass 多值 + DN），返回可继续 addAttribute 的条目。 */
    private static Entry entry(String dn, String... objectClasses) {
        Entry e = new Entry(dn);
        e.addAttribute("objectClass", objectClasses);
        return e;
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.shutDown(true);
        }
    }

    /** search-then-bind 模式配置。 */
    private LdapProperties searchProps() {
        LdapProperties p = new LdapProperties();
        p.setEnabled(true);
        p.setUrl("ldap://127.0.0.1:" + server.getListenPort());
        p.setBaseDn(BASE_DN);
        p.setUserSearchFilter("(uid={0})"); // 测试条目使用 uid 属性（AD 真实环境默认 sAMAccountName）
        p.setManagerDn("uid=svc,ou=service," + BASE_DN);
        p.setManagerPassword("svcPass");
        p.setAdminGroups(List.of(ADMIN_GROUP_DN));
        return p;
    }

    @Test
    void searchThenBind_success_returnsDisplayNameAndAdminRole() {
        LdapAuthenticator auth = new LdapAuthenticator(searchProps());
        Optional<DirectoryAuthenticator.DirectoryUser> result = auth.authenticate("alice", "alicePass");
        assertThat(result).isPresent();
        assertThat(result.get().username()).isEqualTo("alice");
        assertThat(result.get().displayName()).isEqualTo("Alice Zhang");
        assertThat(result.get().role()).isEqualTo("ADMIN"); // memberOf 命中 admin-groups
    }

    @Test
    void searchThenBind_noAdminGroup_returnsDefaultViewerRole() {
        LdapAuthenticator auth = new LdapAuthenticator(searchProps());
        Optional<DirectoryAuthenticator.DirectoryUser> result = auth.authenticate("bob", "bobPass");
        assertThat(result).isPresent();
        assertThat(result.get().role()).isEqualTo("VIEWER");
    }

    @Test
    void searchThenBind_wrongPassword_returnsEmpty() {
        LdapAuthenticator auth = new LdapAuthenticator(searchProps());
        assertThat(auth.authenticate("alice", "wrongPass")).isEmpty();
    }

    @Test
    void searchThenBind_unknownUser_returnsEmpty() {
        LdapAuthenticator auth = new LdapAuthenticator(searchProps());
        assertThat(auth.authenticate("nobody", "whatever")).isEmpty();
    }

    @Test
    void searchThenBind_filterInjection_returnsEmpty() {
        LdapAuthenticator auth = new LdapAuthenticator(searchProps());
        // RFC 4515 转义后该输入只会精确匹配字面 uid，无法构造 OR 注入
        assertThat(auth.authenticate("*)(uid=*))(|uid=*", "x")).isEmpty();
    }

    // ---------- 补充：配置与输入短路 ----------

    @Test
    void missingConfig_returnsEmptyWithoutConnecting() {
        LdapAuthenticator auth = new LdapAuthenticator(searchProps());
        assertThat(auth.authenticate("", "pass")).isEmpty();        // 用户名为空
        assertThat(auth.authenticate("alice", "")).isEmpty();       // 密码为空
        LdapProperties noUrl = searchProps();
        noUrl.setUrl("");
        assertThat(new LdapAuthenticator(noUrl).authenticate("alice", "alicePass")).isEmpty(); // url 为空
    }

    @Test
    void searchThenBind_noDisplayName_returnsEmptyDisplayName() {
        LdapAuthenticator auth = new LdapAuthenticator(searchProps());
        Optional<DirectoryAuthenticator.DirectoryUser> result = auth.authenticate("carol", "carolPass");
        assertThat(result).isPresent();
        assertThat(result.get().displayName()).isEmpty(); // 目录无 displayName 属性
    }

    @Test
    void searchThenBind_secondAdminGroupHit_returnsAdminRole() {
        LdapProperties p = searchProps();
        p.setAdminGroups(List.of("cn=Not-Exist,ou=groups," + BASE_DN, OPS_GROUP_DN));
        LdapAuthenticator auth = new LdapAuthenticator(p);
        Optional<DirectoryAuthenticator.DirectoryUser> result = auth.authenticate("carol", "carolPass");
        assertThat(result).isPresent();
        assertThat(result.get().role()).isEqualTo("ADMIN"); // memberOf 命中列表中的第二个组
    }

    @Test
    void searchThenBind_deepNestedUser_foundBySubtreeSearch() {
        LdapAuthenticator auth = new LdapAuthenticator(searchProps());
        Optional<DirectoryAuthenticator.DirectoryUser> result = auth.authenticate("dave", "davePass");
        assertThat(result).isPresent(); // SUBTREE_SCOPE 覆盖 ou=teams,ou=people 嵌套层级
        assertThat(result.get().displayName()).isEqualTo("Dave Chen");
        assertThat(result.get().role()).isEqualTo("VIEWER");
    }

    // ---------- 补充：manager 服务账号异常语义 ----------

    @Test
    void searchThenBind_managerWrongPassword_failOpen_returnsEmpty() {
        LdapProperties p = searchProps();
        p.setManagerPassword("wrongSvcPass");
        LdapAuthenticator auth = new LdapAuthenticator(p);
        // 服务账号 bind 失败按"服务不可用"处理：默认 fail-open 降级，不抛异常
        assertThat(auth.authenticate("alice", "alicePass")).isEmpty();
    }

    @Test
    void searchThenBind_managerWrongPassword_failClosed_throwsUnavailable() {
        LdapProperties p = searchProps();
        p.setManagerPassword("wrongSvcPass");
        p.setFailClosed(true);
        LdapAuthenticator auth = new LdapAuthenticator(p);
        assertThatThrownBy(() -> auth.authenticate("alice", "alicePass"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.AU_LDAP_UNAVAILABLE));
    }

    // ---------- 补充：direct bind DN 注入 ----------

    @Test
    void directBind_dnInjection_returnsEmpty() {
        LdapProperties p = searchProps();
        p.setManagerDn(null);
        p.setManagerPassword(null);
        p.setUserDnPattern("uid={0},ou=people," + BASE_DN);
        LdapAuthenticator auth = new LdapAuthenticator(p);
        // RFC 4514 转义后 DN 成为字面 uid，无法构造 DN 注入跳转其他分支
        assertThat(auth.authenticate("dave,ou=teams,ou=people", "davePass")).isEmpty();
    }

    // ---------- 补充：StartTLS 分支 ----------

    @Test
    void startTls_serverNotSupporting_failsOpen_returnsEmpty() {
        // startTls=true 但目录服务器不支持 StartTLS：negotiate 失败按服务不可用降级（fail-open），不抛异常
        LdapProperties p = searchProps();
        p.setStartTls(true);
        LdapAuthenticator auth = new LdapAuthenticator(p);
        assertThat(auth.authenticate("alice", "alicePass")).isEmpty();
        // StartTLS 全链路（TLS 协商 + bind 验密）依赖企业 CA 信任与主机名匹配，属运维前提（见 README），单测不模拟
    }

    @Test
    void directBind_success_returnsDefaultRole() {
        LdapProperties p = searchProps();
        p.setManagerDn(null);
        p.setManagerPassword(null);
        p.setUserDnPattern("uid={0},ou=people," + BASE_DN);
        LdapAuthenticator auth = new LdapAuthenticator(p);
        Optional<DirectoryAuthenticator.DirectoryUser> result = auth.authenticate("alice", "alicePass");
        assertThat(result).isPresent();
        assertThat(result.get().role()).isEqualTo("VIEWER"); // direct bind 模式取默认角色
    }

    @Test
    void directBind_wrongPassword_returnsEmpty() {
        LdapProperties p = searchProps();
        p.setManagerDn(null);
        p.setManagerPassword(null);
        p.setUserDnPattern("uid={0},ou=people," + BASE_DN);
        LdapAuthenticator auth = new LdapAuthenticator(p);
        assertThat(auth.authenticate("alice", "wrongPass")).isEmpty();
    }

    @Test
    void serverDown_failOpen_returnsEmpty() {
        server.shutDown(true);
        LdapAuthenticator auth = new LdapAuthenticator(searchProps());
        assertThat(auth.authenticate("alice", "alicePass")).isEmpty(); // 默认降级，不抛异常
    }

    @Test
    void serverDown_failClosed_throwsLdapUnavailable() {
        server.shutDown(true);
        LdapProperties p = searchProps();
        p.setFailClosed(true);
        LdapAuthenticator auth = new LdapAuthenticator(p);
        assertThatThrownBy(() -> auth.authenticate("alice", "alicePass"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.AU_LDAP_UNAVAILABLE));
    }

    @Test
    void escapeFilter_masksSpecialCharacters() {
        assertThat(LdapAuthenticator.escapeFilter("a*b(c)d\\e"))
                .isEqualTo("a\\2ab\\28c\\29d\\5ce");
        assertThat(LdapAuthenticator.escapeFilter("x" + '\0' + "y")).isEqualTo("x\\00y");
    }

    @Test
    void escapeDn_masksSpecialCharacters() {
        assertThat(LdapAuthenticator.escapeDn("a,b\\c+d")).isEqualTo("a\\,b\\\\c\\+d");
        assertThat(LdapAuthenticator.escapeDn(" lead")).isEqualTo("\\ lead");
    }
}
