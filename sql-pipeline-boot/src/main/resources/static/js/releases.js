let plans = [];
let connections = [];
let currentPlan = null;
let currentPlanId = null;
let currentSteps = [];
let sseSource = null;
let sseDebounce = null;

$(function () {
    App.bindOperator();
    $.when(App.api('GET', '/api/releases'), App.api('GET', '/api/connections'))
        .done(function (p, c) {
            // App.api 的 Promise 已解包为 data 本体（数组），无需再取 [0]
            plans = Array.isArray(p) ? p : [];
            connections = Array.isArray(c) ? c : [];
            renderPlans();
        }).fail(function () {
            $('#planTable').html('<tr><td colspan="9" class="text-center text-muted py-4">' + App.escapeHtml(I18N.t('common.loadFailed')) + '</td></tr>');
        });
});

// ---------- 计划列表 ----------

function renderPlans() {
    if (!plans.length) {
        $('#planTable').html('<tr><td colspan="9" class="text-center text-muted py-4">' + App.escapeHtml(I18N.t('rel.empty')) + '</td></tr>');
        return;
    }
    const rows = plans.map(function (p) {
        return '<tr data-id="' + p.id + '" style="cursor:pointer" onclick="loadDetail(' + p.id + ')">' +
            '<td>' + p.id + '</td>' +
            '<td class="mono fw-bold">' + App.escapeHtml(p.planName) + '</td>' +
            '<td>' + App.planBadge(p.status) + '</td>' +
            '<td class="mono">' + App.escapeHtml(p.crNumber || '-') + '</td>' +
            '<td>' + App.escapeHtml(p.operator || '-') + '</td>' +
            '<td class="mono">' + App.fmtTime(p.startedAt) + '</td>' +
            '<td class="mono">' + App.fmtTime(p.finishedAt) + '</td>' +
            '<td class="mono">' + (p.rerunCount || 0) + '</td>' +
            '<td><button class="btn btn-outline-primary btn-sm" onclick="event.stopPropagation();loadDetail(' + p.id + ')">' + I18N.t('rel.details') + '</button></td>' +
            '</tr>';
    });
    $('#planTable').html(rows.join(''));
}

// ---------- 计划详情 ----------

function loadDetail(planId, keepScroll) {
    const scrollTop = keepScroll ? $(window).scrollTop() : 0;
    App.api('GET', '/api/releases/' + planId).then(function (detail) {
        currentPlan = detail.plan;
        currentPlanId = detail.plan.id;
        currentSteps = detail.steps || [];
        renderDetail(detail);
        $('#detailCard').removeClass('d-none');
        subscribeSse(planId);
        if (keepScroll) $(window).scrollTop(scrollTop);
    });
}

function renderDetail(detail) {
    const p = detail.plan;
    $('#detailTitle').text('#' + p.id + ' ' + p.planName);
    $('#detailStatus').html(App.planBadge(p.status));

    $('#planInfo').html([
        infoCell(I18N.t('rel.info.cr'), p.crNumber || '-'),
        infoCell(I18N.t('rel.info.remark'), p.remark || '-'),
        infoCell(I18N.t('rel.info.operator'), p.operator || '-'),
        infoCell(I18N.t('rel.info.defaultConn'), p.defaultConnKey || '-'),
        infoCell(I18N.t('rel.info.dir'), (p.basePath || '') + '/' + p.planName),
        infoCell(I18N.t('rel.info.rerunCount'), String(p.rerunCount || 0))
    ].join(''));

    const st = p.status;
    $('#btnStart').prop('disabled', st !== 'DRAFT');
    $('#btnContinue').prop('disabled', st !== 'WAITING');
    $('#btnRerun').prop('disabled', st === 'RUNNING');

    const rows = currentSteps.map(function (s) {
        const actions = [];
        if (s.status === 'FAIL') actions.push('<button class="btn btn-outline-danger btn-sm" onclick="doRetry(' + s.stepNo + ')">' + I18N.t('rel.steps.retry') + '</button>');
        if (s.status === 'WAITING_CONTINUE') actions.push('<span class="text-warning fw-bold">' + App.escapeHtml(I18N.t('rel.steps.waiting')) + '</span>');
        if (s.status === 'RUNNING') actions.push('<span class="text-primary">' + App.escapeHtml(I18N.t('rel.steps.running')) + '</span>');
        if (!actions.length) actions.push('<span class="text-muted">-</span>');
        return '<tr>' +
            '<td class="fw-bold">' + s.stepNo + '</td>' +
            '<td class="mono sql-cell" title="' + App.escapeHtml(s.dirPath) + '">' + App.escapeHtml(s.dirPath) + '</td>' +
            '<td class="mono">' + App.escapeHtml(s.connKey || '-') + '</td>' +
            '<td>' + App.badge(s.afterMode, s.afterMode === 'WAIT' ? 'warning' : 'info') + '</td>' +
            '<td>' + (s.executor ? App.escapeHtml(s.executor) : '<span class="text-muted">' + I18N.t('common.unlimited') + '</span>') + '</td>' +
            '<td>' + App.stepBadge(s.status) + '</td>' +
            '<td class="mono">' + (s.retryCount || 0) + '</td>' +
            '<td class="mono">' + App.fmtMs(s.durationMs) + '</td>' +
            '<td class="sql-cell" style="max-width:200px" title="' + App.escapeHtml(s.errorMsg || '') + '">' + App.escapeHtml(s.errorMsg || '-') + '</td>' +
            '<td>' + actions.join(' ') + '</td></tr>';
    });
    $('#stepTable').html(rows.length ? rows.join('') :
        '<tr><td colspan="10" class="text-center text-muted py-3">' + App.escapeHtml(I18N.t('rel.steps.none')) + '</td></tr>');
}

