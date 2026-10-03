package com.sqlpipeline.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 创建/更新告警通道请求。 */
public record NotifyChannelSaveReq(
        @NotBlank String name,
        String type,
        @NotBlank String url,
        String authType,
        String username,
        String authHeaderName,
        String secret,
        List<String> events,
        Integer hcFailThreshold,
        Integer enabled) {
}
