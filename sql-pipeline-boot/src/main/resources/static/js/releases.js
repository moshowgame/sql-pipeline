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
            '<td><div class="btn-group btn-group-sm" role="group">' +
            '<button class="btn btn-outline-primary btn-sm" onclick="event.stopPropagation();loadDetail(' + p.id + ')">' + I18N.t('rel.details') + '</button>' +
            '<button class="btn btn-outline-info btn-sm" onclick="event.stopPropagation();viewPlan(' + p.id + ')">' + I18N.t('rel.view') + '</button>' +
            (p.status === 'DRAFT'
                ? '<button class="btn btn-outline-warning btn-sm" onclick="event.stopPropagation();editPlan(' + p.id + ')">' + I18N.t('rel.edit') + '</button>'
                : '') +
            '</div></td>' +
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
        renderDetail(detail, keepScroll);
        $('#detailCard').removeClass('d-none');
        subscribeSse(planId);
        if (keepScroll) $(window).scrollTop(scrollTop);
    });
}

function renderDetail(detail, keepScroll) {
    const p = detail.plan;
    $('#detailTitle').text('#' + p.id + ' ' + p.planName);
    $('#detailStatus').html(App.planBadge(p.status));

    $('#planInfo').html([
        infoCell(I18N.t('rel.info.cr'), p.crNumber || '-'),
        infoCell(I18N.t('rel.info.remark'), p.remark || '-'),
        infoCell(I18N.t('rel.info.operator'), p.operator || '-'),
        infoCell(I18N.t('rel.info.defaultConn'), p.defaultConnKey || '-'),
        infoCell(I18N.t('rel.createModal.releaseType'), p.releaseType || 'FOLDER'),
        infoCell(I18N.t('rel.createModal.releasePath'), p.releasePath || '-'),
        infoCell(I18N.t('rel.info.rerunCount'), String(p.rerunCount || 0))
    ].join(''));

    const st = p.status;
    $('#btnStart').prop('disabled', st !== 'DRAFT');
    $('#btnContinue').prop('disabled', st !== 'WAITING');
    $('#btnRerun').prop('disabled', st === 'RUNNING');

    renderPipeline();
    if (!keepScroll) {
        document.getElementById('detailCard').scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
}

// ---------- Pipeline 可视化 ----------

const PL_STATUS_COLORS = {
    PENDING: '#9ca3af', RUNNING: '#4f46e5', SUCCESS: '#16a34a',
    FAIL: '#dc2626', WAITING_CONTINUE: '#d97706', SKIPPED: '#6b7280'
};

function currentOrientation() {
    return localStorage.getItem('pipelineOrient') || 'horizontal';
}

function setOrientation(o) {
    localStorage.setItem('pipelineOrient', o);
    renderPipeline();
}

function renderPipeline() {
    const orient = currentOrientation();
    $('#orientGroup .btn').each(function () {
        const active = $(this).data('orient') === orient;
        $(this).toggleClass('btn-primary active', active).toggleClass('btn-outline-primary', !active);
    });
    $('#plLegend').html(Object.keys(PL_STATUS_COLORS).map(function (st) {
        return '<span class="pl-lg-dot" style="background:' + PL_STATUS_COLORS[st] + '"></span>' + st;
    }).join(''));

    renderProgress();

    const $pl = $('#pipeline').removeClass('horizontal vertical').addClass(orient);
    if (!currentSteps.length) {
        $pl.html('<div class="text-muted small py-3">' + App.escapeHtml(I18N.t('rel.steps.none')) + '</div>');
        return;
    }
    const arrow = orient === 'vertical' ? '▼' : '▶';
    const html = [];
    let prev = null;
    currentSteps.forEach(function (s) {
        if (prev) {
            html.push(connHtml(prev.afterMode, arrow));
        }
        html.push(nodeHtml(s));
        prev = s;
    });
    $pl.html(html.join(''));
}

/** 计划进度条：按步骤状态分段（成功绿 / 失败红 / 跳过灰）。 */
function renderProgress() {
    const $bar = $('#plProgress');
    if (!currentSteps.length) {
        $bar.empty();
        return;
    }
    const total = currentSteps.length;
    const count = st => currentSteps.filter(s => s.status === st).length;
    const ok = count('SUCCESS'), fail = count('FAIL'), skip = count('SKIPPED');
    const wait = count('WAITING_CONTINUE'), run = count('RUNNING');
    const done = ok + skip;
    const pct = n => total ? (n / total * 100).toFixed(1) : 0;
    let summary = I18N.t('rel.pl.progress', { done: done, total: total });
    if (fail) summary += ' · <span class="text-danger fw-bold">' + fail + ' FAIL</span>';
    if (wait) summary += ' · <span class="text-warning fw-bold">' + wait + ' WAITING</span>';
    if (run) summary += ' · <span class="text-primary fw-bold">' + run + ' RUNNING</span>';
    $bar.html(
        '<div class="small text-muted mb-1">' + summary + '</div>' +
        '<div class="progress" style="height:8px">' +
        '<div class="progress-bar bg-success" style="width:' + pct(ok) + '%"></div>' +
        (fail ? '<div class="progress-bar bg-danger" style="width:' + pct(fail) + '%"></div>' : '') +
        (skip ? '<div class="progress-bar bg-secondary" style="width:' + pct(skip) + '%"></div>' : '') +
        '</div>');
}

function nodeHtml(s) {
    const meta = [App.fmtMs(s.durationMs), '↻ ' + (s.retryCount || 0)];
    if (s.executor) {
        meta.push('👤 ' + App.escapeHtml(s.executor));
    }
    if (s.confirmBy) {
        meta.push('✓ ' + App.escapeHtml(s.confirmBy));
    }
    if (s.scriptChanged) {
        meta.push('<span class="text-warning fw-bold">⚠ ' + App.escapeHtml(I18N.t('rel.steps.changed')) + '</span>');
    }
    return '<div class="pl-node pl-st-' + s.status + '" onclick="openStepResult(' + s.stepNo + ')" title="' + App.escapeHtml(s.dirPath) + '">' +
        '<div class="pl-head"><span class="pl-num">' + s.stepNo + '</span><span class="pl-status">' + App.stepBadge(s.status) + '</span></div>' +
        '<div class="pl-meta">' + meta.join(' · ') + '</div>' +
        (s.errorMsg ? '<div class="pl-err" title="' + App.escapeHtml(s.errorMsg) + '">' + App.escapeHtml(s.errorMsg) + '</div>' : '') +
        '</div>';
}

/** 连接器：上一步 afterMode=WAIT 时显示人工卡点标记，否则普通箭头。 */
function connHtml(afterMode, arrow) {
    const wait = afterMode === 'WAIT';
    return '<div class="pl-conn">' +
        '<div class="pl-line"></div>' +
        (wait
            ? '<div class="pl-gate">⏸ ' + App.escapeHtml(I18N.t('rel.pl.waitGate')) + '</div><div class="pl-line"></div>'
            : '<div class="pl-arrow">' + arrow + '</div>') +
        '</div>';
}

/** 节点点击：展示该步骤运行结果与 SQL 明细。 */
function openStepResult(stepNo) {
    const s = currentSteps.find(x => x.stepNo === stepNo);
    if (!s) return;
    $('#stepResultTitle').text(I18N.t('rel.pl.stepResult', { no: stepNo }));
    const rows = [
        [I18N.t('rel.col.status'), App.stepBadge(s.status)],
        [I18N.t('rel.info.defaultConn'), App.escapeHtml(s.connKey || '-')],
        ['afterMode', App.badge(s.afterMode, s.afterMode === 'WAIT' ? 'warning' : 'info')],
        [I18N.t('rel.steps.col.executor'), s.executor ? App.escapeHtml(s.executor) : I18N.t('common.unlimited')],
        [I18N.t('common.duration'), App.fmtMs(s.durationMs)],
        [I18N.t('rel.steps.col.retries'), (s.retryCount || 0) + (s.retryRemark ? '（' + App.escapeHtml(s.retryRemark) + '）' : '')],
        [I18N.t('common.time'), App.fmtTime(s.startedAt) + ' → ' + App.fmtTime(s.finishedAt)]
    ];
    if (s.confirmBy) {
        rows.push([I18N.t('rel.steps.confirmedBy', { name: s.confirmBy }), App.fmtTime(s.confirmAt)]);
    }
    if (s.scriptChanged) {
        rows.push(['⚠', I18N.t('rel.steps.changed')]);
    }
    let html = '<div class="row g-2 mb-2">' + rows.map(function (r) {
        return '<div class="col-md-3 col-6"><div class="text-muted small">' + r[0] + '</div><div class="small">' + r[1] + '</div></div>';
    }).join('') + '</div>';
    if (s.errorMsg) {
        html += '<div class="alert alert-danger py-2 sql-text">' + App.escapeHtml(s.errorMsg) + '</div>';
    }
    // 操作区：FAIL → 重试（带备注）；WAITING_CONTINUE → 确认并继续
    if (s.status === 'FAIL') {
        html += '<div class="input-group input-group-sm mb-3" style="max-width:440px">' +
            '<input id="stepRetryRemark" class="form-control" maxlength="512" placeholder="' + App.escapeHtml(I18N.t('rel.pl.remarkPlaceholder')) + '">' +
            '<button class="btn btn-danger" onclick="retryFromModal(' + s.stepNo + ')">' + App.escapeHtml(I18N.t('rel.steps.retry')) + '</button></div>';
    } else if (s.status === 'WAITING_CONTINUE') {
        html += '<button class="btn btn-warning text-dark btn-sm mb-3" onclick="confirmFromModal()">' + App.escapeHtml(I18N.t('rel.pl.confirmContinue')) + '</button>';
    }
    html += '<div class="fw-bold small mb-1 mt-2">' + I18N.t('rel.pl.logs') + '</div>' +
        '<div id="stepLogsBody"><span class="text-muted small">' + App.escapeHtml(I18N.t('common.loading')) + '</span></div>';
    $('#stepResultBody').html(html);
    new bootstrap.Modal('#stepResultModal').show();
    App.api('GET', '/api/releases/' + currentPlanId + '/logs?stepNo=' + stepNo).then(function (logs) {
        const all = logs || [];
        const cap = 100;
        const shown = all.slice(0, cap);
        const lrows = shown.map(function (l) {
            return '<tr>' +
                '<td class="mono">' + l.seq + '</td>' +
                '<td class="mono sql-cell" style="max-width:130px">' + App.escapeHtml(l.fileName) + '</td>' +
                '<td class="sql-text" style="max-width:300px">' + App.escapeHtml(l.sqlPreview) + '</td>' +
                '<td>' + (l.status === 'SUCCESS' ? App.badge('SUCCESS', 'success') : App.badge('FAIL', 'danger')) + '</td>' +
                '<td class="mono">' + App.fmtMs(l.durationMs) + '</td>' +
                '<td class="sql-cell" style="max-width:140px" title="' + App.escapeHtml(l.errorMsg || '') + '">' + App.escapeHtml(l.errorMsg || '-') + '</td></tr>';
        });
        $('#stepLogsBody').html(
            '<table class="table table-sm table-bordered pl-mini-table mb-0"><thead class="table-light"><tr>' +
            '<th>#</th><th data-i18n="rel.logs.col.file">File</th><th>SQL</th>' +
            '<th data-i18n="rel.logs.col.status">Status</th><th data-i18n="common.duration">Duration</th>' +
            '<th data-i18n="common.error">Error</th></tr></thead><tbody>' +
            (lrows.length ? lrows.join('') :
                '<tr><td colspan="6" class="text-center text-muted py-2">' + App.escapeHtml(I18N.t('rel.logsModal.empty')) + '</td></tr>') +
            '</tbody></table>' +
            (all.length > cap ? '<div class="text-muted small mt-1">' + App.escapeHtml(I18N.t('rel.pl.logsCapped', { n: cap, total: all.length })) + '</div>' : ''));
    });
}

function retryFromModal(stepNo) {
    const remark = document.getElementById('stepRetryRemark').value.trim() || null;
    App.api('POST', '/api/releases/' + currentPlanId + '/steps/' + stepNo + '/retry', { remark: remark })
        .then(function () {
            App.toast(I18N.t('rel.toast.retried', { no: stepNo }));
            bootstrap.Modal.getInstance(document.getElementById('stepResultModal')).hide();
            loadDetail(currentPlanId, true);
        });
}

function confirmFromModal() {
    bootstrap.Modal.getInstance(document.getElementById('stepResultModal')).hide();
    doContinue();
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

// ---------- 创建 / 编辑 / 查看 弹窗 ----------

let modalMode = 'create'; // create | edit | view
let currentEditId = null;

function openCreate() {
    modalMode = 'create';
    currentEditId = null;
    $('#cAddStepBtn').prop('disabled', false);
    $('#createModal input, #createModal select').not('#cDefaultConn').val('');
    // plan_name 默认 release_yyyyMMdd（今天）
    const d = new Date();
    const pad = n => String(n).padStart(2, '0');
    $('#cPlanName').val('release_' + d.getFullYear() + pad(d.getMonth() + 1) + pad(d.getDate()));
    $('#cReleaseType').val('FOLDER');
    $('#cReleasePath').val('');
    $('#scanPreview').empty();
    $('#stepConfigRows').empty();
    $('#cDefaultConn').html(connSelectOptions());
    addStepRow();
    toggleReleaseType();
    $('#cPlanName').prop('disabled', false);
    $('#createModal .btn-primary').show();
    $('#createModalTitle').text(I18N.t('rel.createModal.title'));
    new bootstrap.Modal('#createModal').show();
}

/** 编辑（仅 DRAFT）：更新 Release 类型/路径/默认连接/步骤配置；planName 锁定。 */
function editPlan(id) {
    const p = plans.find(x => x.id === id);
    if (!p) return;
    if (p.status !== 'DRAFT') {
        App.toast(I18N.t('rel.toast.editOnlyDraft'), 'warning');
        return;
    }
    modalMode = 'edit';
    currentEditId = id;
    $('#cAddStepBtn').prop('disabled', false);
    $('#scanPreview').empty();
    $('#stepConfigRows').empty();
    $('#cPlanName').val(p.planName).prop('disabled', true);
    $('#cReleaseType').val(p.releaseType || 'FOLDER');
    $('#cReleasePath').val(p.releasePath || '');
    $('#cDefaultConn').html(connSelectOptions(p.defaultConnKey));
    let cfgs = [];
    try { cfgs = JSON.parse(p.stepConfig || '[]') || []; } catch (e) { /* ignore */ }
    cfgs.forEach(c => addStepRow(c.stepNo, c.connKey, c.afterMode, c.executor, c.dirName));
    if (!cfgs.length) addStepRow();
    toggleReleaseType();
    $('#createModal .btn-primary').show();
    $('#createModalTitle').text(I18N.t('rel.editModal.title', { id: id }));
    new bootstrap.Modal('#createModal').show();
}

/** 查看（任何状态只读）。 */
function viewPlan(id) {
    const p = plans.find(x => x.id === id);
    if (!p) return;
    modalMode = 'view';
    $('#scanPreview').empty();
    $('#stepConfigRows').empty();
    $('#cPlanName').val(p.planName).prop('disabled', true);
    $('#cReleaseType').val(p.releaseType || 'FOLDER');
    $('#cReleasePath').val(p.releasePath || '');
    $('#cDefaultConn').html(connSelectOptions(p.defaultConnKey));
    let cfgs = [];
    try { cfgs = JSON.parse(p.stepConfig || '[]') || []; } catch (e) { /* ignore */ }
    cfgs.forEach(c => addStepRow(c.stepNo, c.connKey, c.afterMode, c.executor, c.dirName));
    if (!cfgs.length) addStepRow();
    toggleReleaseType();
    $('#cScanBtn').prop('disabled', true);
    $('#cPlanName, #cReleaseType, #cReleasePath, #cDefaultConn, #cAddStepBtn').prop('disabled', true);
    $('#stepConfigRows input, #stepConfigRows select').prop('disabled', true);
    $('#stepConfigRows .btn-outline-danger').hide();
    $('#createModal .btn-primary').hide();
    $('#createModalTitle').text(I18N.t('rel.viewModal.title', { id: id }));
    new bootstrap.Modal('#createModal').show();
}

function toggleReleaseType() {
    if (modalMode === 'view') return;
    const isZip = $('#cReleaseType').val() === 'ZIP';
    // ZIP 解压功能暂未实现：禁用 SCAN 并提示（计划仍可保存，start 时后端会拒绝）
    $('#cScanBtn').prop('disabled', isZip);
    if (isZip) {
        App.toast(I18N.t('rel.toast.zipNotImplemented'), 'warning');
    }
}

function addStepRow(no, connKey, afterMode, executor, dirName) {
    const dirCell = dirName
        ? '<span class="mono small">' + App.escapeHtml(dirName) + '</span><input type="hidden" class="sc-dir" value="' + App.escapeHtml(dirName) + '">'
        : '<span class="text-muted">—</span>';
    const tr = '<tr>' +
        '<td><input class="form-control form-control-sm sc-no" type="number" min="1" max="99" value="' + (no || '') + '"></td>' +
        '<td>' + dirCell + '</td>' +
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
            dirName: $(this).find('.sc-dir').val() || null,
            stepNo: no,
            connKey: $(this).find('.sc-conn').val() || null,
            afterMode: $(this).find('.sc-mode').val(),
            executor: $(this).find('.sc-executor').val().trim() || null
        });
    });
    return configs;
}

