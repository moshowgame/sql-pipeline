let definitions = [];
let connections = [];
let notifyChannels = [];
let runsState = { defId: null, page: 1, size: 10, total: 0 };

/** 常用 Cron 示例（Spring 6 段：秒 分 时 日 月 周），点击应用到输入框。 */
const CRON_SAMPLES = [
    { key: 'hc.cron.s.everyMinute', expr: '0 * * * * *' },
    { key: 'hc.cron.s.every5Min', expr: '0 */5 * * * *' },
    { key: 'hc.cron.s.every15Min', expr: '0 */15 * * * *' },
    { key: 'hc.cron.s.hourly', expr: '0 0 * * * *' },
    { key: 'hc.cron.s.daily2am', expr: '0 0 2 * * *' },
    { key: 'hc.cron.s.daily9am', expr: '0 0 9 * * *' },
    { key: 'hc.cron.s.workday9am', expr: '0 0 9 * * MON-FRI' },
    { key: 'hc.cron.s.monday9am', expr: '0 0 9 * * MON' },
    { key: 'hc.cron.s.monthly1st', expr: '0 30 0 1 * *' },
    { key: 'hc.cron.s.lastDay23pm', expr: '0 0 23 L * *' }
];

function renderCronSamples() {
    const html = CRON_SAMPLES.map(function (s) {
        return '<button type="button" class="btn btn-outline-secondary btn-sm" title="' + App.escapeHtml(s.expr) + '"' +
            ' onclick="applyCronSample(\'' + s.expr + '\')">' + App.escapeHtml(I18N.t(s.key)) + '</button>';
    }).join('');
    $('#cronSamples').html(html);
}

function applyCronSample(expr) {
    $('#dCron').val(expr);
}

$(function () {
    App.bindOperator();
    renderCronSamples();
    App.api('GET', '/api/notify/channels').then(function (chs) {
        notifyChannels = Array.isArray(chs) ? chs.filter(c => c.enabled === 1) : [];
    }).fail(function () { notifyChannels = []; });
    $.when(App.api('GET', '/api/health-checks'), App.api('GET', '/api/connections'))
        .done(function (defs, conns) {
            // App.api 的 Promise 已解包为 data 本体（数组），无需再取 [0]
            definitions = Array.isArray(defs) ? defs : [];
            connections = Array.isArray(conns) ? conns : [];
            render();
            fillNotifyChannelOptions();
        }).fail(function () {
            $('#defTable').html('<tr><td colspan="11" class="text-center text-muted py-4">' + App.escapeHtml(I18N.t('common.loadFailed')) + '</td></tr>');
        });
});

/** 弹窗内的告警频道单选下拉（无绑定 = 不告警）。 */
function fillNotifyChannelOptions(selectedId) {
    const opts = ['<option value=""' + (!selectedId ? ' selected' : '') + '>' + App.escapeHtml(I18N.t('hc.notifyChannel.none')) + '</option>']
        .concat(notifyChannels.map(c =>
            '<option value="' + c.id + '"' + (c.id === selectedId ? ' selected' : '') + '>' +
            App.escapeHtml(c.name + (c.type ? ' (' + c.type + ')' : '')) + '</option>'));
    $('#dNotifyChannel').html(opts.join(''));
}

