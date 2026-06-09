package com.iot.platform.video.gb28181;

import javax.sip.SipFactory;

/**
 * JAIN-SIP 须在首次 createSipStack 前设置 PathName（gov.nist），否则 PeerUnavailableException。
 */
final class Gb28181SipFactoryInit {

    private static volatile boolean initialized;

    private Gb28181SipFactoryInit() {
    }

    static void ensure() {
        if (initialized) {
            return;
        }
        synchronized (Gb28181SipFactoryInit.class) {
            if (initialized) {
                return;
            }
            try {
                SipFactory sipFactory = SipFactory.getInstance();
                sipFactory.setPathName("gov.nist");
                sipFactory.createAddressFactory();
                initialized = true;
            } catch (Exception e) {
                throw new IllegalStateException("JAIN-SIP 初始化失败，请确认依赖 jain-sip-ri 已引入: " + e.getMessage(), e);
            }
        }
    }
}