function doScan() {
    const releasePath = $('#cReleasePath').val().trim();
    if (!releasePath) { App.toast(I18N.t('rel.toast.pathRequired'), 'danger'); return; }
    const body = {
        releaseType: $('#cReleaseType').val(),
        releasePath: releasePath,
        defaultConnKey: $('#cDefaultConn').val() || null
    };
    App.api('POST', '/api/releases/scan', body).then(function (list) {
        if (!list.length) {
            $('#stepConfigRows').empty();
            $('#scanPreview').html('<div class="alert alert-warning py-2 mb-0">' + App.escapeHtml(I18N.t('rel.scan.empty')) + '</div>');
            return;
        }
        // 扫描结果生成可编辑步骤行（步骤号默认 = 目录名数字，可调整执行顺序）
        $('#stepConfigRows').empty();
        list.forEach(function (s) {
            addStepRow(s.stepNo, s.connKey, s.afterMode, s.executor, s.dirName);
        });
        const rows = list.map(function (s) {
            return '<tr><td class="mono">' + App.escapeHtml(s.dirName) + '</td>' +
                '<td class="mono">' + I18N.t('rel.scan.fileCount', { n: s.files.length }) + '</td></tr>';
        });
        $('#scanPreview').html('<div class="fw-bold mb-1 small">' + App.escapeHtml(I18N.t('rel.scan.filesTitle')) + '</div>' +
            '<table class="table table-sm table-bordered mb-0"><thead class="table-light">' +
            '<tr><th data-i18n="rel.createModal.dirName">Directory</th>' +
            '<th data-i18n="rel.scan.col.files">SQL Files</th></tr></thead><tbody>' +
            rows.join('') + '</tbody></table>');
    });
}

