package com.iot.platform.protocol.mqtt;

/**
 * 网关档案变更后触发 MQTT 订阅同步/断开，避免 RegistryService 与 SubscriptionManager 循环依赖。
 */
public class GatewayMqttSubscriptionEvent {

    public enum Action {
        SYNC,
        REMOVE
    }

    private final Long gatewayId;
    private final Action action;

    public GatewayMqttSubscriptionEvent(Long gatewayId, Action action) {
        this.gatewayId = gatewayId;
        this.action = action;
    }

    public Long getGatewayId() {
        return gatewayId;
    }

    public Action getAction() {
        return action;
    }
}
