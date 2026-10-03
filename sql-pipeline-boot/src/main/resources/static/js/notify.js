let channels = [];

$(function () {
    App.bindOperator();
    loadAll();

    // 认证方式切换时显隐对应字段
    $('#nAuthType').on('change', function () { toggleAuthFields($(this).val()); });
});

function loadAll() {
    $.when(App.api('GET', '/api/notify/channels'), App.api('GET', '/api/notify/logs?limit=50'))
        .done(function (chs, logs) {
            channels = Array.isArray(chs) ? chs : [];
            renderChannels();
            renderLogs(Array.isArray(logs) ? logs : []);
        });
}

function renderChannels() {
    if (!channels.length) {
        $('#channelTable').html('<tr><td colspan="9" class="text-center text-muted py-4">' + App.escapeHtml(I18N.t('ntf.empty')) + '</td></tr>');
        return;
    }
    const rows = channels.map(function (c) {
        let events = '-';
        try { events = (JSON.parse(c.events || '[]') || []).join(', '); } catch (e) { events = c.events || '-'; }
        const enabled = c.enabled === 1;
        return '<tr data-id="' + c.id + '">' +
            '<td>' + c.id + '</td>' +
            '<td class="fw-bold">' + App.escapeHtml(c.name) + '</td>' +
            '<td>' + App.badge(c.type, 'info') + '</td>' +
            '<td class="mono sql-cell" title="' + App.escapeHtml(c.url) + '">' + App.escapeHtml(c.url) + '</td>' +
            '<td>' + App.escapeHtml(c.authType) +
            (c.authType === 'BASIC' && c.username ? '<div class="text-muted small">' + App.escapeHtml(c.username) + '</div>' : '') +
            '</td>' +
            '<td class="small">' + App.escapeHtml(events) + '</td>' +
            '<td class="mono">' + (c.hcFailThreshold || 1) + '</td>' +
            '<td>' + (enabled ? App.badge(I18N.t('common.enabled'), 'success') : App.badge(I18N.t('common.disabled'), 'secondary')) + '</td>' +
            '<td><div class="btn-group btn-group-sm" role="group">' +
            '<button class="btn btn-success" onclick="testChannel(' + c.id + ')">' + I18N.t('common.test') + '</button>' +
            '<button class="btn btn-outline-primary" onclick="openEdit(' + c.id + ')">' + I18N.t('common.edit') + '</button>' +
            '<button class="btn btn-outline-danger" onclick="deleteChannel(' + c.id + ')">' + I18N.t('common.delete') + '</button>' +
            '</div></td></tr>';
    });
    $('#channelTable').html(rows.join(''));
}

function renderLogs(logs) {
    if (!logs.length) {
        $('#logTable').html('<tr><td colspan="6" class="text-center text-muted py-4">' + App.escapeHtml(I18N.t('ntf.logs.empty')) + '</td></tr>');
        return;
    }
    const rows = logs.map(function (l) {
        return '<tr><td class="mono">' + App.fmtTime(l.createdAt) + '</td>' +
            '<td>' + App.escapeHtml(l.channelName || l.channelId || '-') + '</td>' +
            '<td>' + App.badge(l.event, l.event === 'TEST' ? 'secondary' : 'info') + '</td>' +
            '<td class="sql-cell" style="max-width:240px" title="' + App.escapeHtml(l.title) + '">' + App.escapeHtml(l.title || '-') + '</td>' +
            '<td>' + (l.status === 'SUCCESS' ? App.badge('SUCCESS', 'success') : App.badge('FAIL', 'danger')) + '</td>' +
            '<td class="sql-cell" style="max-width:280px" title="' + App.escapeHtml((l.responseBody || '') + (l.errorMsg || '')) + '">' +
            App.escapeHtml(l.responseBody || l.errorMsg || '-') + '</td></tr>';
    });
    $('#logTable').html(rows.join(''));
}

