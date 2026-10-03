package com.sqlpipeline.notify.sender;

import com.sqlpipeline.common.util.JsonUtils;
import com.sqlpipeline.datasource.crypto.CryptoService;
import com.sqlpipeline.notify.config.NotifyProperties;
import com.sqlpipeline.notify.entity.NotifyChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.springframework.util.StringUtils.hasText;

/**
 * xMatters 发送器：POST JSON 到 xMatters 入站集成（Inbound Integration）或
 * Flow Designer 的 Webhook 触发地址，认证支持 Basic 与 API Key。
 * xMatters 侧在 Flow 中按需取用 payload 里的属性（event/severity/title/message/业务字段…）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XmattersSender implements AlertSender {

    public static final String TYPE = "XMATTERS";

    private final CryptoService cryptoService;
    private final NotifyProperties properties;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String send(NotifyChannel channel, String payloadJson) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(channel.getUrl()))
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8));

        applyAuth(builder, channel);

        HttpResponse<String> response =
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String summary = "HTTP " + response.statusCode() + ", body=" +
                JsonUtils.truncate(response.body(), 200);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("xMatters 返回非 2xx: " + summary);
        }
        return summary;
    }

    private void applyAuth(HttpRequest.Builder builder, NotifyChannel channel) {
        String authType = channel.getAuthType() == null ? NotifyChannel.AUTH_NONE : channel.getAuthType();
        switch (authType) {
            case NotifyChannel.AUTH_BASIC -> {
                if (!hasText(channel.getUsername()) || !hasText(channel.getSecretEnc())) {
                    throw new IllegalArgumentException("BASIC 认证需要配置用户名与密码");
                }
                String token = Base64.getEncoder().encodeToString(
                        (channel.getUsername() + ":" + cryptoService.decrypt(channel.getSecretEnc()))
                                .getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + token);
            }
            case NotifyChannel.AUTH_API_KEY -> {
                if (!hasText(channel.getSecretEnc())) {
                    throw new IllegalArgumentException("API_KEY 认证需要配置 API Key");
                }
                String header = hasText(channel.getAuthHeaderName()) ? channel.getAuthHeaderName() : "apikey";
                builder.header(header, cryptoService.decrypt(channel.getSecretEnc()));
            }
            default -> {
                // NONE：xMatters 公开 Webhook 触发地址
            }
        }
    }
}