function savePlan() {
    const planName = $('#cPlanName').val().trim();
    const releasePath = $('#cReleasePath').val().trim();
    if (!planName) { App.toast(I18N.t('rel.toast.planNameRequired'), 'danger'); return; }
    if (!releasePath) { App.toast(I18N.t('rel.toast.pathRequired'), 'danger'); return; }
    const body = {
        releaseType: $('#cReleaseType').val(),
        releasePath: releasePath,
        defaultConnKey: $('#cDefaultConn').val() || null,
        steps: collectStepConfigs()
    };
    if (modalMode === 'edit') {
        App.api('PUT', '/api/releases/' + currentEditId, body).then(function () {
            App.toast(I18N.t('rel.toast.updated'));
            bootstrap.Modal.getInstance($('#createModal')[0]).hide();
            App.api('GET', '/api/releases').then(function (list) {
                plans = Array.isArray(list) ? list : [];
                renderPlans();
            });
        });
        return;
    }
    body.planName = planName;
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
    const $runFilter = $('#logsRunFilter');
    $runFilter.find('option:not(:first)').remove();
    const maxRun = (currentPlan.rerunCount || 0) + 1;
    for (let i = 1; i <= maxRun; i++) {
        $runFilter.append('<option value="' + i + '">' + I18N.t('rel.summary.round', { n: i }) + '</option>');
    }
    $runFilter.val('');
    $('#logsTable').html('<tr><td colspan="8" class="text-center text-muted py-3">' + App.escapeHtml(I18N.t('common.loading')) + '</td></tr>');
    new bootstrap.Modal('#logsModal').show();
    loadLogs();
}