function render() {
    if (!definitions.length) {
        $('#defTable').html('<tr><td colspan="10" class="text-center text-muted py-4">' + App.escapeHtml(I18N.t('hc.empty')) + '</td></tr>');
        return;
    }
    const rows = definitions.map(function (d) {
        const enabled = d.enabled === 1;
        const assertText = d.assertType
            ? App.badge(d.assertType + ' ' + d.assertOp + ' ' + d.assertValue, 'info')
            : '<span class="text-muted">-</span>';
        const alertsText = d.notifyChannelId
            ? App.badge(d.notifyChannelName || ('#' + d.notifyChannelId), 'info')
            : '<span class="text-muted fw-bold">NO</span>';
        const connLabel = d.connKey ? '<span class="text-muted small" title="connKey">' + App.escapeHtml(d.connKey) + '</span><br>' : '';
        return '<tr data-id="' + d.id + '">' +
            '<td>' + d.id + '</td>' +
            '<td class="fw-bold">' + App.escapeHtml(d.name) +
            (d.notifyChannelName ? '' : '') + '</td>' +
            '<td>' + connLabel + '<span class="sql-cell d-inline-block" style="max-width:200px" title="' + App.escapeHtml(d.sqlText) + '">' + App.escapeHtml(d.sqlText) + '</span></td>' +
            '<td>' + assertText + '</td>' +
            '<td>' + alertsText + '</td>' +
            '<td class="mono">' + App.escapeHtml(d.cronExpr || '-') + '</td>' +
            '<td class="mono">' + d.timeoutSec + 's</td>' +
            '<td>' + (enabled ? App.badge(I18N.t('common.enabled'), 'success') : App.badge(I18N.t('common.disabled'), 'secondary')) + '</td>' +
            '<td><div class="btn-group btn-group-sm" role="group">' +
            '<button class="btn btn-success" title="' + App.escapeHtml(I18N.t('hc.runModal.exec')) + '" onclick="runDef(' + d.id + ')">▶ ' + App.escapeHtml(I18N.t('hc.runModal.exec')) + '</button>' +
            '<button class="btn btn-outline-info hc-icon-btn" title="' + App.escapeHtml(I18N.t('hc.runs.title')) + '" onclick="openRuns(' + d.id + ')">📊</button>' +
            '<button class="btn btn-outline-secondary hc-icon-btn" title="' + App.escapeHtml(I18N.t('hc.history.title')) + '" onclick="openHistory(' + d.id + ')">🕘</button>' +
            '<button class="btn btn-outline-primary hc-icon-btn" title="' + App.escapeHtml(I18N.t('common.edit')) + '" onclick="openEdit(' + d.id + ')">✏️</button>' +
            (enabled
                ? '<button class="btn btn-outline-warning hc-icon-btn" title="' + App.escapeHtml(I18N.t('common.disable')) + '" onclick="toggleDef(' + d.id + ', 0)">⏻</button>'
                : '<button class="btn btn-outline-success hc-icon-btn" title="' + App.escapeHtml(I18N.t('common.enable')) + '" onclick="toggleDef(' + d.id + ', 1)">⏻</button>') +
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

function openCreate() {
    $('#defModalTitle').text(I18N.t('hc.modal.create'));
    $('#defForm')[0].reset();
    $('#defId').val('');
    $('#dConnKey').html(connOptions());
    $('#dTimeout').val(30); $('#dEnabled').val(1);
    $('#dParams').val('{}');
    $('#dAssertType').val(''); $('#dAssertOp').val('=='); $('#dAssertValue').val('');
    fillNotifyChannelOptions(null);
    $('#dCron').val('');
    new bootstrap.Modal('#defModal').show();
}

function openEdit(id) {
    const d = definitions.find(x => x.id === id);
    if (!d) return;
    $('#defModalTitle').text(I18N.t('hc.modal.edit', { id: id }));
    $('#defId').val(d.id);
    $('#dName').val(d.name);
    $('#dConnKey').html(connOptions(d.connKey));
    $('#dSql').val(d.sqlText);
    $('#dParams').val(App.prettyJson(d.paramsJson));
    $('#dAssertType').val(d.assertType || '');
    $('#dAssertOp').val(d.assertOp || '==');
    $('#dAssertValue').val(d.assertValue === null || d.assertValue === undefined ? '' : d.assertValue);
    fillNotifyChannelOptions(d.notifyChannelId);
    $('#dCron').val(d.cronExpr || '');
    $('#dTimeout').val(d.timeoutSec);
    $('#dEnabled').val(String(d.enabled));
    new bootstrap.Modal('#defModal').show();
}

function saveDef() {
    const id = $('#defId').val();
    const body = {
        name: $('#dName').val().trim(),
        connKey: $('#dConnKey').val(),
        sqlText: $('#dSql').val(),
        paramsJson: $('#dParams').val().trim() || null,
        assertType: $('#dAssertType').val() || null,
        assertOp: $('#dAssertOp').val(),
        assertValue: $('#dAssertValue').val() === '' ? null : parseFloat($('#dAssertValue').val()),
        notifyChannelId: $('#dNotifyChannel').val() ? parseInt($('#dNotifyChannel').val(), 10) : null,
        cronExpr: $('#dCron').val().trim() || null,
        timeoutSec: parseInt($('#dTimeout').val(), 10),
        enabled: parseInt($('#dEnabled').val(), 10)
    };
    if (!body.name || !body.connKey || !body.sqlText) {
        App.toast(I18N.t('hc.toast.nameRequired'), 'danger');
        return;
    }
    if (body.paramsJson) {
        let params;
        try { params = JSON.parse(body.paramsJson); }
        catch (e) { App.toast(I18N.t('hc.toast.paramsInvalidJson', { msg: e.message }), 'danger'); return; }
        if (Array.isArray(params) || typeof params !== 'object' || params === null) {
            App.toast(I18N.t('hc.toast.paramsMustBeObject'), 'danger');
            return;
        }
    }
    if (body.assertType && (body.assertValue === null || isNaN(body.assertValue))) {
        App.toast(I18N.t('hc.toast.assertValueRequired'), 'danger');
        return;
    }
    const req = id ? App.api('PUT', '/api/health-checks/' + id, body)
        : App.api('POST', '/api/health-checks', body);
    req.then(function () {
        App.toast(id ? I18N.t('hc.toast.saveUpdated') : I18N.t('hc.toast.saveCreated'));
        bootstrap.Modal.getInstance($('#defModal')[0]).hide();
        loadListQuietly();
    });
}

function runDef(id) {
    const d = definitions.find(x => x.id === id);
    $('#runParamsTitle').text('#' + id + ' ' + (d ? d.name : ''));
    $('#runParamsInput').val((d && d.paramsJson) ? App.prettyJson(d.paramsJson) : '{}');
    $('#runParamsInput').data('defId', id);
    new bootstrap.Modal('#runParamsModal').show();
}

function doRun() {
    const id = $('#runParamsInput').data('defId');
    const raw = $('#runParamsInput').val().trim();
    let params = {};
    if (raw) {
        try { params = JSON.parse(raw); }
        catch (e) { App.toast(I18N.t('hc.toast.overrideInvalidJson', { msg: e.message }), 'danger'); return; }
        if (Array.isArray(params) || typeof params !== 'object' || params === null) {
            App.toast(I18N.t('hc.toast.overrideMustBeObject'), 'danger');
            return;
        }
    }
    App.api('POST', '/api/health-checks/' + id + '/run', { params: params }).then(function (run) {
        bootstrap.Modal.getInstance($('#runParamsModal')[0]).hide();
        showRunResult(run);
    });
}

function showRunResult(run) {
    const failed = run.status !== 'SUCCESS';
    $('#runModalBadge').html(' ' + App.runBadge(run.status));
    $('#runResultBody').html(
        '<div class="row g-2 mb-2">' +
        '<div class="col-md-3"><span class="kv-label">' + I18N.t('common.version') + '</span><span class="mono">v' + run.version + '</span></div>' +
        '<div class="col-md-3"><span class="kv-label">' + I18N.t('hc.result.rows') + '</span><span class="mono">' + run.rowCount + '</span></div>' +
        '<div class="col-md-3"><span class="kv-label">' + I18N.t('common.duration') + '</span><span class="mono">' + App.fmtMs(run.durationMs) + '</span></div>' +
        '<div class="col-md-3"><span class="kv-label">' + I18N.t('hc.result.trigger') + '</span><span class="mono">' + run.triggerType + '</span></div>' +
        '</div>' +
        (run.assertMsg ? '<div class="alert ' + (failed ? 'alert-danger' : 'alert-success') + ' py-2">' + I18N.t('hc.result.assert') + App.escapeHtml(run.assertMsg) + '</div>' : '') +
        (run.errorMsg ? '<div class="alert alert-danger py-2 sql-text">' + I18N.t('hc.result.error') + App.escapeHtml(run.errorMsg) + '</div>' : '') +
        '<div class="text-muted small mb-1">' + I18N.t('hc.result.preview') + '</div>' +
        '<pre class="border rounded bg-light p-2 sql-text" style="max-height:320px;overflow:auto">' + App.escapeHtml(App.prettyJson(run.resultHead)) + '</pre>');
    new bootstrap.Modal('#runModal').show();
    loadListQuietly();
}

function loadListQuietly() {
    App.api('GET', '/api/health-checks').then(function (defs) { definitions = Array.isArray(defs) ? defs : []; render(); });
}

function openRuns(id) {
    const d = definitions.find(x => x.id === id);
    runsState = { defId: id, page: 1, size: 10, total: 0 };
    $('#runsModalTitle').text('#' + id + ' ' + (d ? d.name : ''));
    $('#runsTable').html('<tr><td colspan="7" class="text-center text-muted py-3">' + App.escapeHtml(I18N.t('common.loading')) + '</td></tr>');
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
        $('#runsTable').html(rows.length ? rows.join('') :
            '<tr><td colspan="7" class="text-center text-muted py-3">' + App.escapeHtml(I18N.t('hc.runs.empty')) + '</td></tr>');
        const maxPage = Math.max(1, Math.ceil(s.total / s.size));
        $('#runsPagerInfo').text(I18N.t('hc.runs.pager', { total: s.total, page: s.page, max: maxPage }));
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
    $('#historyTable').html('<tr><td colspan="7" class="text-center text-muted py-3">' + App.escapeHtml(I18N.t('common.loading')) + '</td></tr>');
    App.api('GET', '/api/health-checks/' + id + '/history').then(function (list) {
        const rows = (list || []).map(function (h) {
            return '<tr><td class="mono">v' + h.version + '</td>' +
                '<td>' + App.badge(h.changeType, h.changeType === 'CREATE' ? 'success' : h.changeType === 'DISABLE' ? 'secondary' : 'info') + '</td>' +
                '<td class="sql-cell" title="' + App.escapeHtml(h.sqlText || '') + '">' + App.escapeHtml(h.sqlText || '-') + '</td>' +
                '<td>' + (h.assertType ? App.badge(h.assertType + ' ' + h.assertOp + ' ' + h.assertValue, 'info') : '-') + '</td>' +
                '<td class="mono">' + App.escapeHtml(h.cronExpr || '-') + '</td>' +
                '<td>' + App.escapeHtml(h.changedBy || '-') + '</td>' +
                '<td class="mono">' + App.fmtTime(h.changedAt) + '</td></tr>';
        });
        $('#historyTable').html(rows.length ? rows.join('') :
            '<tr><td colspan="7" class="text-center text-muted py-3">' + App.escapeHtml(I18N.t('hc.history.empty')) + '</td></tr>');
    });
    new bootstrap.Modal('#historyModal').show();
}

function toggleDef(id, enabled) {
    const req = enabled === 1
        ? App.api('POST', '/api/health-checks/' + id + '/enable')
        : App.api('POST', '/api/health-checks/' + id + '/disable');
    req.then(function () {
        App.toast(enabled === 1 ? I18N.t('hc.toast.enabledOn') : I18N.t('hc.toast.enabledOff'));
        loadListQuietly();
    });
}
