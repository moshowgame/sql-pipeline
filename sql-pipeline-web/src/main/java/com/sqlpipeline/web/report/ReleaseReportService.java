package com.sqlpipeline.web.report;

import com.sqlpipeline.release.entity.ReleasePlan;
import com.sqlpipeline.release.entity.ReleaseRunSummary;
import com.sqlpipeline.release.entity.ReleaseSqlLog;
import com.sqlpipeline.release.entity.ReleaseStep;
import com.sqlpipeline.release.orchestrator.ReleaseOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 发布执行报告（自包含单文件 HTML，无外部依赖，可直接归档/邮件转发）：
 * 计划汇总 + 步骤状态 + 全流程重跑统计 + SQL 明细日志（含失败指纹 errorMsg）。
 */
@Service
@RequiredArgsConstructor
public class ReleaseReportService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReleaseOrchestrator orchestrator;

    /** 生成自包含 HTML 报告（UTF-8）。 */
    public String buildHtml(Long planId) {
        ReleasePlan plan = orchestrator.getPlan(planId);
        List<ReleaseStep> steps = orchestrator.listSteps(planId);
        List<ReleaseRunSummary> summaries = orchestrator.summaries(planId);
        Integer latestRunSeq = plan.getRerunCount() == null ? 1 : plan.getRerunCount() + 1;
        List<ReleaseSqlLog> logs = orchestrator.logs(planId, null, latestRunSeq, null);

        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">")
          .append("<title>Release Report #").append(plan.getId()).append(" ").append(esc(plan.getPlanName())).append("</title>")
          .append(CSS).append("</head><body><div class=\"wrap\">");
        header(sb, plan);
        planInfo(sb, plan);
        stepsTable(sb, steps);
        summariesTable(sb, summaries);
        logsTable(sb, logs);
        sb.append("<p class=\"foot\">sql-pipeline release report · generated at ")
          .append(FMT.format(LocalDateTime.now())).append("</p></div></body></html>");
        return sb.toString();
    }

    private void header(StringBuilder sb, ReleasePlan p) {
        sb.append("<h1>Release Execution Report <span class=\"zh\">发布执行报告</span></h1>")
          .append("<p class=\"sub\">#").append(p.getId()).append(" <b>").append(esc(p.getPlanName())).append("</b> ")
          .append(statusBadge(p.getStatus()));
        if (Boolean.TRUE.equals(p.getDryRun())) {
            sb.append(" <span class=\"badge\" style=\"background:#7c3aed\">DRY RUN 预演</span>");
        }
        if (p.getCrNumber() != null && !p.getCrNumber().isEmpty()) {
            sb.append(" · CR: ").append(esc(p.getCrNumber()));
        }
        sb.append("</p>");
    }

    private void planInfo(StringBuilder sb, ReleasePlan p) {
        row2(sb,
                "Plan Type / 发布类型", esc(p.getReleaseType() == null ? "FOLDER" : p.getReleaseType()),
                "Release Path / 发布路径", esc(p.getReleasePath()));
        row2(sb,
                "Default Conn / 默认连接", esc(p.getDefaultConnKey()),
                "Operator / 操作人", esc(p.getOperator()));
        row2(sb,
                "Started At / 开始时间", fmtTime(p.getStartedAt()),
                "Finished At / 结束时间", fmtTime(p.getFinishedAt()));
        row2(sb,
                "Rerun Count / 重跑轮次", String.valueOf(p.getRerunCount() == null ? 0 : p.getRerunCount()),
                "Remark / 备注", esc(p.getRemark()));
    }

    private void stepsTable(StringBuilder sb, List<ReleaseStep> steps) {
        sb.append("<h2>Steps / 步骤状态</h2>")
          .append("<table><thead><tr><th>#</th><th>Dir / 目录</th><th>Conn / 连接</th><th>Status / 状态</th>")
          .append("<th>Duration / 耗时</th><th>Retry / 重试</th><th>Executor / 执行人</th><th>Verify</th><th>Error / 错误</th></tr></thead><tbody>");
        if (steps.isEmpty()) {
            sb.append("<tr><td colspan=\"9\" class=\"muted\">-</td></tr>");
        }
        for (ReleaseStep s : steps) {
            sb.append("<tr><td>").append(s.getStepNo()).append("</td>")
              .append("<td class=\"mono\">").append(esc(s.getDirPath())).append("</td>")
              .append("<td class=\"mono\">").append(esc(s.getConnKey())).append("</td>")
              .append("<td>").append(statusBadge(s.getStatus())).append("</td>")
              .append("<td>").append(fmtMs(s.getDurationMs())).append("</td>")
              .append("<td>").append(s.getRetryCount() == null ? 0 : s.getRetryCount()).append("</td>")
              .append("<td>").append(esc(s.getExecutor())).append("</td>")
              .append("<td>").append(s.getVerifySql() != null && !s.getVerifySql().isEmpty() ? "🛡" : "-").append("</td>")
              .append("<td class=\"err\">").append(esc(s.getErrorMsg())).append("</td></tr>");
        }
        sb.append("</tbody></table>");
    }

    private void summariesTable(StringBuilder sb, List<ReleaseRunSummary> summaries) {
        sb.append("<h2>Run Summaries / 全流程计时</h2>")
          .append("<table><thead><tr><th>Run</th><th>Started At / 开始</th><th>Finished At / 结束</th>")
          .append("<th>Total / 总耗时</th><th>Operator / 操作人</th><th>Step Detail / 步骤明细</th></tr></thead><tbody>");
        if (summaries.isEmpty()) {
            sb.append("<tr><td colspan=\"6\" class=\"muted\">-</td></tr>");
        }
        for (ReleaseRunSummary s : summaries) {
            sb.append("<tr><td>#").append(s.getRunSeq()).append("</td>")
              .append("<td>").append(fmtTime(s.getStartedAt())).append("</td>")
              .append("<td>").append(fmtTime(s.getFinishedAt())).append("</td>")
              .append("<td>").append(fmtMs(s.getTotalMs())).append("</td>")
              .append("<td>").append(esc(s.getOperator())).append("</td>")
              .append("<td class=\"mono\">").append(esc(s.getStepDetail())).append("</td></tr>");
        }
        sb.append("</tbody></table>");
    }

    private void logsTable(StringBuilder sb, List<ReleaseSqlLog> logs) {
        sb.append("<h2>SQL Logs (latest run) / SQL 明细（最新一轮）</h2>")
          .append("<table><thead><tr><th>Step</th><th>Seq</th><th>File / 文件</th><th>Operator / 操作人</th>")
          .append("<th>Status / 状态</th><th>Duration / 耗时</th><th>Executed At / 执行时间</th><th>SQL</th><th>Error / 错误</th></tr></thead><tbody>");
        if (logs.isEmpty()) {
            sb.append("<tr><td colspan=\"9\" class=\"muted\">-</td></tr>");
        }
        for (ReleaseSqlLog l : logs) {
            sb.append("<tr><td>").append(l.getStepNo()).append("</td>")
              .append("<td>").append(l.getSeq()).append("</td>")
              .append("<td class=\"mono\">").append(esc(l.getFileName())).append("</td>")
              .append("<td>").append(esc(l.getOperator())).append("</td>")
              .append("<td>").append(statusBadge(l.getStatus())).append("</td>")
              .append("<td>").append(fmtMs(l.getDurationMs())).append("</td>")
              .append("<td>").append(fmtTime(l.getExecutedAt())).append("</td>")
              .append("<td class=\"mono sql\">").append(esc(l.getSqlPreview())).append("</td>")
              .append("<td class=\"err\">").append(esc(l.getErrorMsg())).append("</td></tr>");
        }
        sb.append("</tbody></table>");
    }

    private void row2(StringBuilder sb, String k1, String v1, String k2, String v2) {
        sb.append("<div class=\"grid\"><div class=\"k\">").append(k1).append("</div><div class=\"v\">").append(v1)
          .append("</div><div class=\"k\">").append(k2).append("</div><div class=\"v\">").append(v2).append("</div></div>");
    }

    private String statusBadge(String status) {
        if (status == null) return "<span class=\"badge\">-</span>";
        String color;
        switch (status) {
            case "SUCCESS": case "PLANNED": color = "#16a34a"; break;
            case "FAIL": color = "#dc2626"; break;
            case "RUNNING": color = "#4f46e5"; break;
            case "WAITING_CONTINUE": color = "#d97706"; break;
            case "SKIPPED": color = "#6b7280"; break;
            default: color = "#3b82f6"; break;
        }
        return "<span class=\"badge\" style=\"background:" + color + "\">" + esc(status) + "</span>";
    }

    private static String esc(String s) {
        if (s == null) return "-";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String fmtTime(LocalDateTime t) {
        return t == null ? "-" : FMT.format(t);
    }

    private static String fmtMs(Long ms) {
        if (ms == null) return "-";
        if (ms < 1000) return ms + " ms";
        if (ms < 60_000) return String.format("%.2f s", ms / 1000.0);
        return String.format("%d m %d s", ms / 60_000, (ms % 60_000) / 1000);
    }

    private static final String CSS =
            "<style>" +
            "body{font-family:-apple-system,'Segoe UI','Microsoft YaHei',sans-serif;color:#1f2937;margin:0;background:#f3f4f6}" +
            ".wrap{max-width:1200px;margin:24px auto;padding:32px;background:#fff;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.1)}" +
            "h1{font-size:22px;margin:0 0 4px}.zh{font-weight:400;color:#6b7280;font-size:16px;margin-left:8px}" +
            "h2{font-size:16px;margin:28px 0 8px;padding-bottom:6px;border-bottom:1px solid #e5e7eb}" +
            ".sub{color:#6b7280;margin:0 0 20px}.grid{display:grid;grid-template-columns:180px 1fr 180px 1fr;gap:4px 12px;margin-bottom:8px;font-size:13px}" +
            ".k{color:#6b7280}.v{word-break:break-all}" +
            "table{width:100%;border-collapse:collapse;font-size:12.5px;margin-bottom:8px}" +
            "th,td{border:1px solid #e5e7eb;padding:5px 8px;text-align:left;vertical-align:top}" +
            "th{background:#f9fafb;white-space:nowrap}" +
            ".mono{font-family:Consolas,Menlo,monospace;font-size:11.5px}.sql{max-width:360px;word-break:break-all}" +
            ".err{color:#dc2626;max-width:260px;word-break:break-all}.muted{color:#9ca3af;text-align:center}" +
            ".badge{color:#fff;border-radius:10px;padding:1px 8px;font-size:11px;white-space:nowrap}" +
            ".foot{color:#9ca3af;font-size:12px;margin-top:24px;text-align:center}" +
            "</style>";
}
