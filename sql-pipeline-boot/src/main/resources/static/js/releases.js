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
            plans = p[0] || [];
            connections = c[0] || [];
            renderPlans();
        }).fail(function () {
            $('#planTable').html('<tr><td colspan="9" class="text-center text-muted py-4">加载失败</td></tr>');
        });
});

// ---------- 计划列表 ----------

function renderPlans() {
    if (!plans.length) {
        $('#planTable').html('<tr><td colspan="9" class="text-center text-muted py-4">暂无发布计划，点击右上角「创建计划」</td></tr>');
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
            '<td><button class="btn btn-outline-primary btn-sm" onclick="event.stopPropagation();loadDetail(' + p.id + ')">详情</button></td>' +
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
        infoCell('CR 单号', p.crNumber || '-'),
        infoCell('备注', p.remark || '-'),
        infoCell('操作人', p.operator || '-'),
        infoCell('默认连接', p.defaultConnKey || '-'),
        infoCell('计划目录', (p.basePath || '') + '/' + p.planName),
        infoCell('重跑次数', String(p.rerunCount || 0))
    ].join(''));

    const st = p.status;
    $('#btnStart').prop('disabled', st !== 'DRAFT');
    $('#btnContinue').prop('disabled', st !== 'WAITING');
    $('#btnRerun').prop('disabled', st === 'RUNNING');

    const stepConfigs = detail.stepConfigs || [];
    const cfgByNo = {};
    stepConfigs.forEach(c => { cfgByNo[c.stepNo] = c; });

    const rows = currentSteps.map(function (s) {
        const cfg = cfgByNo[s.stepNo];
        const actions = [];
        if (s.status === 'FAIL') actions.push('<button class="btn btn-outline-danger btn-sm" onclick="doRetry(' + s.stepNo + ')">重试</button>');
        if (s.status === 'WAITING_CONTINUE') actions.push('<span class="text-warning fw-bold">⏸ 等待人工确认</span>');
        if (s.status === 'RUNNING') actions.push('<span class="text-primary">执行中…</span>');
        if (!actions.length) actions.push('<span class="text-muted">-</span>');
        return '<tr>' +
            '<td class="fw-bold">' + s.stepNo + '</td>' +
            '<td class="mono sql-cell" title="' + App.escapeHtml(s.dirPath) + '">' + App.escapeHtml(s.dirPath) + '</td>' +
            '<td class="mono">' + App.escapeHtml(s.connKey || '-') + '</td>' +
            '<td>' + App.badge(s.afterMode, s.afterMode === 'WAIT' ? 'warning text-dark' : 'info') + '</td>' +
            '<td>' + (cfg && cfg.executor ? App.escapeHtml(cfg.executor) : '<span class="text-muted">不限</span>') + '</td>' +
            '<td>' + App.stepBadge(s.status) + '</td>' +
            '<td class="mono">' + (s.retryCount || 0) + '</td>' +
            '<td class="mono">' + App.fmtMs(s.durationMs) + '</td>' +
            '<td class="sql-cell" style="max-width:200px" title="' + App.escapeHtml(s.errorMsg || '') + '">' + App.escapeHtml(s.errorMsg || '-') + '</td>' +
            '<td>' + actions.join(' ') + '</td></tr>';
    });
    $('#stepTable').html(rows.length ? rows.join('') :
        '<tr><td colspan="10" class="text-center text-muted py-3">尚未启动（启动后按目录扫描生成步骤）</td></tr>');
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
        $('#sseBadge').html('<span class="badge bg-success">实时推送已连接</span>');
    });
    const refresh = function () {
        clearTimeout(sseDebounce);
        sseDebounce = setTimeout(function () {
            if (currentPlanId) loadDetail(currentPlanId, true);
            // 列表状态也可能变化
            App.api('GET', '/api/releases').then(function (list) { plans = list || []; renderPlans(); });
        }, 300);
    };
    sseSource.addEventListener('step', refresh);
    sseSource.addEventListener('plan', refresh);
    sseSource.onerror = function () {
        $('#sseBadge').html('<span class="badge bg-secondary">推送已断开</span>');
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
        '<td><select class="form-select form-select-sm sc-conn"><option value="">（默认连接）</option>' +
        connSelectOptions(connKey) + '</select></td>' +
        '<td><select class="form-select form-select-sm sc-mode">' +
        '<option value="CONTINUE"' + (afterMode !== 'WAIT' ? ' selected' : '') + '>CONTINUE 自动</option>' +
        '<option value="WAIT"' + (afterMode === 'WAIT' ? ' selected' : '') + '>WAIT 人工确认</option></select></td>' +
        '<td><input class="form-control form-control-sm sc-executor" value="' + App.escapeHtml(executor || '') + '" placeholder="指定执行者账号（可空）"></td>' +
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
    if (!planName) { App.toast('请先填写计划名', 'danger'); return; }
    const body = { planName: planName, defaultConnKey: $('#cDefaultConn').val() || null, steps: collectStepConfigs() };
    App.api('POST', '/api/releases/scan', body).then(function (list) {
        if (!list.length) {
            $('#scanPreview').html('<div class="alert alert-warning py-2 mb-0">未扫描到有效步骤目录：请确认 base-path 下存在该目录，且含数字命名、非空的 SQL 步骤子目录。</div>');
            return;
        }
        const rows = list.map(function (s) {
            return '<tr><td class="fw-bold">' + s.stepNo + '</td>' +
                '<td class="mono">' + App.escapeHtml(s.connKey || '(未设置!)') + '</td>' +
                '<td>' + App.badge(s.afterMode, s.afterMode === 'WAIT' ? 'warning text-dark' : 'info') + '</td>' +
                '<td>' + (s.executor ? App.escapeHtml(s.executor) : '<span class="text-muted">不限</span>') + '</td>' +
                '<td class="mono sql-cell" style="max-width:280px">' + s.files.map(App.escapeHtml).join(', ') + '</td></tr>';
        });
        $('#scanPreview').html('<div class="fw-bold mb-1 small">扫描结果（将按步骤号顺序执行；缺号/空目录已忽略）</div>' +
            '<table class="table table-sm table-bordered mb-0"><thead class="table-light">' +
            '<tr><th>步骤</th><th>连接</th><th>afterMode</th><th>执行者</th><th>SQL 文件</th></tr></thead><tbody>' +
            rows.join('') + '</tbody></table>');
    });
}

