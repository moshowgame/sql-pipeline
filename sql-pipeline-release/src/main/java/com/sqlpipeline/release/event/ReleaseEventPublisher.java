package com.sqlpipeline.release.event;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 发布进度 SSE 推送：按 planId 维护订阅者，推送 step / plan 状态事件。 */
@Component
public class ReleaseEventPublisher {

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long planId) {
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> list = emitters.computeIfAbsent(planId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        emitter.onError(e -> list.remove(emitter));
        sendTo(emitter, "hello", Map.of("planId", planId, "ts", System.currentTimeMillis()));
        return emitter;
    }

    public void sendStep(Long planId, Object payload) {
        broadcast(planId, "step", payload);
    }

    public void sendPlan(Long planId, Object payload) {
        broadcast(planId, "plan", payload);
    }

    private void broadcast(Long planId, String event, Object payload) {
        List<SseEmitter> list = emitters.get(planId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(event).data(payload));
            } catch (Exception e) {
                list.remove(emitter);
            }
        }
    }

    private void sendTo(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload));
        } catch (Exception ignore) {
            // 订阅者初始快照发送失败不阻塞流程
        }
    }
}
