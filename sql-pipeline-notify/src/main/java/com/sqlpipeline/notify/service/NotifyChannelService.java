package com.sqlpipeline.notify.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.common.util.JsonUtils;
import com.sqlpipeline.datasource.crypto.CryptoService;
import com.sqlpipeline.notify.config.NotifyProperties;
import com.sqlpipeline.notify.entity.NotifyChannel;
import com.sqlpipeline.notify.mapper.NotifyChannelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.util.StringUtils.hasText;

/** 告警通道管理：CRUD + 凭据加密 + 连通性测试。 */
@Service
@RequiredArgsConstructor
public class NotifyChannelService {

    private static final TypeReference<List<String>> EVENT_LIST = new TypeReference<>() {
    };

    private final NotifyChannelMapper mapper;
    private final CryptoService cryptoService;
    private final NotifyProperties properties;

    public Long create(NotifyChannel channel, String plainSecret, String operator) {
        validate(channel, plainSecret, true);
        if (hasText(plainSecret)) {
            channel.setSecretEnc(cryptoService.encrypt(plainSecret));
        } else {
            channel.setSecretEnc(null);
        }
        fillDefaults(channel);
        channel.setCreatedBy(operator);
        channel.setUpdatedBy(operator);
        mapper.insert(channel);
        return channel.getId();
    }

    /** secret 留空表示保持不变；传空串清除凭据（仅 NONE 认证时合理）。 */
    public void update(Long id, NotifyChannel patch, String plainSecret, String operator) {
        NotifyChannel old = requireExists(id);
        validate(patch, hasText(plainSecret) ? plainSecret : null, false);
        old.setName(patch.getName());
        old.setType(patch.getType());
        old.setUrl(patch.getUrl());
        old.setAuthType(patch.getAuthType());
        old.setUsername(patch.getUsername());
        old.setAuthHeaderName(patch.getAuthHeaderName());
        old.setEvents(patch.getEvents());
        old.setHcFailThreshold(patch.getHcFailThreshold());
        old.setEnabled(patch.getEnabled());
        if (plainSecret != null) {
            old.setSecretEnc(hasText(plainSecret) ? cryptoService.encrypt(plainSecret) : null);
        }
        old.setUpdatedBy(operator);
        mapper.update(old);
    }

    public void delete(Long id) {
        requireExists(id);
        mapper.deleteById(id);
    }

    public NotifyChannel get(Long id) {
        return requireExists(id);
    }

    public List<NotifyChannel> list() {
        return mapper.selectAll();
    }

    private void validate(NotifyChannel channel, String plainSecret, boolean isCreate) {
        if (!hasText(channel.getName()) || !hasText(channel.getUrl())) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.notify.nameUrlRequired");
        }
        if (!channel.getUrl().startsWith("http://") && !channel.getUrl().startsWith("https://")) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.notify.urlInvalid");
        }
        if (!NotifyChannel.TYPE_XMATTERS.equals(channel.getType())) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID,
                    "error.notify.typeUnsupported", channel.getType());
        }
        String authType = hasText(channel.getAuthType()) ? channel.getAuthType() : NotifyChannel.AUTH_NONE;
        if (!List.of(NotifyChannel.AUTH_NONE, NotifyChannel.AUTH_BASIC, NotifyChannel.AUTH_API_KEY).contains(authType)) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.notify.authTypeInvalid");
        }
        if (NotifyChannel.AUTH_BASIC.equals(authType) && isCreate && (!hasText(channel.getUsername()) || !hasText(plainSecret))) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.notify.basicRequired");
        }
        if (NotifyChannel.AUTH_API_KEY.equals(authType) && isCreate && !hasText(plainSecret)) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.notify.apiKeyRequired");
        }
        List<String> events = parseEvents(channel.getEvents());
        if (events.isEmpty()) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.notify.eventsRequired");
        }
    }

    private void fillDefaults(NotifyChannel channel) {
        if (!hasText(channel.getAuthType())) {
            channel.setAuthType(NotifyChannel.AUTH_NONE);
        }
        if (!hasText(channel.getEvents())) {
            channel.setEvents(JsonUtils.toJson(List.of(AlertEvents.HC_FAIL, AlertEvents.RELEASE_STEP_FAIL, AlertEvents.PLAN_FAIL)));
        }
        if (channel.getHcFailThreshold() == null || channel.getHcFailThreshold() < 1) {
            channel.setHcFailThreshold(1);
        }
        if (channel.getEnabled() == null) {
            channel.setEnabled(1);
        }
    }

    private NotifyChannel requireExists(Long id) {
        NotifyChannel channel = mapper.selectById(id);
        if (channel == null) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.notify.channelNotFound", id);
        }
        return channel;
    }

    /** 通道订阅校验工具（供 AlertService 复用）。 */
    public List<String> parseEvents(String eventsJson) {
        if (!hasText(eventsJson)) {
            return List.of();
        }
        List<String> events = JsonUtils.fromJson(eventsJson, EVENT_LIST, ErrorCode.SYS_INTERNAL);
        return events == null ? List.of() : events;
    }
}