function infoCell(label, value) {
    return '<div class="col-md-4 col-lg-2"><div class="text-muted small">' + App.escapeHtml(label) + '</div>' +
        '<div class="mono text-truncate" title="' + App.escapeHtml(value) + '">' + App.escapeHtml(value) + '</div></div>';
}

// ---------- SSE 实时刷新 ----------

function subscribeSse(planId) {
    if (sseSource) { sseSource.close(); sseSource = null; }
    sseSource = new EventSource('/api/releases/' + planId + '/stream');
    sseSource.addEventListener('hello', function () {
        $('#sseBadge').html('<span class="badge bg-success">' + App.escapeHtml(I18N.t('rel.sse.connected')) + '</span>');
    });
    const refresh = function () {
        clearTimeout(sseDebounce);
        sseDebounce = setTimeout(function () {
            if (currentPlanId) loadDetail(currentPlanId, true);
            App.api('GET', '/api/releases').then(function (list) {
                plans = Array.isArray(list) ? list : [];
                renderPlans();
            });
        }, 300);
    };
    sseSource.addEventListener('step', refresh);
    sseSource.addEventListener('plan', refresh);
    sseSource.onerror = function () {
        $('#sseBadge').html('<span class="badge bg-secondary">' + App.escapeHtml(I18N.t('rel.sse.offline')) + '</span>');
    };
}

$(window).on('unload', function () { if (sseSource) sseSource.close(); });

// ---------- 创建计划 ----------

function connSelectOptions(selected) {
    return connections.filter(c => c.enabled === 1).map(c =>
        '<option value="' + App.escapeHtml(c.connKey) + '"' + (c.connKey === selected ? ' selected' : '') + '>' +
        App.escapeHtml(c.connKey + '（' + c.displayName + '）') + '</option>').join('');
}

function openCreate() {
    $('#createModal input, #createModal select').not('#cDefaultConn').val('');
    $('#scanPreview').empty();
    $('#stepConfigRows').empty();
    $('#cDefaultConn').html(connSelectOptions());
    addStepRow();
    new bootstrap.Modal('#createModal').show();
}

function addStepRow(no, connKey, afterMode, executor) {
    const tr = '<tr>' +
        '<td><input class="form-control form-control-sm sc-no" type="number" min="1" max="99" value="' + (no || '') + '"></td>' +
        '<td><select class="form-select form-select-sm sc-conn"><option value="">' + App.escapeHtml(I18N.t('rel.createModal.defaultConnOption')) + '</option>' +
        connSelectOptions(connKey) + '</select></td>' +
        '<td><select class="form-select form-select-sm sc-mode">' +
        '<option value="CONTINUE"' + (afterMode !== 'WAIT' ? ' selected' : '') + '>CONTINUE</option>' +
        '<option value="WAIT"' + (afterMode === 'WAIT' ? ' selected' : '') + '>WAIT</option></select></td>' +
        '<td><input class="form-control form-control-sm sc-executor" value="' + App.escapeHtml(executor || '') + '" placeholder="' + App.escapeHtml(I18N.t('rel.createModal.executorPlaceholder')) + '"></td>' +
        '<td><button class="btn btn-outline-danger btn-sm" onclick="$(this).closest(\'tr\').remove()">×</button></td></tr>';
    $('#stepConfigRows').append(tr);
}

