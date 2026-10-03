let definitions = [];
let connections = [];
let runsState = { defId: null, page: 1, size: 10, total: 0 };

const ASSERT_TEMPLATES = {
    VALUE: '{"type":"VALUE","expr":"cell(0,0)","op":"==","value":1}',
    ROWCOUNT: '{"type":"ROWCOUNT","op":">=","value":1}',
    RECORD: '{"type":"RECORD","mode":"ALL","rules":[{"field":"status","op":"not_null"},{"field":"type","op":"in","value":["A","B"]}]}'
};

$(function () {
    App.bindOperator();
    $.when(App.api('GET', '/api/health-checks'), App.api('GET', '/api/connections'))
        .done(function (defs, conns) {
            // App.api 的 Promise 已解包为 data 本体（数组），无需再取 [0]
            definitions = Array.isArray(defs) ? defs : [];
            connections = Array.isArray(conns) ? conns : [];
            render();
        }).fail(function () {
            $('#defTable').html('<tr><td colspan="10" class="text-center text-muted py-4">加载失败</td></tr>');
        });
});

function render() {
    if (!definitions.length) {
        $('#defTable').html('<tr><td colspan="10" class="text-center text-muted py-4">暂无定义，点击右上角「新建健康检查」</td></tr>');
        return;
    }
    const rows = definitions.map(function (d) {
        const enabled = d.enabled === 1;
        return '<tr data-id="' + d.id + '">' +
            '<td>' + d.id + '</td>' +
            '<td class="fw-bold">' + App.escapeHtml(d.name) + '</td>' +
            '<td class="mono">' + App.escapeHtml(d.connKey) + '</td>' +
            '<td class="sql-cell" title="' + App.escapeHtml(d.sqlText) + '">' + App.escapeHtml(d.sqlText) + '</td>' +
            '<td>' + (d.assertType ? App.badge(d.assertType, 'info') : '<span class="text-muted">-</span>') + '</td>' +
            '<td class="mono">' + App.escapeHtml(d.cronExpr || '-') + '</td>' +
            '<td class="mono">' + d.timeoutSec + 's</td>' +
            '<td class="mono">v' + d.version + '</td>' +
            '<td>' + (enabled ? App.badge('启用', 'success') : App.badge('停用', 'secondary')) + '</td>' +
            '<td><div class="btn-group btn-group-sm" role="group">' +
            '<button class="btn btn-success" onclick="runDef(' + d.id + ')">执行</button>' +
            '<button class="btn btn-outline-info" onclick="openRuns(' + d.id + ')">记录</button>' +
            '<button class="btn btn-outline-secondary" onclick="openHistory(' + d.id + ')">历史</button>' +
            '<button class="btn btn-outline-primary" onclick="openEdit(' + d.id + ')">编辑</button>' +
            (enabled
                ? '<button class="btn btn-outline-warning" onclick="toggleDef(' + d.id + ', 0)">停用</button>'
                : '<button class="btn btn-outline-success" onclick="toggleDef(' + d.id + ', 1)">启用</button>') +
            '</div></td></tr>';
    });
    $('#defTable').html(rows.join(''));
}

function connOptions(selected) {
    return connections.filter(c => c.enabled === 1).map(c =>
        '<option value="' + App.escapeHtml(c.connKey) + '"' +
        (c.connKey === selected ? ' selected' : '') + '>' +
        App.escapeHtml(c.connKey + '（' + c.displayName + '）') + '</option>').join('');
}

function renderAssertTemplates(type) {
    if (!type || !ASSERT_TEMPLATES[type]) {
        $('#assertTemplates').html('');
        return;
    }
    $('#assertTemplates').html(
        '<button type="button" class="btn btn-outline-info btn-sm me-1" onclick=\'fillTemplate("' + type + '")\'>填入 ' + type + ' 示例</button>' +
        '<span class="text-muted small">' + ({
            VALUE: '对单个单元格或行数做比较',
            ROWCOUNT: '对返回行数做比较',
            RECORD: '对每行/任一行的字段做规则校验'
        })[type] + '</span>');
}

function fillTemplate(type) {
    $('#dAssertConfig').val(ASSERT_TEMPLATES[type]);
}

