package com.sqlpipeline.notify.service;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.common.util.JsonUtils;
import com.sqlpipeline.notify.config.NotifyProperties;
import com.sqlpipeline.notify.entity.NotifyChannel;
import com.sqlpipeline.notify.entity.NotifyLog;
import com.sqlpipeline.notify.mapper.NotifyChannelMapper;
import com.sqlpipeline.notify.mapper.NotifyLogMapper;
import com.sqlpipeline.notify.sender.AlertSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static org.springframework.util.StringUtils.hasText;

/**
 * 告警分发服务：业务模块（健康检查/发布）通过 onXxx 钩子上报事件，
 * 按"通道订阅的事件类型 + 阈值过滤"匹配通道后异步推送；推送结果落 notify_log。
 * 任何告警链路异常都不影响业务主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final NotifyChannelMapper channelMapper;
    private final NotifyLogMapper logMapper;
    private final NotifyChannelService channelService;
    private final List<AlertSender> senders;
    private final ThreadPoolTaskExecutor alertExecutor;
    private final NotifyProperties properties;
    private final MessageSource messageSource;

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }

    /** 健康检查连续失败计数（内存态：重启清零，多实例各自计数）。 */
    private final ConcurrentHashMap<Long, Integer> hcFailStreak = new ConcurrentHashMap<>();

    // ---------- 业务钩子（参数保持简单类型，notify 模块不依赖业务模块） ----------

    /** 健康检查执行完成：FAIL/ERROR/TIMEOUT 计入连续失败，SUCCESS 清零；达到通道阈值时告警。 */
    /**
     * 健康检查执行完成：HC_FAIL 路由到该检查绑定的告警通道（未绑定=不告警），
     * 连败达到通道阈值触发一次（成功清零计数）；SUCCESS 清零。
     */
    public void onHealthCheckFinished(Long defId, String defName, String connKey, String status,
                                      Long runId, Long durationMs, String errorMsg, String assertMsg,
                                      Long notifyChannelId) {
        try {
            if (RunStatuses.SUCCESS.equals(status)) {
                hcFailStreak.remove(defId);
                return;
            }
            if (!RunStatuses.FAILED_STATUSES.contains(status)) {
                return;
            }
            int streak = hcFailStreak.merge(defId, 1, Integer::sum);
            if (notifyChannelId == null) {
                log.debug("健康检查失败但未绑定告警通道，跳过推送: defId={}, streak={}", defId, streak);
                return;
            }
            NotifyChannel channel = channelMapper.selectById(notifyChannelId);
            if (channel == null || channel.getEnabled() == null || channel.getEnabled() != 1) {
                log.warn("绑定的告警通道不存在或已停用，跳过推送: defId={}, channelId={}", defId, notifyChannelId);
                return;
            }
            // 连败阈值语义：连续失败次数恰好达到通道阈值时告警一次；阈值 1 = 每次失败即告警
            Integer threshold = channel.getHcFailThreshold();
            if (threshold != null && threshold > 1 && threshold != streak) {
                return;
            }
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("defId", defId);
            props.put("defName", defName);
            props.put("connKey", connKey);
            props.put("status", status);
            props.put("consecutiveFailCount", streak);
            props.put("runId", runId);
            props.put("durationMs", durationMs);
            if (hasText(errorMsg)) {
                props.put("errorMsg", errorMsg);
            }
            if (hasText(assertMsg)) {
                props.put("assertMsg", assertMsg);
            }
            // 只推送到该检查绑定的通道（绕过事件订阅广播）
            String title = msg("notify.alert.hcFail", streak, defName);
            String message = buildFailMessage(status, errorMsg, assertMsg);
            pushToChannel(channel, AlertEvents.HC_FAIL, "WARN", title, message, props);
        } catch (Exception e) {
            log.error("健康检查告警处理失败: defId={}", defId, e);
        }
    }

    public void onReleaseStepFail(Long planId, String planName, String crNumber, Integer stepNo,
                                  String connKey, Integer retryCount, Long durationMs, String errorMsg) {
        try {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("planId", planId);
            props.put("planName", planName);
            props.put("crNumber", crNumber);
            props.put("stepNo", stepNo);
            props.put("connKey", connKey);
            props.put("retryCount", retryCount);
            props.put("durationMs", durationMs);
            if (hasText(errorMsg)) {
                props.put("errorMsg", errorMsg);
            }
            dispatch(AlertEvents.RELEASE_STEP_FAIL, "CRITICAL",
                    msg("notify.alert.stepFail", planName, stepNo),
                    hasText(errorMsg) ? errorMsg : msg("notify.alert.stepFailDefault"), props, ch -> true);
        } catch (Exception e) {
            log.error("发布步骤告警处理失败: planId={}", planId, e);
        }
    }

    public void onPlanFail(Long planId, String planName, String crNumber, String operator) {
        try {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("planId", planId);
            props.put("planName", planName);
            props.put("crNumber", crNumber);
            props.put("operator", operator);
            dispatch(AlertEvents.PLAN_FAIL, "CRITICAL",
                    msg("notify.alert.planFail", planName),
                    msg("notify.alert.planFailMsg", planName, crNumber), props, ch -> true);
        } catch (Exception e) {
            log.error("发布计划告警处理失败: planId={}", planId, e);
        }
    }

    /** 发布步骤成功事件（供通道按需订阅）。 */
    public void onReleaseStepSuccess(Long planId, String planName, String crNumber, Integer stepNo,
                                     String connKey, Long durationMs) {
        try {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("planId", planId);
            props.put("planName", planName);
            props.put("crNumber", crNumber);
            props.put("stepNo", stepNo);
            props.put("connKey", connKey);
            props.put("durationMs", durationMs);
            dispatch(AlertEvents.RELEASE_STEP_SUCCESS, "INFO",
                    msg("notify.alert.stepSuccess", planName, stepNo),
                    msg("notify.alert.stepSuccessMsg", planName, stepNo), props, ch -> true);
        } catch (Exception e) {
            log.error("发布步骤成功事件处理失败: planId={}", planId, e);
        }
    }

    /** 发布计划完成事件（供通道按需订阅）。 */
    public void onPlanCompleted(Long planId, String planName, String crNumber, String operator) {
        try {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("planId", planId);
            props.put("planName", planName);
            props.put("crNumber", crNumber);
            props.put("operator", operator);
            dispatch(AlertEvents.PLAN_COMPLETED, "INFO",
                    msg("notify.alert.planCompleted", planName),
                    msg("notify.alert.planCompletedMsg", planName, crNumber), props, ch -> true);
        } catch (Exception e) {
            log.error("发布计划完成事件处理失败: planId={}", planId, e);
        }
    }

    /** 最近的推送日志（新→旧）。 */
    public List<NotifyLog> recentLogs(int limit) {
        return logMapper.selectRecent(limit);
    }

    // ---------- 通道测试（同步执行，返回本次推送结果） ----------

    public NotifyLog testChannel(Long channelId) {
        NotifyChannel channel = channelMapper.selectById(channelId);
        if (channel == null) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.notify.channelNotFound", channelId);
        }
        Map<String, Object> payload = buildPayload(AlertEvents.TEST, "INFO",
                msg("notify.alert.testTitle"), msg("notify.alert.testMessage", channel.getName()), Map.of());
        return doSend(channel, AlertEvents.TEST, msg("notify.alert.testTitle"), JsonUtils.toJson(payload));
    }

    // ---------- 内部 ----------

    private String buildFailMessage(String status, String errorMsg, String assertMsg) {
        if (hasText(errorMsg)) {
            return "status=" + status + ", error=" + errorMsg;
        }
        if (hasText(assertMsg)) {
            return "status=" + status + ", assert=" + assertMsg;
        }
        return "status=" + status;
    }

    private void dispatch(String event, String severity, String title, String message,
                          Map<String, Object> props, Predicate<NotifyChannel> extraFilter) {
        if (!properties.isEnabled()) {
            return;
        }
        List<NotifyChannel> channels;
        try {
            channels = channelMapper.selectEnabled();
        } catch (Exception e) {
            log.error("告警通道查询失败", e);
            return;
        }
        if (channels.isEmpty()) {
            return;
        }
        String payloadJson = JsonUtils.toJson(buildPayload(event, severity, title, message, props));
        for (NotifyChannel channel : channels) {
            if (!subscribes(channel, event) || !extraFilter.test(channel)) {
                continue;
            }
            submitPush(channel, event, title, payloadJson);
        }
    }

    /** 定向推送：提交到异步线程池并落 notify_log（HC_FAIL 按绑定的频道路由用）。 */
    private void pushToChannel(NotifyChannel channel, String event, String severity,
                               String title, String message, Map<String, Object> props) {
        if (!properties.isEnabled()) {
            return;
        }
        Map<String, Object> payload = buildPayload(event, severity, title, message, props);
        submitPush(channel, event, title, JsonUtils.toJson(payload));
    }

    private void submitPush(NotifyChannel channel, String event, String title, String payloadJson) {
        try {
            alertExecutor.execute(() -> doSend(channel, event, title, payloadJson));
        } catch (Exception e) {
            log.error("告警任务提交失败: channel={}", channel.getName(), e);
        }
    }

    private boolean subscribes(NotifyChannel channel, String event) {
        return channelService.parseEvents(channel.getEvents()).contains(event);
    }

    private Map<String, Object> buildPayload(String event, String severity, String title,
                                             String message, Map<String, Object> props) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "sql-pipeline");
        payload.put("event", event);
        payload.put("severity", severity);
        payload.put("title", title);
        payload.put("message", message);
        payload.put("timestamp", LocalDateTime.now().toString());
        if (props != null) {
            payload.putAll(props);
        }
        return payload;
    }

    private NotifyLog doSend(NotifyChannel channel, String event, String title, String payloadJson) {
        NotifyLog logRow = new NotifyLog();
        logRow.setChannelId(channel.getId());
        logRow.setChannelName(channel.getName());
        logRow.setEvent(event);
        logRow.setTitle(title);
        logRow.setPayload(payloadJson);
        try {
            AlertSender sender = senders.stream()
                    .filter(s -> s.type().equals(channel.getType()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("无 " + channel.getType() + " 类型的发送器"));
            String summary = sender.send(channel, payloadJson);
            logRow.setStatus("SUCCESS");
            logRow.setResponseBody(summary);
        } catch (Exception e) {
            logRow.setStatus("FAIL");
            logRow.setErrorMsg(JsonUtils.truncate(e.getMessage(), 1000));
            log.warn("告警推送失败: channel={}, event={}, err={}", channel.getName(), event, e.getMessage());
        }
        try {
            logMapper.insert(logRow);
        } catch (Exception ex) {
            log.error("告警日志写入失败: channel={}", channel.getName(), ex);
        }
        return logRow;
    }
}