function collectStepConfigs() {
    const configs = [];
    $('#stepConfigRows tr').each(function () {
        const no = parseInt($(this).find('.sc-no').val(), 10);
        if (!no || no < 1) return;
        configs.push({
            stepNo: no,
            connKey: $(this).find('.sc-conn').val() || null,
            afterMode: $(this).find('.sc-mode').val(),
            executor: $(this).find('.sc-executor').val().trim() || null
        });
    });
    return configs;
}

function doScan() {
    const planName = $('#cPlanName').val().trim();
    if (!planName) { App.toast(I18N.t('rel.toast.planNameRequired'), 'danger'); return; }
    const body = { planName: planName, defaultConnKey: $('#cDefaultConn').val() || null, steps: collectStepConfigs() };
    App.api('POST', '/api/releases/scan', body).then(function (list) {
        if (!list.length) {
            $('#scanPreview').html('<div class="alert alert-warning py-2 mb-0">' + App.escapeHtml(I18N.t('rel.scan.empty')) + '</div>');
            return;
        }
        const rows = list.map(function (s) {
            return '<tr><td class="fw-bold">' + s.stepNo + '</td>' +
                '<td class="mono">' + App.escapeHtml(s.connKey || I18N.t('rel.scan.unset')) + '</td>' +
                '<td>' + App.badge(s.afterMode, s.afterMode === 'WAIT' ? 'warning' : 'info') + '</td>' +
                '<td>' + (s.executor ? App.escapeHtml(s.executor) : '<span class="text-muted">' + I18N.t('common.unlimited') + '</span>') + '</td>' +
                '<td class="mono sql-cell" style="max-width:280px">' + s.files.map(App.escapeHtml).join(', ') + '</td></tr>';
        });
        $('#scanPreview').html('<div class="fw-bold mb-1 small">' + App.escapeHtml(I18N.t('rel.scan.title')) + '</div>' +
            '<table class="table table-sm table-bordered mb-0"><thead class="table-light">' +
            '<tr><th data-i18n="rel.createModal.stepNo">Step</th><th data-i18n="rel.createModal.conn">Connection</th>' +
            '<th>afterMode</th><th data-i18n="rel.steps.col.executor">Executor</th>' +
            '<th data-i18n="rel.scan.col.files">SQL Files</th></tr></thead><tbody>' +
            rows.join('') + '</tbody></table>');
    });
}

function savePlan() {
    const planName = $('#cPlanName').val().trim();
    if (!planName) { App.toast(I18N.t('rel.toast.planNameRequired'), 'danger'); return; }
    const body = { planName: planName, defaultConnKey: $('#cDefaultConn').val() || null, steps: collectStepConfigs() };
    App.api('POST', '/api/releases', body).then(function (plan) {
        App.toast(I18N.t('rel.toast.planCreated'));
        bootstrap.Modal.getInstance($('#createModal')[0]).hide();
        loadPlanListThenDetail(plan.id);
    });
}

function loadPlanListThenDetail(planId) {
    App.api('GET', '/api/releases').then(function (list) {
        plans = Array.isArray(list) ? list : [];
        renderPlans();
        loadDetail(planId);
    });
}

// ---------- 状态机操作 ----------

function openStart() {
    if (!currentPlan) return;
    $('#startModalPlan').text('#' + currentPlan.id + ' ' + currentPlan.planName);
    $('#sCrNumber').val(currentPlan.crNumber || '');
    $('#sRemark').val(currentPlan.remark || '');
    $('#startOperator').text(App.operator);
    new bootstrap.Modal('#startModal').show();
}

function doStart() {
    const crNumber = $('#sCrNumber').val().trim();
    const remark = $('#sRemark').val().trim();
    if (!crNumber || !remark) { App.toast(I18N.t('rel.toast.crRequired'), 'danger'); return; }
    App.api('POST', '/api/releases/' + currentPlanId + '/start', { crNumber: crNumber, remark: remark })
        .then(function () {
            bootstrap.Modal.getInstance($('#startModal')[0]).hide();
            App.toast(I18N.t('rel.toast.started'));
            loadDetail(currentPlanId);
        });
}

