/**
 * 登录页：POST /api/auth/login 成功后服务端写入 AUTH_TOKEN Cookie（HttpOnly，1 天），
 * 前端只记录用户名（localStorage.operator）供 X-Operator 头使用，然后跳回来源页。
 */
$(function () {
    // 已登录（Cookie 未过期）则直接进入首页
    $.get('/api/auth/me').then(function (res) {
        if (res && res.code === '0') {
            location.replace(nextPage());
        }
    });

    $('#loginForm').on('submit', function (e) {
        e.preventDefault();
        const username = $('#username').val().trim();
        const password = $('#password').val();
        if (!username || !password) return;
        const $btn = $('#submitBtn').prop('disabled', true).text(I18N.t('login.submitting'));
        $.ajax({
            url: '/api/auth/login',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({ username: username, password: password }),
            dataType: 'json'
        }).then(function (res) {
            if (res && res.code !== '0') {
                App.toast(res.message || 'Login failed', 'danger');
                return;
            }
            localStorage.setItem('operator', res.data.username);
            localStorage.setItem('role', res.data.role || 'ADMIN');
            location.replace(nextPage());
        }).fail(function (xhr) {
            const r = xhr && xhr.responseJSON;
            App.toast(r && r.message ? r.message : 'HTTP ' + (xhr && xhr.status), 'danger');
        }).always(function () {
            $btn.prop('disabled', false).text(I18N.t('login.submit'));
        });
    });

    function nextPage() {
        const next = new URLSearchParams(location.search).get('next');
        return next && next.indexOf('/') === 0 ? next : '/';
    }
});
