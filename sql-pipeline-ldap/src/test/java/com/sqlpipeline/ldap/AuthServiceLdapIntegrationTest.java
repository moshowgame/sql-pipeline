package com.sqlpipeline.ldap;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.web.auth.AuthService;
import com.sqlpipeline.web.auth.DirectoryAuthenticator;
import com.sqlpipeline.web.auth.PasswordEncryptor;
import com.sqlpipeline.web.auth.TokenManager;
import com.sqlpipeline.web.auth.UserAccount;
import com.sqlpipeline.web.auth.UserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 目录认证集成行为（Mockito）：自动开通、本地角色优先、禁用拦截、SPI 缺失/异常回退。
 */
class AuthServiceLdapIntegrationTest {

    private UserStore userStore;
    private PasswordEncryptor passwordEncryptor;
    private TokenManager tokenManager;
    private DirectoryAuthenticator directoryAuthenticator;

    @SuppressWarnings("unchecked")
    private ObjectProvider<DirectoryAuthenticator> provider(DirectoryAuthenticator bean) {
        ObjectProvider<DirectoryAuthenticator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    private UserAccount localUser(String role, int enabled) {
        UserAccount user = new UserAccount();
        user.setUsername("alice");
        user.setPasswordHash("$2a$10$existingHash");
        user.setDisplayName("Alice");
        user.setRole(role);
        user.setEnabled(enabled);
        return user;
    }

    @BeforeEach
    void setUp() {
        userStore = mock(UserStore.class);
        passwordEncryptor = mock(PasswordEncryptor.class);
        tokenManager = mock(TokenManager.class);
        directoryAuthenticator = mock(DirectoryAuthenticator.class);
    }

    @Test
    void directoryUserFirstLogin_provisionsLocalAccount() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.empty());
        when(directoryAuthenticator.authenticate("alice", "dirPass"))
                .thenReturn(Optional.of(new DirectoryAuthenticator.DirectoryUser("alice", "Alice Zhang", "VIEWER")));
        when(tokenManager.issue("alice", "VIEWER")).thenReturn("token-1");

        AuthService service = new AuthService(userStore, passwordEncryptor, tokenManager, provider(directoryAuthenticator));
        AuthService.LoginResult result = service.login("alice", "dirPass");