function openCreate() {
    $('#defModalTitle').text('新建健康检查');
    $('#defForm')[0].reset();
    $('#defId').val('');
    $('#dConnKey').html(connOptions());
    $('#dTimeout').val(30); $('#dEnabled').val(1);
    $('#dAssertType').val(''); renderAssertTemplates('');
    new bootstrap.Modal('#defModal').show();
}

function openEdit(id) {
    const d = definitions.find(x => x.id === id);
    if (!d) return;
    $('#defModalTitle').text('编辑健康检查 #' + id + '（保存后版本 +1）');
    $('#defId').val(d.id);
    $('#dName').val(d.name);
    $('#dConnKey').html(connOptions(d.connKey));
    $('#dSql').val(d.sqlText);
    $('#dParams').val(App.prettyJson(d.paramsJson));
    $('#dAssertType').val(d.assertType || '');
    $('#dAssertConfig').val(App.prettyJson(d.assertConfig));
    $('#dCron').val(d.cronExpr || '');
    $('#dTimeout').val(d.timeoutSec);
    $('#dEnabled').val(String(d.enabled));
    renderAssertTemplates(d.assertType);
    new bootstrap.Modal('#defModal').show();
}

$('#dAssertType').on('change', function () { renderAssertTemplates($(this).val()); });

function saveDef() {
    const id = $('#defId').val();
    const body = {
        name: $('#dName').val().trim(),
        connKey: $('#dConnKey').val(),
        sqlText: $('#dSql').val(),
        paramsJson: $('#dParams').val().trim() || null,
        assertType: $('#dAssertType').val() || null,
        assertConfig: $('#dAssertConfig').val().trim() || null,
        cronExpr: $('#dCron').val().trim() || null,
        timeoutSec: parseInt($('#dTimeout').val(), 10),
        enabled: parseInt($('#dEnabled').val(), 10)
    };
    if (!body.name || !body.connKey || !body.sqlText) {
        App.toast('请填写名称、连接与 SQL', 'danger');
        return;
    }
    if (body.paramsJson) { try { JSON.parse(body.paramsJson); } catch (e) { App.toast('默认参数不是合法 JSON：' + e.message, 'danger'); return; } }
    if (body.assertConfig) { try { JSON.parse(body.assertConfig); } catch (e) { App.toast('断言配置不是合法 JSON：' + e.message, 'danger'); return; } }
    const req = id ? App.api('PUT', '/api/health-checks/' + id, body)
        : App.api('POST', '/api/health-checks', body);
    req.then(function () {
        App.toast('已保存' + (id ? '（版本 +1，调度已更新）' : '，调度已注册'));
        bootstrap.Modal.getInstance($('#defModal')[0]).hide();
        setTimeout(function () { location.reload(); }, 600);
    });
}

function runDef(id) {
    App.api('POST', '/api/health-checks/' + id + '/run', {}).then(function (run) {
        const failed = run.status !== 'SUCCESS';
        $('#runModalBadge').html(' ' + App.runBadge(run.status));
        $('#runResultBody').html(
            '<div class="row g-2 mb-2">' +
            '<div class="col-md-3"><span class="kv-label">版本</span><span class="mono">v' + run.version + '</span></div>' +
            '<div class="col-md-3"><span class="kv-label">行数</span><span class="mono">' + run.rowCount + '</span></div>' +
            '<div class="col-md-3"><span class="kv-label">耗时</span><span class="mono">' + App.fmtMs(run.durationMs) + '</span></div>' +
            '<div class="col-md-3"><span class="kv-label">触发</span><span class="mono">' + run.triggerType + '</span></div>' +
            '</div>' +
            (run.assertMsg ? '<div class="alert ' + (failed ? 'alert-danger' : 'alert-success') + ' py-2">断言：' + App.escapeHtml(run.assertMsg) + '</div>' : '') +
            (run.errorMsg ? '<div class="alert alert-danger py-2 sql-text">错误：' + App.escapeHtml(run.errorMsg) + '</div>' : '') +
            '<div class="text-muted small mb-1">结果预览（前 20 行）</div>' +
            '<pre class="border rounded bg-light p-2 sql-text" style="max-height:320px;overflow:auto">' + App.escapeHtml(App.prettyJson(run.resultHead)) + '</pre>');
        new bootstrap.Modal('#runModal').show();
        loadListQuietly();
    });
}