function toggleAuthFields(authType) {
    $('#usernameWrap, #secretWrap, #headerWrap').hide();
    if (authType === 'BASIC') {
        $('#usernameWrap, #secretWrap').show();
        $('#secretLabel').text(I18N.t('ntf.field.secretBasic'));
    } else if (authType === 'API_KEY') {
        $('#headerWrap, #secretWrap').show();
        $('#secretLabel').text(I18N.t('ntf.field.secretApiKey'));
    }
}

function openCreate() {
    $('#channelModalTitle').text(I18N.t('ntf.modal.create'));
    $('#channelForm')[0].reset();
    $('#channelId').val('');
    $('#nType').val('XMATTERS');
    $('#nEnabled').val(1);
    $('#nThreshold').val(1);
    $('#nAuthType').val('NONE');
    $('#nAuthHeader').val('apikey');
    toggleAuthFields('NONE');
    $('#secretHint').hide();
    new bootstrap.Modal('#channelModal').show();
}

function openEdit(id) {
    const c = channels.find(x => x.id === id);
    if (!c) return;
    $('#channelModalTitle').text(I18N.t('ntf.modal.edit', { id: id }));
    $('#channelId').val(c.id);
    $('#nName').val(c.name);
    $('#nType').val(c.type);
    $('#nEnabled').val(String(c.enabled));
    $('#nThreshold').val(c.hcFailThreshold || 1);
    $('#nUrl').val(c.url);
    $('#nAuthType').val(c.authType || 'NONE');
    $('#nUsername').val(c.username || '');
    $('#nAuthHeader').val(c.authHeaderName || 'apikey');
    $('#nSecret').val('');
    let events = [];
    try { events = JSON.parse(c.events || '[]') || []; } catch (e) { /* ignore */ }
    $('.ev').each(function () { $(this).prop('checked', events.indexOf($(this).val()) >= 0); });
    toggleAuthFields(c.authType || 'NONE');
    $('#secretHint').show();
    new bootstrap.Modal('#channelModal').show();
}

function saveChannel() {
    const id = $('#channelId').val();
    const body = {
        name: $('#nName').val().trim(),
        type: $('#nType').val(),
        url: $('#nUrl').val().trim(),
        authType: $('#nAuthType').val(),
        username: $('#nUsername').val().trim() || null,
        authHeaderName: $('#nAuthHeader').val().trim() || null,
        secret: $('#nSecret').val() || null,
        events: $('.ev:checked').map(function () { return $(this).val(); }).get(),
        hcFailThreshold: parseInt($('#nThreshold').val(), 10) || 1,
        enabled: parseInt($('#nEnabled').val(), 10)
    };
    if (!body.url || !body.name) { App.toast(I18N.t('ntf.toast.required'), 'danger'); return; }
    if (!body.events.length) { App.toast(I18N.t('ntf.toast.eventsRequired'), 'danger'); return; }
    const req = id ? App.api('PUT', '/api/notify/channels/' + id, body)
        : App.api('POST', '/api/notify/channels', body);
    req.then(function () {
        App.toast(I18N.t('ntf.toast.saved'));
        bootstrap.Modal.getInstance($('#channelModal')[0]).hide();
        loadAll();
    });
}

function testChannel(id) {
    App.toast(I18N.t('ntf.toast.sendingTest'), 'secondary');
    App.api('POST', '/api/notify/channels/' + id + '/test').then(function (log) {
        if (log.status === 'SUCCESS') {
            App.toast(I18N.t('ntf.toast.testOk', { msg: log.responseBody || '' }));
        } else {
            App.toast(I18N.t('ntf.toast.testFail', { msg: log.errorMsg || '' }), 'danger');
        }
        loadAll();
    });
}

function deleteChannel(id) {
    if (!confirm(I18N.t('ntf.confirmDelete'))) return;
    App.api('DELETE', '/api/notify/channels/' + id).then(function () {
        App.toast(I18N.t('ntf.toast.deleted'));
        loadAll();
    });
}
