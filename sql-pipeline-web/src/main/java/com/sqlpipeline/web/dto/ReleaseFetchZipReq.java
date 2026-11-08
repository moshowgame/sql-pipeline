package com.sqlpipeline.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Nexus 发布包下载请求：username + token 仅用于本次下载的 HTTP Basic Auth，
 * 不落库、不写日志；下载后 zip 缓存到 base-path/nexus-cache 并自动解压检测目录。
 */
public record ReleaseFetchZipReq(
        @NotBlank @Size(max = 512) String nexusUrl,
        @Size(max = 128) String username,
        @Size(max = 256) String token) {
}