function doContinue() {
    App.api('POST', '/api/releases/' + currentPlanId + '/continue').then(function () {
        App.toast(I18N.t('rel.toast.continued'));
        loadDetail(currentPlanId);
    });
}

function doRetry(stepNo) {
    App.api('POST', '/api/releases/' + currentPlanId + '/steps/' + stepNo + '/retry').then(function () {
        App.toast(I18N.t('rel.toast.retried', { no: stepNo }));
        loadDetail(currentPlanId);
    });
}

function doRerun() {
    if (!confirm(I18N.t('rel.confirmRerun'))) return;
    App.api('POST', '/api/releases/' + currentPlanId + '/rerun').then(function () {
        App.toast(I18N.t('rel.toast.rerunStarted'));
        loadDetail(currentPlanId);
    });
}

// ---------- 日志与摘要 ----------

function openLogs() {
    if (!currentPlan) return;
    const $filter = $('#logsStepFilter');
    $filter.find('option:not(:first)').remove();
    currentSteps.forEach(s => $filter.append('<option value="' + s.stepNo + '">' + I18N.t('rel.logs.col.step') + ' ' + s.stepNo + '</option>'));
    $filter.val('');
    $('#logsTable').html('<tr><td colspan="7" class="text-center text-muted py-3">' + App.escapeHtml(I18N.t('common.loading')) + '</td></tr>');
    new bootstrap.Modal('#logsModal').show();
    loadLogs();
}

function loadLogs() {
    const stepNo = $('#logsStepFilter').val();
    const url = '/api/releases/' + currentPlanId + '/logs' + (stepNo ? '?stepNo=' + stepNo : '');
    App.api('GET', url).then(function (logs) {
        const rows = (logs || []).map(function (l) {
            return '<tr><td class="mono">' + l.seq + '</td>' +
                '<td class="mono">' + (l.stepNo || '-') + '</td>' +
                '<td class="mono sql-cell" style="max-width:160px">' + App.escapeHtml(l.fileName) + '</td>' +
                '<td class="sql-text" style="max-width:380px">' + App.escapeHtml(l.sqlPreview) + '</td>' +
                '<td>' + (l.status === 'SUCCESS' ? App.badge('SUCCESS', 'success') : App.badge('FAIL', 'danger')) + '</td>' +
                '<td class="mono">' + App.fmtMs(l.durationMs) + '</td>' +
                '<td class="sql-cell" style="max-width:180px" title="' + App.escapeHtml(l.errorMsg || '') + '">' + App.escapeHtml(l.errorMsg || '-') + '</td></tr>';
        });
        $('#logsTable').html(rows.length ? rows.join('') :
            '<tr><td colspan="7" class="text-center text-muted py-3">' + App.escapeHtml(I18N.t('rel.logsModal.empty')) + '</td></tr>');
    });
}

function openSummaries() {
    App.api('GET', '/api/releases/' + currentPlanId + '/summaries').then(function (list) {
        const rows = (list || []).map(function (s) {
            let detail = '-';
            try {
                const arr = JSON.parse(s.stepDetail || '[]');
                detail = arr.map(x => '#' + x.stepNo + ' ' + x.status + ' ' + App.fmtMs(x.durationMs)).join(', ');
            } catch (e) { /* ignore */ }
            return '<tr><td class="fw-bold">' + App.escapeHtml(I18N.t('rel.summary.round', { n: s.runSeq })) + '</td>' +
                '<td class="mono">' + App.fmtMs(s.totalMs) + '</td>' +
                '<td class="mono">' + App.fmtTime(s.startedAt) + '</td>' +
                '<td class="mono">' + App.fmtTime(s.finishedAt) + '</td>' +
                '<td>' + App.escapeHtml(s.operator || '-') + '</td>' +
                '<td class="small">' + App.escapeHtml(detail) + '</td></tr>';
        });
        $('#summaryTable').html(rows.length ? rows.join('') :
            '<tr><td colspan="6" class="text-center text-muted py-3">' + App.escapeHtml(I18N.t('rel.summary.empty')) + '</td></tr>');
        new bootstrap.Modal('#summaryModal').show();
    });
}
