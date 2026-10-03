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
        $('#connTable').html('<tr><td colspan="9" class="text-center text-muted py-4">加载失败</td></tr>');
    });
}

function render() {
    if (!connections.length) {
        $('#connTable').html('<tr><td colspan="10" class="text-center text-muted py-4">暂无连接，点击右上角「新建连接」</td></tr>');
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
            '<td>' + (enabled ? App.badge('启用', 'success') : App.badge('停用', 'secondary')) + '</td>' +
            '<td><div class="btn-group btn-group-sm" role="group">' +
            '<button class="btn btn-outline-info" onclick="testConn(' + c.id + ')">测试</button>' +
            '<button class="btn btn-outline-secondary" onclick="reloadConn(' + c.id + ')">重载</button>' +
            '<button class="btn btn-outline-primary" onclick="openEdit(' + c.id + ')">编辑</button>' +
            (enabled
                ? '<button class="btn btn-outline-warning" onclick="toggleConn(' + c.id + ', 0)">停用</button>'
                : '<button class="btn btn-outline-success" onclick="toggleConn(' + c.id + ', 1)">启用</button>') +
            '</div></td></tr>';
    });
    $('#connTable').html(rows.join(''));
}

function openCreate() {
    $('#connModalTitle').text('新建连接');
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
    $('#connModalTitle').text('编辑连接 #' + id);
    $('#connId').val(c.id);
    $('#fConnKey').val(c.connKey).prop('disabled', true);
    $('#fDisplayName').val(c.displayName);
    $('#fJdbcUrl').val(c.jdbcUrl);
    $('#fUsername').val(c.username);
    $('#fPassword').val('');
    $('#fDriverClass').val(c.driverClass);
    $('#fDefaultSchema').val(c.defaultSchema || '');
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
            App.toast('连接已更新，连接池已热更新');
            bootstrap.Modal.getInstance($('#connModal')[0]).hide();
            loadConnections();
        });
    } else {
        base.connKey = $('#fConnKey').val().trim();
        if (!base.connKey || !base.displayName || !base.jdbcUrl || !base.username || !base.password) {
            App.toast('请填写完整的必填项（connKey/名称/URL/用户名/密码）', 'danger');
            return;
        }
        App.api('POST', '/api/connections', base).then(function () {
            App.toast('连接已创建并注册连接池');
            bootstrap.Modal.getInstance($('#connModal')[0]).hide();
            loadConnections();
        });
    }
}

function testConn(id) {
    App.api('POST', '/api/connections/' + id + '/test').then(function (r) {
        App.toast(r.ok ? '连接成功，耗时 ' + r.latencyMs + ' ms' : '连接失败：' + r.message, r.ok ? 'success' : 'danger');
    });
}

function reloadConn(id) {
    App.api('POST', '/api/connections/' + id + '/reload').then(function () {
        App.toast('连接池已重载');
    });
}

function toggleConn(id, enabled) {
    const c = connections.find(x => x.id === id);
    App.api('PUT', '/api/connections/' + id, { enabled: enabled }).then(function () {
        App.toast('连接已' + (enabled === 1 ? '启用' : '停用（连接池已移除）'));
        loadConnections();
    });
}
