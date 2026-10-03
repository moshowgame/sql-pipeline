$(function () {
    App.bindOperator();
    loadSummary();
    loadRecentPlans();
});

function loadSummary() {
    $.when(
        App.api('GET', '/api/connections'),
        App.api('GET', '/api/health-checks'),
        App.api('GET', '/api/releases'),
        $.getJSON('/actuator/health')
    ).done(function (conns, defs, plans, health) {
        const connList = conns[0] || [], defList = defs[0] || [], planList = plans[0] || [];
        const enabledConns = connList.filter(c => c.enabled === 1).length;
        const enabledDefs = defList.filter(d => d.enabled === 1).length;
        const running = planList.filter(p => p.status === 'RUNNING' || p.status === 'WAITING').length;
        const failed = planList.filter(p => p.status === 'FAILED').length;
        const cards = [
            { title: '数据库连接', value: connList.length, sub: '启用 ' + enabledConns, link: '/connections.html', color: 'primary' },
            { title: '健康检查', value: defList.length, sub: '启用 ' + enabledDefs, link: '/health-checks.html', color: 'success' },
            { title: '发布计划', value: planList.length, sub: '进行中 ' + running, link: '/releases.html', color: 'info' },
            { title: '失败计划', value: failed, sub: '可在发布页重试/重跑', link: '/releases.html', color: failed > 0 ? 'danger' : 'secondary' }
        ];
        $('#summaryCards').html(cards.map(c =>
            '<div class="col-6 col-lg-3"><div class="card page-card h-100">' +
            '<div class="card-body"><div class="d-flex justify-content-between">' +
            '<div><div class="text-muted small">' + App.escapeHtml(c.title) + '</div>' +
            '<div class="fs-3 fw-bold lh-1 mt-1">' + c.value + '</div>' +
            '<div class="text-muted small mt-1">' + App.escapeHtml(c.sub) + '</div></div>' +
            '<span class="badge bg-' + c.color + ' align-self-start">查看</span></div>' +
            '</div><a class="stretched-link" href="' + c.link + '"></a></div></div>'
        ).join(''));
        $('#healthInfo').text('actuator/health = ' + (health[0] && health[0].status ? health[0].status : 'unknown'));
    });
}

function loadRecentPlans() {
    App.api('GET', '/api/releases').then(function (plans) {
        const rows = (plans || []).slice(0, 8).map(function (p) {
            return '<tr><td>' + p.id + '</td><td class="mono">' + App.escapeHtml(p.planName) + '</td>' +
                '<td>' + App.planBadge(p.status) + '</td>' +
                '<td>' + App.escapeHtml(p.operator || '-') + '</td>' +
                '<td>' + App.fmtTime(p.startedAt) + '</td></tr>';
        });
        $('#recentPlans').html(rows.length ? rows.join('') :
            '<tr><td colspan="5" class="text-center text-muted py-3">暂无发布计划，去 <a href="/releases.html">发布编排</a> 创建</td></tr>');
    }).fail(function () {
        $('#recentPlans').html('<tr><td colspan="5" class="text-center text-muted py-3">加载失败</td></tr>');
    });
}
