package com.sqlpipeline.release.zip;

import com.sqlpipeline.common.error.ErrorCode;
import com.sqlpipeline.common.exception.BizException;
import com.sqlpipeline.release.config.ReleaseProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

/**
 * Nexus 发布包下载器：
 * - HTTP Basic Auth（username + token），凭据仅在内存中使用，不落库不打日志
 * - 仅允许 http/https；302 重定向不跟随（避免凭据被转发到非预期主机），提示改用直连地址
 * - 大小限制 MAX_BYTES（Content-Length 预检 + 流式累计双重防护）
 * - 保存到 base-path/nexus-cache/&lt;sha256(url)&gt;.zip，同名包重复下载直接覆盖
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NexusZipFetcher {

    /** 下载大小上限（200MB）。 */
    private static final long MAX_BYTES = 200L * 1024 * 1024;
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final String NEXUS_CACHE_DIR = "nexus-cache";

    private final ReleaseProperties properties;

    /** 下载 zip 并返回本地缓存路径（releaseType=ZIP 的 release_path）。 */
    public String fetch(String nexusUrl, String username, String token) {
        URI uri = parseUri(nexusUrl);
        String basicAuth = Base64.getEncoder().encodeToString(
                ((username == null ? "" : username) + ":" + (token == null ? "" : token))
                        .getBytes(StandardCharsets.UTF_8));
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Basic " + basicAuth)
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<InputStream> resp;
        try {
            resp = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.rl.nexusDownloadFailed", brief(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.rl.nexusDownloadFailed", "interrupted");
        }
        int status = resp.statusCode();
        if (status == 401 || status == 403) {
            closeQuietly(resp);
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.rl.nexusAuthFailed", status);
        }
        if (status >= 300 && status < 400) {
            closeQuietly(resp);
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.rl.nexusRedirect", status);
        }
        if (status != 200) {
            closeQuietly(resp);
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.rl.nexusDownloadFailed", "HTTP " + status);
        }
        // Content-Length 预检（缺失时依赖流式累计检查）
        long contentLength = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (contentLength > MAX_BYTES) {
            closeQuietly(resp);
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.rl.nexusTooLarge", MAX_BYTES / 1024 / 1024);
        }
        Path target = cacheTarget(uri);
        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_BYTES) {
                    throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID,
                            "error.rl.nexusTooLarge", MAX_BYTES / 1024 / 1024);
                }
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.rl.nexusDownloadFailed", brief(e));
        }
        log.info("Nexus zip 下载完成: url={}, size={}, savedTo={}", nexusUrl, total0(target), target);
        return target.toString();
    }

    private void closeQuietly(HttpResponse<InputStream> resp) {
        try {
            resp.body().close();
        } catch (IOException ignored) {
            // 错误路径上关闭响应体失败可忽略
        }
    }

    private long total0(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    private Path cacheTarget(URI uri) {
        try {
            Path cacheRoot = Paths.get(properties.getBasePath()).toAbsolutePath().normalize()
                    .resolve(NEXUS_CACHE_DIR);
            Files.createDirectories(cacheRoot);
            return cacheRoot.resolve(ZipExtractor.sha256Hex(uri.toString()) + ".zip");
        } catch (IOException e) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.rl.basePathInvalid",
                    properties.getBasePath());
        }
    }

    /** URL 校验：仅 http/https 且 host 非空。 */
    private URI parseUri(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.rl.nexusInvalidUrl", url);
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!ALLOWED_SCHEMES.contains(scheme) || uri.getHost() == null) {
                throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.rl.nexusInvalidUrl", url);
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw BizException.i18n(ErrorCode.SYS_PARAM_INVALID, "error.rl.nexusInvalidUrl", url);
        }
    }

    private String brief(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName()
                : (m.length() > 300 ? m.substring(0, 300) + "..." : m);
    }
}
