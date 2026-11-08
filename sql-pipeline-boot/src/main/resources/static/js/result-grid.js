/**
 * DBeaver 风格查询结果网格：
 * - 行号列、表头点击排序、NULL 弱化显示、长值截断 + 悬停完整内容
 * - 单元格双击复制、行选中高亮、复制全部（TSV 粘贴到 Excel）
 * - 导出 CSV（带 BOM）、结果状态栏（加载数 / 总行数 / 截断提示）
 * 用法：App.resultGrid('#container', { columns: [...], rows: [...], rowCount: n, truncated: bool });
 */
(function () {

    function fmtCell(v) {
        if (v === null || v === undefined) return '<span class="rg-null">NULL</span>';
        let s = String(v);
        if (s.length > 200) s = s.substring(0, 200) + '…';
        return App.escapeHtml(s);
    }

    function cellRaw(v) {
        return v === null || v === undefined ? '' : String(v);
    }

    function sortVal(v) {
        if (v === null || v === undefined) return null;
        if (typeof v === 'number') return v;
        if (typeof v === 'boolean') return v ? 1 : 0;
        const s = String(v);
        const n = Number(s);
        if (s.trim() !== '' && !isNaN(n)) return n;
        return s;
    }

    function compare(a, b, dir) {
        if (a === null && b === null) return 0;
        if (a === null) return 1;         // NULL 恒排后
        if (b === null) return -1;
        if (typeof a === 'number' && typeof b === 'number') return (a - b) * dir;
        return String(a).localeCompare(String(b)) * dir;
    }

    function downloadCsv(name, columns, rows) {
        const esc = s => '"' + String(s === null || s === undefined ? '' : s).replace(/"/g, '""') + '"';
        const lines = [columns.map(esc).join(',')].concat(
            rows.map(r => columns.map(c => esc(cellRaw(r[c]))).join(',')));
        const blob = new Blob(['\ufeff' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = name;
        a.click();
        URL.revokeObjectURL(a.href);
    }

    /**
     * 渲染结果网格到 container。
     * @param container 选择器或 DOM 元素
     * @param data {columns, rows, rowCount, truncated}
     * @param opts {maxHeight: css 高度}
     */
    function render(container, data, opts) {
        const $c = $(container);
        if (!$c.length) return;
        opts = opts || {};
        const columns = (data && data.columns) || [];
        let rows = (data && data.rows) || [];
        const rowCount = data && data.rowCount !== undefined ? data.rowCount : rows.length;
        const truncated = !!(data && data.truncated);
        const state = { sortCol: null, dir: 1 };
        const maxHeight = opts.maxHeight || '360px';

        $c.html(
            '<div class="rg-wrap">' +
            '  <div class="rg-bar d-flex align-items-center gap-2 flex-wrap">' +
            '    <span class="rg-info text-muted small"></span>' +
            '    <span class="ms-auto d-flex gap-1">' +
            '      <button type="button" class="btn btn-outline-secondary btn-sm py-0 rg-copy-cell" title="' + App.escapeHtml(I18N.t('grid.copyCellHint')) + '">' + App.escapeHtml(I18N.t('grid.copyCell')) + '</button>' +
            '      <button type="button" class="btn btn-outline-secondary btn-sm py-0 rg-copy-all">' + App.escapeHtml(I18N.t('grid.copyAll')) + '</button>' +
            '      <button type="button" class="btn btn-outline-secondary btn-sm py-0 rg-csv">' + App.escapeHtml(I18N.t('grid.exportCsv')) + '</button>' +
            '    </span>' +
            '  </div>' +
            '  <div class="rg-scroll" style="max-height:' + maxHeight + ';overflow:auto;border:1px solid #dee2e6;border-radius:4px">' +
            '    <table class="table table-sm table-hover mb-0 rg-table"><thead class="table-light"></thead><tbody></tbody></table>' +
            '  </div>' +
            '</div>');

        const $thead = $c.find('thead');
        const $tbody = $c.find('tbody');
        const $info = $c.find('.rg-info');

        function renderHead() {
            let h = '<tr><th class="rg-rownum">#</th>';
            columns.forEach(function (col) {
                const arrow = state.sortCol === col ? (state.dir === 1 ? ' ▲' : ' ▼') : '';
                h += '<th class="rg-col text-user-select-none" data-col="' + App.escapeHtml(col) + '">' +
                    App.escapeHtml(col) + arrow + '</th>';
            });
            $thead.html(h + '</tr>');
        }

        function renderBody() {
            if (!columns.length) {
                $tbody.html('<tr><td class="text-center text-muted py-3">' + App.escapeHtml(I18N.t('grid.empty')) + '</td></tr>');
                return;
            }
            if (state.sortCol !== null) {
                rows = rows.slice().sort(function (r1, r2) {
                    return compare(sortVal(r1[state.sortCol]), sortVal(r2[state.sortCol]), state.dir);
                });
            }
            const body = rows.map(function (r, i) {
                let tr = '<tr class="rg-row"><td class="rg-rownum text-muted">' + (i + 1) + '</td>';
                columns.forEach(function (col) {
                    tr += '<td class="rg-cell" title="' + App.escapeHtml(cellRaw(r[col])) + '">' + fmtCell(r[col]) + '</td>';
                });
                return tr + '</tr>';
            });
            $tbody.html(body.length ? body.join('') :
                '<tr><td colspan="' + (columns.length + 1) + '" class="text-center text-muted py-3">' + App.escapeHtml(I18N.t('grid.empty')) + '</td></tr>');
        }

        function renderInfo() {
            const loaded = I18N.t('grid.loaded', { n: rows.length });
            const total = rowCount !== rows.length ? ' / ' + I18N.t('grid.total', { n: rowCount }) : '';
            const warn = truncated ? ' <span class="badge bg-warning text-dark">' + App.escapeHtml(I18N.t('grid.truncated')) + '</span>' : '';
            $info.html(loaded + total + warn);
        }

        $thead.on('click', '.rg-col', function () {
            const col = $(this).data('col');
            if (state.sortCol === col) state.dir = -state.dir;
            else { state.sortCol = col; state.dir = 1; }
            renderHead(); renderBody();
        });

        let lastCell = null; // 最近点击的单元格 DOM

        $tbody.on('click', '.rg-row', function () {
            $tbody.find('.rg-row').removeClass('table-active');
            $(this).addClass('table-active');
        });

        $tbody.on('click', '.rg-cell', function () {
            lastCell = this;
        });

        $tbody.on('dblclick', '.rg-cell', function () {
            copyText($(this).attr('title'));
            App.toast(I18N.t('grid.copied'));
        });

        $c.find('.rg-copy-cell').on('click', function () {
            if (!lastCell || !document.contains(lastCell)) {
                App.toast(I18N.t('grid.selectCellFirst'), 'warning');
                return;
            }
            copyText($(lastCell).attr('title'));
            App.toast(I18N.t('grid.copied'));
        });

        $c.find('.rg-copy-all').on('click', function () {
            const tsv = [columns.join('\t')].concat(
                rows.map(r => columns.map(c => cellRaw(r[c])).join('\t'))).join('\n');
            copyText(tsv);
            App.toast(I18N.t('grid.copied'));
        });

        $c.find('.rg-csv').on('click', function () {
            downloadCsv('query-result-' + Date.now() + '.csv', columns, rows);
        });

        function copyText(t) {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(t);
            } else {
                const ta = document.createElement('textarea');
                ta.value = t; document.body.appendChild(ta);
                ta.select(); document.execCommand('copy'); ta.remove();
            }
        }

        renderHead(); renderBody(); renderInfo();
    }

    App.resultGrid = render;
})();