function loadListQuietly() {
    App.api('GET', '/api/health-checks').then(function (defs) { definitions = defs || []; render(); });
}

function openRuns(id) {
    const d = definitions.find(x => x.id === id);
    runsState = { defId: id, page: 1, size: 10, total: 0 };
    $('#runsModalTitle').text('#' + id + ' ' + (d ? d.name : ''));
    $('#runsTable').html('<tr><td colspan="7" class="text-center text-muted py-3">加载中…</td></tr>');
    new bootstrap.Modal('#runsModal').show();
    loadRuns();
}

function loadRuns() {
    const s = runsState;
    App.api('GET', '/api/health-checks/' + s.defId + '/runs?page=' + s.page + '&size=' + s.size).then(function (pr) {
        s.total = pr.total;
        const rows = pr.items.map(function (r) {
            return '<tr><td class="mono">' + App.fmtTime(r.startedAt) + '</td>' +
                '<td>' + App.badge(r.triggerType, 'info') + '</td>' +
                '<td>' + App.runBadge(r.status) + '</td>' +
                '<td class="mono">' + (r.rowCount === null ? '-' : r.rowCount) + '</td>' +
                '<td class="mono">' + App.fmtMs(r.durationMs) + '</td>' +
                '<td class="sql-cell" title="' + App.escapeHtml(r.assertMsg || '') + '">' + App.escapeHtml(r.assertMsg || '-') + '</td>' +
                '<td class="sql-cell" title="' + App.escapeHtml(r.errorMsg || '') + '">' + App.escapeHtml(r.errorMsg || '-') + '</td></tr>';
        });
        $('#runsTable').html(rows.length ? rows.join('') : '<tr><td colspan="7" class="text-center text-muted py-3">暂无执行记录</td></tr>');
        const maxPage = Math.max(1, Math.ceil(s.total / s.size));
        $('#runsPagerInfo').text('共 ' + s.total + ' 条，第 ' + s.page + ' / ' + maxPage + ' 页');
    });
}

function runsPage(delta) {
    const s = runsState;
    const maxPage = Math.max(1, Math.ceil(s.total / s.size));
    const next = s.page + delta;
    if (next < 1 || next > maxPage) return;
    s.page = next;
    loadRuns();
}

function openHistory(id) {
    const d = definitions.find(x => x.id === id);
    $('#historyModalTitle').text('#' + id + ' ' + (d ? d.name : ''));
    $('#historyTable').html('<tr><td colspan="7" class="text-center text-muted py-3">加载中…</td></tr>');
    App.api('GET', '/api/health-checks/' + id + '/history').then(function (list) {
        const rows = (list || []).map(function (h) {
            return '<tr><td class="mono">v' + h.version + '</td>' +
                '<td>' + App.badge(h.changeType, h.changeType === 'CREATE' ? 'success' : h.changeType === 'DISABLE' ? 'secondary' : 'info') + '</td>' +
                '<td class="sql-cell" title="' + App.escapeHtml(h.sqlText || '') + '">' + App.escapeHtml(h.sqlText || '-') + '</td>' +
                '<td>' + (h.assertType ? App.badge(h.assertType, 'info') : '-') + '</td>' +
                '<td class="mono">' + App.escapeHtml(h.cronExpr || '-') + '</td>' +
                '<td>' + App.escapeHtml(h.changedBy || '-') + '</td>' +
                '<td class="mono">' + App.fmtTime(h.changedAt) + '</td></tr>';
        });
        $('#historyTable').html(rows.length ? rows.join('') : '<tr><td colspan="7" class="text-center text-muted py-3">暂无修改记录</td></tr>');
    });
    new bootstrap.Modal('#historyModal').show();
}

function toggleDef(id, enabled) {
    const req = enabled === 1
        ? App.api('POST', '/api/health-checks/' + id + '/enable')
        : App.api('POST', '/api/health-checks/' + id + '/disable');
    req.then(function () {
        App.toast('已' + (enabled === 1 ? '启用（调度已注册）' : '停用（调度已取消）'));
        loadListQuietly();
    });
}
