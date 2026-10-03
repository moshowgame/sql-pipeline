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
        const connList = Array.isArray(conns) ? conns : [];
        const defList = Array.isArray(defs) ? defs : [];
        const planList = Array.isArray(plans) ? plans : [];
        const enabledConns = connList.filter(c => c.enabled === 1).length;
        const enabledDefs = defList.filter(d => d.enabled === 1).length;
        const running = planList.filter(p => p.status === 'RUNNING' || p.status === 'WAITING').length;
        const failed = planList.filter(p => p.status === 'FAILED').length;
        const cards = [
            { title: I18N.t('dash.connections'), value: connList.length, sub: I18N.t('dash.sub.enabled', { n: enabledConns }), link: '/connections.html', color: 'primary' },
            { title: I18N.t('dash.healthChecks'), value: defList.length, sub: I18N.t('dash.sub.enabled', { n: enabledDefs }), link: '/health-checks.html', color: 'success' },
            { title: I18N.t('dash.plans'), value: planList.length, sub: I18N.t('dash.sub.inProgress', { n: running }), link: '/releases.html', color: 'info' },
            { title: I18N.t('dash.failedPlans'), value: failed, sub: I18N.t('dash.sub.failedHint'), link: '/releases.html', color: failed > 0 ? 'danger' : 'secondary' }
        ];
        $('#summaryCards').html(cards.map(c =>
            '<div class="col-6 col-lg-3"><div class="card page-card h-100">' +
            '<div class="card-body"><div class="d-flex justify-content-between">' +
            '<div><div class="text-muted small">' + App.escapeHtml(c.title) + '</div>' +
            '<div class="fs-3 fw-bold lh-1 mt-1">' + c.value + '</div>' +
            '<div class="text-muted small mt-1">' + App.escapeHtml(c.sub) + '</div></div>' +
            '<span class="badge bg-' + c.color + ' align-self-start">' + App.escapeHtml(I18N.t('dash.view')) + '</span></div>' +
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
            '<tr><td colspan="5" class="text-center text-muted py-3">' + App.escapeHtml(I18N.t('dash.noPlans')) + '</td></tr>');
    }).fail(function () {
        $('#recentPlans').html('<tr><td colspan="5" class="text-center text-muted py-3">' + App.escapeHtml(I18N.t('common.loadFailed')) + '</td></tr>');
    });
}