function savePlan() {
    const planName = $('#cPlanName').val().trim();
    if (!planName) { App.toast('请填写计划名', 'danger'); return; }
    const body = { planName: planName, defaultConnKey: $('#cDefaultConn').val() || null, steps: collectStepConfigs() };
    App.api('POST', '/api/releases', body).then(function (plan) {
        App.toast('计划已创建（DRAFT）');
        bootstrap.Modal.getInstance($('#createModal')[0]).hide();
        loadPlanListThenDetail(plan.id);
    });
}

function loadPlanListThenDetail(planId) {
    App.api('GET', '/api/releases').then(function (list) {
        plans = list || [];
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
    if (!crNumber || !remark) { App.toast('CR 单号与备注为必填项', 'danger'); return; }
    App.api('POST', '/api/releases/' + currentPlanId + '/start', { crNumber: crNumber, remark: remark })
        .then(function () {
            bootstrap.Modal.getInstance($('#startModal')[0]).hide();
            App.toast('已启动（CONTINUE 步骤连跑中…）');
            loadDetail(currentPlanId);
        });
}

function doContinue() {
    App.api('POST', '/api/releases/' + currentPlanId + '/continue').then(function () {
        App.toast('已确认继续，后续步骤推进中…');
        loadDetail(currentPlanId);
    });
}

function doRetry(stepNo) {
    App.api('POST', '/api/releases/' + currentPlanId + '/steps/' + stepNo + '/retry').then(function () {
        App.toast('步骤 ' + stepNo + ' 已重试（成功后进入 WAITING，由人工决定是否继续）');
        loadDetail(currentPlanId);
    });
}

function doRerun() {
    if (!confirm('确认全流程重跑？将记录本次运行摘要并从头执行所有步骤。')) return;
    App.api('POST', '/api/releases/' + currentPlanId + '/rerun').then(function () {
        App.toast('已开始全流程重跑');
        loadDetail(currentPlanId);
    });
}

// ---------- 日志与摘要 ----------

function openLogs() {
    if (!currentPlan) return;
    const $filter = $('#logsStepFilter');
    $filter.find('option:not(:first)').remove();
    currentSteps.forEach(s => $filter.append('<option value="' + s.stepNo + '">步骤 ' + s.stepNo + '</option>'));
    $filter.val('');
    $('#logsTable').html('<tr><td colspan="7" class="text-center text-muted py-3">加载中…</td></tr>');
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
        $('#logsTable').html(rows.length ? rows.join('') : '<tr><td colspan="7" class="text-center text-muted py-3">暂无日志</td></tr>');
    });
}

function openSummaries() {
    App.api('GET', '/api/releases/' + currentPlanId + '/summaries').then(function (list) {
        const rows = (list || []).map(function (s) {
            let detail = '-';
            try {
                const arr = JSON.parse(s.stepDetail || '[]');
                detail = arr.map(x => '#' + x.stepNo + ' ' + x.status + ' ' + App.fmtMs(x.durationMs)).join('，');
            } catch (e) { /* ignore */ }
            return '<tr><td class="fw-bold">第 ' + s.runSeq + ' 轮</td>' +
                '<td class="mono">' + App.fmtMs(s.totalMs) + '</td>' +
                '<td class="mono">' + App.fmtTime(s.startedAt) + '</td>' +
                '<td class="mono">' + App.fmtTime(s.finishedAt) + '</td>' +
                '<td>' + App.escapeHtml(s.operator || '-') + '</td>' +
                '<td class="small">' + App.escapeHtml(detail) + '</td></tr>';
        });
        $('#summaryTable').html(rows.length ? rows.join('') :
            '<tr><td colspan="6" class="text-center text-muted py-3">暂无运行摘要（完成一轮或重跑后生成）</td></tr>');
        new bootstrap.Modal('#summaryModal').show();
    });
}
