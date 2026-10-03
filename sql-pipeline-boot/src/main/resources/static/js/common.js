/**
 * sql-pipeline 管理界面公共层：
 * - App.api: 统一 REST 调用（JSON + X-Operator 头）
 * - toast / badge / 时间格式化等工具
 */
window.App = {
    operator: localStorage.getItem('operator') || 'admin',

    /** 统一 REST 请求，返回 jqXHR；失败（非 2xx 或 code!=0）以 toast 提示 */
    api(method, url, data) {
        return $.ajax({
            url: url,
            method: method,
            headers: { 'X-Operator': App.operator },
            contentType: data !== undefined ? 'application/json' : undefined,
            data: data !== undefined ? JSON.stringify(data) : undefined,
            dataType: 'json'
        }).then(function (res) {
            if (res && res.code !== '0') {
                App.toast('[' + res.code + '] ' + res.message, 'danger');
                return $.Deferred().reject(res).promise();
            }
            return res.data;
        });
    },

    toast(message, type) {
        type = type || 'success';
        let $container = $('.toast-container');
        if ($container.length === 0) {
            $container = $('<div class="toast-container position-fixed top-0 end-0 p-3"></div>').appendTo('body');
        }
        const id = 'toast-' + Date.now() + Math.floor(Math.random() * 1000);
        const html =
            '<div id="' + id + '" class="toast align-items-center text-bg-' + type + ' border-0" role="alert">' +
            '  <div class="d-flex"><div class="toast-body" style="white-space:pre-wrap;word-break:break-all">' +
            App.escapeHtml(message) +
            '  </div><button type="button" class="btn-close btn-close-white me-2 m-auto" data-bs-dismiss="toast"></button></div></div>';
        const $el = $(html).appendTo($container);
        const toast = new bootstrap.Toast($el[0], { delay: type === 'danger' ? 8000 : 3500 });
        toast.show();
        $el.on('hidden.bs.toast', function () { $el.remove(); });
    },

    escapeHtml(s) {
        if (s === null || s === undefined) return '';
        return String(s)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    },

    /** ISO 时间 → 本地可读格式；空值返回 - */
    fmtTime(iso) {
        if (!iso) return '-';
        const d = new Date(String(iso).length > 23 ? String(iso).substring(0, 23) : iso);
        if (isNaN(d.getTime())) return iso;
        const p = n => String(n).padStart(2, '0');
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
            ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
    },

    fmtMs(ms) {
        if (ms === null || ms === undefined) return '-';
        if (ms < 1000) return ms + ' ms';
        if (ms < 60000) return (ms / 1000).toFixed(1) + ' s';
        return Math.floor(ms / 60000) + ' m ' + Math.round(ms % 60000 / 1000) + ' s';
    },

    planBadge(status) {
        const map = {
            DRAFT: 'secondary', RUNNING: 'primary', WAITING: 'warning text-dark',
            PAUSED: 'secondary', COMPLETED: 'success', FAILED: 'danger', SKIPPED: 'dark'
        };
        return App.badge(status, map[status] || 'secondary');
    },

    stepBadge(status) {
        const map = {
            PENDING: 'secondary', RUNNING: 'primary', SUCCESS: 'success',
            FAIL: 'danger', WAITING_CONTINUE: 'warning text-dark', SKIPPED: 'dark'
        };
        return App.badge(status, map[status] || 'secondary');
    },

    runBadge(status) {
        const map = {
            SUCCESS: 'success', FAIL: 'danger', ERROR: 'dark', TIMEOUT: 'warning text-dark', RUNNING: 'primary'
        };
        return App.badge(status, map[status] || 'secondary');
    },

    badge(text, color) {
        return '<span class="badge bg-' + color + '">' + App.escapeHtml(text || '-') + '</span>';
    },

    /** 美化 JSON（失败则原样返回） */
    prettyJson(s) {
        if (!s) return '';
        try { return JSON.stringify(JSON.parse(s), null, 2); } catch (e) { return s; }
    },

    bindOperator() {
        const $input = $('#operatorInput');
        $input.val(App.operator);
        $input.on('change', function () {
            App.operator = $(this).val().trim() || 'admin';
            localStorage.setItem('operator', App.operator);
            App.toast('操作者已切换为 ' + App.operator);
        });
    }
};

// 全局兜底：HTTP 层错误（4xx/5xx）统一 toast
$(document).ajaxError(function (event, xhr) {
    if (xhr && xhr.responseJSON && xhr.responseJSON.code) return; // 业务错误已在 App.api 内提示
    let msg = '请求失败: ' + (xhr && xhr.status ? xhr.status : 'network error');
    try {
        const r = JSON.parse(xhr.responseText);
        if (r && r.message) msg = '[' + r.code + '] ' + r.message;
    } catch (e) { /* ignore */ }
    App.toast(msg, 'danger');
});
