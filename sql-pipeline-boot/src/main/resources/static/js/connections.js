let connections = [];

$(function () {
    App.bindOperator();
    loadConnections();
});

function loadConnections() {
    App.api('GET', '/api/connections').then(function (list) {
        connections = list || [];
        render();
    }).fail(function () {
        $('#connTable').html('<tr><td colspan="10" class="text-center text-muted py-4">' + App.escapeHtml(I18N.t('common.loadFailed')) + '</td></tr>');
    });
}

function render() {
    if (!connections.length) {
        $('#connTable').html('<tr><td colspan="10" class="text-center text-muted py-4">' + App.escapeHtml(I18N.t('conn.empty')) + '</td></tr>');
        return;
    }
    const rows = connections.map(function (c) {
        const enabled = c.enabled === 1;
        return '<tr data-id="' + c.id + '">' +
            '<td>' + c.id + '</td>' +
            '<td class="mono fw-bold">' + App.escapeHtml(c.connKey) + '</td>' +
            '<td>' + App.escapeHtml(c.displayName) + '</td>' +
            '<td class="mono sql-cell" title="' + App.escapeHtml(c.jdbcUrl) + '">' + App.escapeHtml(c.jdbcUrl) + '</td>' +
            '<td>' + App.escapeHtml(c.username) + '</td>' +
            '<td class="mono">' + App.escapeHtml(c.defaultSchema || '-') + '</td>' +
            '<td class="mono">' + c.poolSize + ' / ' + c.connTimeoutMs + 'ms</td>' +
            '<td class="mono">' + c.maxRows + '</td>' +
            '<td>' + (enabled ? App.badge(I18N.t('common.enabled'), 'success') : App.badge(I18N.t('common.disabled'), 'secondary')) + '</td>' +
            '<td><div class="btn-group btn-group-sm" role="group">' +
            '<button class="btn btn-outline-info" onclick="testConn(' + c.id + ')">' + I18N.t('common.test') + '</button>' +
            '<button class="btn btn-outline-secondary" onclick="reloadConn(' + c.id + ')">' + I18N.t('conn.reload') + '</button>' +
            '<button class="btn btn-outline-primary" onclick="openEdit(' + c.id + ')">' + I18N.t('common.edit') + '</button>' +
            (enabled
                ? '<button class="btn btn-outline-warning" onclick="toggleConn(' + c.id + ', 0)">' + I18N.t('common.disable') + '</button>'
                : '<button class="btn btn-outline-success" onclick="toggleConn(' + c.id + ', 1)">' + I18N.t('common.enable') + '</button>') +
            '</div></td></tr>';
    });
    $('#connTable').html(rows.join(''));
}

function openCreate() {
    $('#connModalTitle').text(I18N.t('conn.modal.create'));
    $('#connForm')[0].reset();
    $('#connId').val('');
    $('#fConnKey').prop('disabled', false);
    $('#fDriverClass').val('org.postgresql.Driver');
    $('#fDefaultSchema').val('public');
    $('#fPoolSize').val(10); $('#fConnTimeout').val(5000);
    $('#fMaxRows').val(1000); $('#fQueryTimeout').val(30); $('#fEnabled').val(1);
    $('#pwdRequired').show(); $('#pwdHint').hide(); $('#connKeyHint').show();
}

function openEdit(id) {
    const c = connections.find(x => x.id === id);
    if (!c) return;
    $('#connModalTitle').text(I18N.t('conn.modal.edit', { id: id }));
    $('#connId').val(c.id);
    $('#fConnKey').val(c.connKey).prop('disabled', true);
    $('#fDisplayName').val(c.displayName);
    $('#fJdbcUrl').val(c.jdbcUrl);
    $('#fUsername').val(c.username);
    $('#fDefaultSchema').val(c.defaultSchema || '');
    $('#fPassword').val('');
    $('#fDriverClass').val(c.driverClass);
    $('#fPoolSize').val(c.poolSize);
    $('#fConnTimeout').val(c.connTimeoutMs);
    $('#fMaxRows').val(c.maxRows);
    $('#fQueryTimeout').val(c.queryTimeoutS);
    $('#fEnabled').val(String(c.enabled));
    $('#pwdRequired').hide(); $('#pwdHint').show(); $('#connKeyHint').hide();
    new bootstrap.Modal('#connModal').show();
}

function saveConn() {
    const id = $('#connId').val();
    const base = {
        displayName: $('#fDisplayName').val().trim(),
        jdbcUrl: $('#fJdbcUrl').val().trim(),
        username: $('#fUsername').val().trim(),
        password: $('#fPassword').val(),
        driverClass: $('#fDriverClass').val().trim(),
        defaultSchema: $('#fDefaultSchema').val().trim() || null,
        poolSize: parseInt($('#fPoolSize').val(), 10),
        connTimeoutMs: parseInt($('#fConnTimeout').val(), 10),
        maxRows: parseInt($('#fMaxRows').val(), 10),
        queryTimeoutS: parseInt($('#fQueryTimeout').val(), 10),
        enabled: parseInt($('#fEnabled').val(), 10)
    };
    if (id) {
        App.api('PUT', '/api/connections/' + id, base).then(function () {
            App.toast(I18N.t('conn.toast.updated'));
            bootstrap.Modal.getInstance($('#connModal')[0]).hide();
            loadConnections();
        });
    } else {
        base.connKey = $('#fConnKey').val().trim();
        if (!base.connKey || !base.displayName || !base.jdbcUrl || !base.username || !base.password) {
            App.toast(I18N.t('conn.toast.required'), 'danger');
            return;
        }
        App.api('POST', '/api/connections', base).then(function () {
            App.toast(I18N.t('conn.toast.created'));
            bootstrap.Modal.getInstance($('#connModal')[0]).hide();
            loadConnections();
        });
    }
}

function testConn(id) {
    App.api('POST', '/api/connections/' + id + '/test').then(function (r) {
        App.toast(r.ok ? I18N.t('conn.toast.testOk', { ms: r.latencyMs })
                : I18N.t('conn.toast.testFail', { msg: r.message }),
            r.ok ? 'success' : 'danger');
    });
}

function reloadConn(id) {
    App.api('POST', '/api/connections/' + id + '/reload').then(function () {
        App.toast(I18N.t('conn.toast.reloaded'));
    });
}

function toggleConn(id, enabled) {
    App.api('PUT', '/api/connections/' + id, { enabled: enabled }).then(function () {
        App.toast(enabled === 1 ? I18N.t('conn.toast.enabled') : I18N.t('conn.toast.disabled'));
        loadConnections();
    });
}
