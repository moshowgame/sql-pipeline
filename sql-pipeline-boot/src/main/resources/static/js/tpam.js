/**
 * Self-TPAM 自助临时账号页：
 * - 申请：CR/IN 单号 + staffid（生成 staffid_tpam）+ 角色（ADMIN/VIEWER），4 小时有效
 * - 密码仅在申请响应中出现一次，弹窗展示 + 一键复制
 * - 列表：现存临时账号（剩余时间/过期状态），可提前吊销
 */
let tpamLastPassword = null;

$(function () {
    App.bindOperator();
    loadTpam();
});

function loadTpam() {
    App.api('GET', '/api/auth/tpam/list').then(function (list) {
        renderTpam(list || []);
    });
}

function renderTpam(list) {
    const now = Date.now();
    const rows = list.map(function (u) {
        const expMs = u.expiresAt ? new Date(u.expiresAt).getTime() : null;
        const expired = expMs != null && expMs <= now;
        const hoursLeft = expMs != null ? Math.max(0, (expMs - now) / 3600000).toFixed(1) : '-';
        return '<tr><td class="mono">' + App.escapeHtml(u.username) + '</td>' +
            '<td>' + (u.role === 'ADMIN' ? App.badge('ADMIN', 'warning') : App.badge('VIEWER', 'secondary')) + '</td>' +
            '<td class="mono">' + App.escapeHtml(u.crInNo || '-') + '</td>' +
            '<td class="mono">' + App.escapeHtml(u.staffId || '-') + '</td>' +
            '<td>' + App.escapeHtml(u.expiresAt ? new Date(u.expiresAt).toLocaleString() : '-') + '</td>' +
            '<td>' + (expired ? App.badge('EXPIRED', 'danger') :
                '<span class="text-muted small">' + App.escapeHtml(I18N.t('tpam.remaining', { h: hoursLeft })) + '</span>') + '</td>' +
            '<td><button class="btn btn-outline-danger btn-sm py-0" onclick="tpamRevoke(\'' + App.escapeHtml(u.username) + '\')">' +
            App.escapeHtml(I18N.t('tpam.revoke')) + '</button></td></tr>';
    });
    $('#tpamTable').html(rows.length ? rows.join('') :
        '<tr><td colspan="7" class="text-center text-muted py-4">' + App.escapeHtml(I18N.t('tpam.empty')) + '</td></tr>');
}

/** 申请临时账号：生成 staffid_tpam + 32 位随机密码（仅本次响应返回）。 */
function tpamGrant() {
    const req = {
        crInNo: $('#tCrInNo').val().trim(),
        staffId: $('#tStaffId').val().trim(),
        role: $('#tRole').val()
    };
    if (!req.crInNo || !req.staffId) {
        App.toast(I18N.t('tpam.invalid'), 'warning');
        return;
    }
    App.api('POST', '/api/auth/tpam/grant', req).then(function (r) {
        tpamLastPassword = r.password;
        $('#rUsername').text(r.username);
        $('#rPassword').text(r.password);
        $('#rRole').text(r.role === 'ADMIN' ? 'ADMIN' : 'VIEWER');
        $('#rCrInNo').text(r.crInNo);
        $('#rExpiresAt').text(new Date(r.expiresAt).toLocaleString());
        new bootstrap.Modal('#tpamResultModal').show();
        loadTpam();
    });
}

function copyTpamPassword() {
    if (!tpamLastPassword) return;
    navigator.clipboard.writeText(tpamLastPassword).then(function () {
        App.toast(I18N.t('tpam.copied'), 'success');
    });
}

/** 提前吊销：删除临时账号并释放 staffid。 */
function tpamRevoke(username) {
    if (!confirm(I18N.t('tpam.revokeConfirm', { user: username }))) return;
    App.api('POST', '/api/auth/tpam/revoke', { username: username }).then(function () {
        loadTpam();
    });
}
