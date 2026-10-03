package com.sqlpipeline.notify.sender;

import com.sqlpipeline.notify.entity.NotifyChannel;

/** 告警发送器 SPI：按 channel.type 路由到具体实现。 */
public interface AlertSender {

    /** 对应 NotifyChannel.type，如 XMATTERS。 */
    String type();

    /**
     * 推送事件；抛出异常视为本次推送失败（由调用方记录 notify_log）。
     *
     * @param payloadJson 事件 JSON（含 event/severity/title/message 及业务属性）
     * @return 服务端响应摘要（HTTP 状态 + 截断响应体），用于 notify_log
     */
    String send(NotifyChannel channel, String payloadJson) throws Exception;
}
