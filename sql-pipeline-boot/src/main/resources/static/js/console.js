/**
 * SQL Console 独立页面：
 * - 连接下拉（记住上次选择）+ CodeMirror 编辑器（元数据表/字段补全）
 * - Ctrl+Enter / Run 执行（多语句 SELECT，多结果集网格）
 * - 查询历史存 localStorage（最近 20 条，点击回填，可清空）
 * - VIEWER 隐藏 Run（runSql 在 common.js WRITE_FNS 中）
 */
let connections = [];
let consoleCm = null;

const HISTORY_KEY = 'sqlConsoleHistory';
const HISTORY_LIMIT = 20;
const CONN_KEY = 'sqlConsoleConn';

function historyList() {
    try {
        const arr = JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]');
        return Array.isArray(arr) ? arr : [];
    } catch (e) { return []; }
}

function pushHistory(connKey, sql) {
    const items = historyList().filter(function (h) { return h.sql !== sql || h.connKey !== connKey; });
    items.unshift({ connKey: connKey, sql: sql, time: new Date().toISOString() });
    localStorage.setItem(HISTORY_KEY, JSON.stringify(items.slice(0, HISTORY_LIMIT)));
    renderHistory();
}

function renderHistory() {
    const items = historyList();
    $('#historyCount').text(items.length);
    if (!items.length) {
        $('#historyList').html('<li class="text-muted">' + App.escapeHtml(I18N.t('console.historyEmpty')) + '</li>');
        return;
    }
    $('#historyList').html(items.map(function (h, i) {
        const preview = h.sql.replace(/\s+/g, ' ');
        const label = preview.length > 80 ? preview.slice(0, 80) + '…' : preview;
        return '<li class="d-flex align-items-start gap-2 py-1 border-bottom">' +
            '<a href="javascript:void(0)" class="flex-grow-1 text-decoration-none" ' +
            'onclick="restoreHistory(' + i + ')" title="' + App.escapeHtml(h.sql) + '">' +
            '<span class="badge bg-info me-1">' + App.escapeHtml(h.connKey) + '</span>' +
            '<span class="mono">' + App.escapeHtml(label) + '</span></a>' +
            '<span class="text-muted small">' + App.fmtTime(h.time) + '</span></li>';
    }).join(''));
}

function restoreHistory(i) {
    const h = historyList()[i];
    if (!h || !consoleCm) return;
    consoleCm.setValue(h.sql);
    if (h.connKey) {
        $('#connSelect').val(h.connKey);
        $('#connSelect').trigger('change');
    }
    bootstrap.Collapse.getOrCreateInstance(document.getElementById('historyPanel')).hide();
}

function clearHistory() {
    localStorage.removeItem(HISTORY_KEY);
    renderHistory();
}

function connOptions(selected) {
    return connections.filter(c => c.enabled === 1).map(c =>
        '<option value="' + App.escapeHtml(c.connKey) + '"' +
        (c.connKey === selected ? ' selected' : '') + '>' +
        App.escapeHtml(c.connKey + '（' + c.displayName + '）') + '</option>').join('');
}

/** 拉取当前连接的表/字段元数据注入补全（失败静默，退化为关键字补全）。 */
function loadConsoleHints(connKey) {
    if (!connKey || !consoleCm) return;
    App.api('GET', '/api/connections/key/' + encodeURIComponent(connKey) + '/metadata')
        .then(function (tables) {
            const hint = {};
            (tables || []).forEach(function (t) { hint[t.name] = t.columns; });
            SqlEditor.setHintTables(consoleCm, hint);
        }).fail(function () { /* 元数据不可用时保持关键字级补全 */ });
}

function toggleConsoleTheme() {
    if (consoleCm) SqlEditor.toggleTheme(consoleCm);
}

/** 执行：优先选区，否则全部文本；走只读即席查询接口，多结果集分区展示。 */
function runSql() {
    if (!consoleCm) return;
    const selected = consoleCm.getSelection();
    const sql = (selected && selected.trim()) ? selected : consoleCm.getValue();
    if (!sql.trim()) { App.toast(I18N.t('console.emptySql'), 'warning'); return; }
    const connKey = $('#connSelect').val();
    if (!connKey) { App.toast(I18N.t('console.connRequired'), 'warning'); return; }
    const maxRows = Math.min(Math.max(parseInt($('#maxRowsInput').val(), 10) || 200, 1), 1000);
    const $btn = $('#btnRun').prop('disabled', true);
    App.api('POST', '/api/connections/key/' + encodeURIComponent(connKey) + '/query',
            { sql: sql, maxRows: maxRows })
        .done(function (results) {
            pushHistory(connKey, sql);
            showResults(connKey, results || []);
        })
        .always(function () { $btn.prop('disabled', false); });
}

function showResults(connKey, results) {
    const $box = $('#consoleResults').empty();
    if (!results.length) {
        $box.html('<div class="text-muted small">' + App.escapeHtml(I18N.t('console.noResults')) + '</div>');
        return;
    }
    $box.append('<div class="text-muted small mb-1">' +
        App.escapeHtml(I18N.t('console.summary', { n: results.length })) +
        ' @ <span class="mono">' + App.escapeHtml(connKey) + '</span></div>');
    results.forEach(function (r, i) {
        const single = results.length === 1;
        $box.append(
            '<div class="mb-3"><div class="fw-bold small mb-1">' +
            (single ? '' : App.escapeHtml(I18N.t('console.statement', { n: i + 1 })) + ' · ') +
            r.rowCount + ' rows' + (r.truncated ? ' ▂' : '') + '</div>' +
            '<div id="consoleGrid' + i + '"></div></div>');
        App.resultGrid('#consoleGrid' + i, r, { maxHeight: '360px' });
    });
}

$(function () {
    App.bindOperator();
    renderHistory();
    consoleCm = SqlEditor.attach(document.getElementById('consoleSql'), { exec: runSql });
    App.api('GET', '/api/connections').then(function (conns) {
        connections = Array.isArray(conns) ? conns : [];
        const last = localStorage.getItem(CONN_KEY);
        $('#connSelect').html(connOptions(last)).val(last || $('#connSelect').val());
        if (!connections.filter(c => c.enabled === 1).length) {
            $('#connSelect').html('<option value=""></option>');
        }
        loadConsoleHints($('#connSelect').val());
    });
    $('#connSelect').on('change', function () {
        localStorage.setItem(CONN_KEY, this.value || '');
        loadConsoleHints(this.value);
    });
});
