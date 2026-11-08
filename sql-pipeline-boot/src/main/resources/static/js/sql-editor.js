/**
 * SQL 编辑器封装（基于本地化 CodeMirror 5）：
 * - 语法高亮（PostgreSQL 关键字）+ 行号 + 括号匹配 + 当前行高亮
 * - Ctrl/Cmd+Enter 执行回调、Ctrl+/ 行注释、Ctrl+Space 智能补全、Ctrl+F 页内搜索
 * - 为空时 Tab 缩进；表/字段提示词可通过 tables 选项注入
 * 用法：
 *   const cm = SqlEditor.attach(document.getElementById('dSql'), { exec: fn, tables: {...} });
 *   SqlEditor.sync(cm);      // 回写 textarea（提交前）
 *   SqlEditor.refresh(cm);   // 在 Bootstrap modal shown 后调用
 */
const SqlEditor = (function () {

    const THEME_KEY = 'sqlEditorTheme';
    const LIGHT = 'default';
    const DARK = 'material-darker';

    function currentTheme() {
        return localStorage.getItem(THEME_KEY) === DARK ? DARK : LIGHT;
    }

    const DEFAULTS = {
        exec: null,          // Ctrl/Cmd+Enter 回调
        tables: null,        // 补全提示词 { tableName: ['col1','col2'], ... }
        dark: null,          // null=跟随 localStorage 偏好；true/false 显式指定
        readOnly: false
    };

    function hintFn(editor) {
        editor.showHint({
            hint: CodeMirror.hint.sql,
            completeSingle: false,
            tables: (editor.__hintTables) || {}
        });
    }

    function attach(textarea, opts) {
        if (!textarea || typeof CodeMirror === 'undefined') return null;
        const o = Object.assign({}, DEFAULTS, opts || {});
        const dark = o.dark === null ? (currentTheme() === DARK) : o.dark;
        const cm = CodeMirror.fromTextArea(textarea, {
            mode: 'text/x-pgsql',
            theme: dark ? DARK : LIGHT,
            lineNumbers: true,
            lineWrapping: true,
            matchBrackets: true,
            autoCloseBrackets: false,
            styleActiveLine: true,
            indentWithTabs: true,
            smartIndent: true,
            readOnly: o.readOnly,
            extraKeys: {
                'Ctrl-Enter': function (ed) { if (o.exec) o.exec(ed); },
                'Cmd-Enter': function (ed) { if (o.exec) o.exec(ed); },
                'Ctrl-Space': hintFn,
                'Ctrl-/': function (ed) { ed.execCommand('toggleComment'); },
                'Cmd-/': function (ed) { ed.execCommand('toggleComment'); },
                'Shift-Ctrl-Space': hintFn,
                'Alt-Space': hintFn,
                Tab: function (ed) {
                    if (ed.somethingSelected()) ed.indentSelection('add');
                    else ed.replaceSelection('\t', 'end');
                }
            }
        });
        cm.__hintTables = o.tables;
        cm.getWrapperElement().classList.add('sql-editor-box');
        cm.setSize('100%', null); // 高度由 CSS 控制
        // 编辑内容变化时输入字母自动唤起补全（不强制）
        cm.on('inputRead', function (ed, change) {
            if (change.text && change.text.length === 1 && /[a-zA-Z_"']/.test(change.text[0])) {
                const cur = ed.getCursor();
                const token = ed.getTokenAt(cur);
                if (token.string && token.string.length >= 2) hintFn(ed);
            }
        });
        return cm;
    }

    /** 将编辑器内容回写到原 textarea（表单提交前调用）。 */
    function sync(cm) {
        if (cm) cm.save();
    }

    /** Bootstrap modal 弹出后调用，避免渲染尺寸异常。 */
    function refresh(cm) {
        if (cm) setTimeout(function () { cm.refresh(); cm.focus(); }, 120);
    }

    /** 切换明暗主题并持久化偏好，返回是否暗色。 */
    function toggleTheme(cm) {
        const dark = cm.getOption('theme') !== DARK;
        cm.setOption('theme', dark ? DARK : LIGHT);
        localStorage.setItem(THEME_KEY, dark ? DARK : LIGHT);
        return dark;
    }

    /** 注入表/字段补全提示词：{ tableName: ['col1', ...], ... }。 */
    function setHintTables(cm, tables) {
        if (cm) cm.__hintTables = tables || {};
    }

    return { attach: attach, sync: sync, refresh: refresh, toggleTheme: toggleTheme, setHintTables: setHintTables };
})();
