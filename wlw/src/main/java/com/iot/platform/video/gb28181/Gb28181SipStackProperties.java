package com.iot.platform.video.gb28181;

import java.util.Properties;

/**
 * JAIN-SIP 栈参数（对齐 wvp-GB28181-pro {@code DefaultProperties}，提升与海康等设备兼容性）。
 */
public final class Gb28181SipStackProperties {

    private Gb28181SipStackProperties() {
    }

    public static Properties create(String stackName) {
        Properties properties = new Properties();
        properties.setProperty("javax.sip.STACK_NAME", stackName);
        properties.setProperty("javax.sip.AUTOMATIC_DIALOG_SUPPORT", "off");
        properties.setProperty("gov.nist.javax.sip.DELIVER_UNSOLICITED_NOTIFY", "true");
        properties.setProperty("gov.nist.javax.sip.AUTOMATIC_DIALOG_ERROR_HANDLING", "false");
        properties.setProperty("gov.nist.javax.sip.CANCEL_CLIENT_TRANSACTION_CHECKED", "true");
        properties.setProperty("gov.nist.javax.sip.DELIVER_TERMINATED_EVENT_FOR_NULL_DIALOG", "true");
        properties.setProperty("gov.nist.javax.sip.COMPUTE_CONTENT_LENGTH_FROM_MESSAGE_BODY", "true");
        properties.setProperty("gov.nist.javax.sip.RELEASE_REFERENCES_STRATEGY", "Normal");
        properties.setProperty("gov.nist.javax.sip.RELIABLE_CONNECTION_KEEP_ALIVE_TIMEOUT", "60");
        properties.setProperty("gov.nist.javax.sip.REENTRANT_LISTENER", "true");
        properties.setProperty("gov.nist.javax.sip.THREAD_AUDIT_INTERVAL_IN_MILLISECS", "30000");
        properties.setProperty("gov.nist.javax.sip.CACHE_SERVER_CONNECTIONS", "true");
        properties.setProperty("gov.nist.javax.sip.MESSAGE_PROCESSOR_FACTORY",
                "gov.nist.javax.sip.stack.NioMessageProcessorFactory");
        properties.setProperty("gov.nist.javax.sip.MAX_MESSAGE_SIZE", "1048576");
        properties.setProperty("gov.nist.javax.sip.TRACE_LEVEL", "0");
        return properties;
    }
}
