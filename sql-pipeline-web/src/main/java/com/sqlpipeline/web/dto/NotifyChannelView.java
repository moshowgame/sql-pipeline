package com.sqlpipeline.web.dto;

import com.sqlpipeline.notify.entity.NotifyChannel;

import java.time.LocalDateTime;

/** 告警通道视图：凭据仅返回掩码。 */
public record NotifyChannelView(Long id, String name, String type, String url, String authType,
                                String username, String authHeaderName, String secretMask,
                                String events, Integer hcFailThreshold, Integer enabled,
                                String createdBy, LocalDateTime createdAt,
                                String updatedBy, LocalDateTime updatedAt) {

    private static final String MASK = "******";

    public static NotifyChannelView from(NotifyChannel e) {
        return new NotifyChannelView(e.getId(), e.getName(), e.getType(), e.getUrl(), e.getAuthType(),
                e.getUsername(), e.getAuthHeaderName(),
                e.getSecretEnc() == null ? null : MASK, e.getEvents(), e.getHcFailThreshold(), e.getEnabled(),
                e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedBy(), e.getUpdatedAt());
    }
}