function loadLogs() {
    const stepNo = $('#logsStepFilter').val();
    const runSeq = $('#logsRunFilter').val();
    const params = [];
    if (stepNo) params.push('stepNo=' + stepNo);
    if (runSeq) params.push('runSeq=' + runSeq);
    const url = '/api/releases/' + currentPlanId + '/logs' + (params.length ? '?' + params.join('&') : '');
    App.api('GET', url).then(function (logs) {
        const rows = (logs || []).map(function (l) {
            return '<tr><td class="mono">' + l.seq + '</td>' +
                '<td class="mono">' + (l.runSeq || '-') + '</td>' +
                '<td class="mono">' + (l.stepNo || '-') + '</td>' +
                '<td class="mono sql-cell" style="max-width:160px">' + App.escapeHtml(l.fileName) + '</td>' +
                '<td class="sql-text" style="max-width:380px">' + App.escapeHtml(l.sqlPreview) + '</td>' +
                '<td>' + (l.status === 'SUCCESS' ? App.badge('SUCCESS', 'success') : App.badge('FAIL', 'danger')) + '</td>' +
                '<td class="mono">' + App.fmtMs(l.durationMs) + '</td>' +
                '<td class="sql-cell" style="max-width:180px" title="' + App.escapeHtml(l.errorMsg || '') + '">' + App.escapeHtml(l.errorMsg || '-') + '</td></tr>';
        });
        $('#logsTable').html(rows.length ? rows.join('') :
            '<tr><td colspan="8" class="text-center text-muted py-3">' + App.escapeHtml(I18N.t('rel.logsModal.empty')) + '</td></tr>');
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