        assertThat(result.token()).isEqualTo("token-1");
        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userStore).createUser(captor.capture());
        UserAccount created = captor.getValue();
        assertThat(created.getPasswordHash()).isEqualTo(AuthService.LDAP_PASSWORD_MARKER);
        assertThat(created.getRole()).isEqualTo("VIEWER");
        assertThat(created.getEnabled()).isEqualTo(1);
        assertThat(created.getDisplayName()).isEqualTo("Alice Zhang");
    }

    @Test
    void localAccountExists_keepsLocalRoleAndDoesNotRecreate() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(localUser("ADMIN", 1)));
        when(directoryAuthenticator.authenticate("alice", "dirPass"))
                .thenReturn(Optional.of(new DirectoryAuthenticator.DirectoryUser("alice", "Alice Zhang", "VIEWER")));
        when(tokenManager.issue("alice", "ADMIN")).thenReturn("token-2");

        AuthService service = new AuthService(userStore, passwordEncryptor, tokenManager, provider(directoryAuthenticator));
        AuthService.LoginResult result = service.login("alice", "dirPass");

        assertThat(result.token()).isEqualTo("token-2"); // 本地 role 获胜，避免每次登录意外提权/降权
        verify(userStore, never()).createUser(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void localAccountDisabled_blocksDirectoryLogin() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(localUser("VIEWER", 0)));
        when(directoryAuthenticator.authenticate("alice", "dirPass"))
                .thenReturn(Optional.of(new DirectoryAuthenticator.DirectoryUser("alice", "", "ADMIN")));

        AuthService service = new AuthService(userStore, passwordEncryptor, tokenManager, provider(directoryAuthenticator));
        assertThatThrownBy(() -> service.login("alice", "dirPass"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.AU_USER_DISABLED));
    }

    @Test
    void spiMissing_fallsBackToLocalPassword() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(localUser("ADMIN", 1)));
        when(passwordEncryptor.matches("localPass", "$2a$10$existingHash")).thenReturn(true);
        when(tokenManager.issue("alice", "ADMIN")).thenReturn("token-3");

        AuthService service = new AuthService(userStore, passwordEncryptor, tokenManager, provider(null));
        AuthService.LoginResult result = service.login("alice", "localPass");

        assertThat(result.token()).isEqualTo("token-3");
        verify(directoryAuthenticator, never()).authenticate(anyString(), anyString());
    }

    @Test
    void directoryException_failOpen_fallsBackToLocal() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(localUser("ADMIN", 1)));
        when(directoryAuthenticator.authenticate("alice", "anyPass")).thenThrow(new RuntimeException("connect refused"));
        when(passwordEncryptor.matches("anyPass", "$2a$10$existingHash")).thenReturn(true);
        when(tokenManager.issue("alice", "ADMIN")).thenReturn("token-4");

        AuthService service = new AuthService(userStore, passwordEncryptor, tokenManager, provider(directoryAuthenticator));
        assertThat(service.login("alice", "anyPass").token()).isEqualTo("token-4");
    }

    @Test
    void directoryFailClosed_propagatesLdapUnavailable() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.empty());
        when(directoryAuthenticator.authenticate("alice", "dirPass"))
                .thenThrow(BizException.i18n(ErrorCode.AU_LDAP_UNAVAILABLE, "error.auth.ldapUnavailable"));

        AuthService service = new AuthService(userStore, passwordEncryptor, tokenManager, provider(directoryAuthenticator));
        assertThatThrownBy(() -> service.login("alice", "dirPass"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.AU_LDAP_UNAVAILABLE));
        verify(tokenManager, never()).issue(anyString(), anyString());
    }

    @Test
    void directoryUnknownUser_fallsBackToLocalCredential() {
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(localUser("ADMIN", 1)));
        when(directoryAuthenticator.authenticate("alice", "localPass")).thenReturn(Optional.empty());
        when(passwordEncryptor.matches(eq("localPass"), anyString())).thenReturn(true);
        when(tokenManager.issue("alice", "ADMIN")).thenReturn("token-5");

        AuthService service = new AuthService(userStore, passwordEncryptor, tokenManager, provider(directoryAuthenticator));
        assertThat(service.login("alice", "localPass").token()).isEqualTo("token-5");
    }

    // ---------- 补充用例 ----------

    @Test
    void tpamExpiredLocalAccount_blocksDirectoryLogin() {
        UserAccount expired = localUser("ADMIN", 1);
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1)); // Self-TPAM 临时账号已过期
        when(userStore.findByUsername("alice")).thenReturn(Optional.of(expired));
        when(directoryAuthenticator.authenticate("alice", "dirPass"))
                .thenReturn(Optional.of(new DirectoryAuthenticator.DirectoryUser("alice", "", "ADMIN")));

        AuthService service = new AuthService(userStore, passwordEncryptor, tokenManager, provider(directoryAuthenticator));
        assertThatThrownBy(() -> service.login("alice", "dirPass"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.AU_TPAM_EXPIRED));
        verify(tokenManager, never()).issue(anyString(), anyString());
    }

    @Test
    void directoryUserBlankDisplayName_fallsBackToUsername() {
        when(userStore.findByUsername("bob")).thenReturn(Optional.empty());
        when(directoryAuthenticator.authenticate("bob", "dirPass"))
                .thenReturn(Optional.of(new DirectoryAuthenticator.DirectoryUser("bob", "  ", "VIEWER")));
        when(tokenManager.issue("bob", "VIEWER")).thenReturn("token-6");

        AuthService service = new AuthService(userStore, passwordEncryptor, tokenManager, provider(directoryAuthenticator));
        service.login("bob", "dirPass");

        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userStore).createUser(captor.capture());
        assertThat(captor.getValue().getDisplayName()).isEqualTo("bob"); // 空白 displayName 回退登录名
    }
}
