package com.sqlpipeline.web.controller;

import com.sqlpipeline.common.api.R;
import com.sqlpipeline.notify.entity.NotifyChannel;
import com.sqlpipeline.notify.entity.NotifyLog;
import com.sqlpipeline.notify.service.AlertService;
import com.sqlpipeline.notify.service.NotifyChannelService;
import com.sqlpipeline.web.dto.NotifyChannelSaveReq;
import com.sqlpipeline.web.dto.NotifyChannelView;
import com.sqlpipeline.web.util.Operator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notify")
@RequiredArgsConstructor
public class NotifyController {

    private final NotifyChannelService channelService;
    private final AlertService alertService;

    @GetMapping("/channels")
    public R<List<NotifyChannelView>> list() {
        return R.ok(channelService.list().stream().map(NotifyChannelView::from).toList());
    }

    @PostMapping("/channels")
    public R<Long> create(@Valid @RequestBody NotifyChannelSaveReq req,
                          @RequestHeader(value = Operator.HEADER, required = false) String operator) {
        NotifyChannel channel = toEntity(req);
        return R.ok(channelService.create(channel, req.secret(), Operator.of(operator)));
    }

    @PutMapping("/channels/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody NotifyChannelSaveReq req,
                          @RequestHeader(value = Operator.HEADER, required = false) String operator) {
        channelService.update(id, toEntity(req), req.secret(), Operator.of(operator));
        return R.ok();
    }

    @DeleteMapping("/channels/{id}")
    public R<Void> delete(@PathVariable Long id) {
        channelService.delete(id);
        return R.ok();
    }

    /** 同步发送一条 TEST 事件，返回推送结果（供界面"测试"按钮）。 */
    @PostMapping("/channels/{id}/test")
    public R<NotifyLog> test(@PathVariable Long id) {
        return R.ok(alertService.testChannel(id));
    }

    @GetMapping("/logs")
    public R<List<NotifyLog>> logs(@RequestParam(defaultValue = "50") int limit) {
        return R.ok(alertService.recentLogs(Math.min(Math.max(limit, 1), 200)));
    }

    private NotifyChannel toEntity(NotifyChannelSaveReq req) {
        NotifyChannel channel = new NotifyChannel();
        channel.setName(req.name());
        channel.setType(req.type() == null || req.type().isBlank() ? "XMATTERS" : req.type());
        channel.setUrl(req.url());
        channel.setAuthType(req.authType());
        channel.setUsername(req.username());
        channel.setAuthHeaderName(req.authHeaderName());
        channel.setEvents(req.events() == null || req.events().isEmpty()
                ? null : com.sqlpipeline.common.util.JsonUtils.toJson(req.events()));
        channel.setHcFailThreshold(req.hcFailThreshold());
        channel.setEnabled(req.enabled());
        return channel;
    }
}
