package com.iot.platform.video.gb28181;

import org.springframework.stereotype.Component;

/** 应用启动时尽早初始化 JAIN-SIP PathName。 */
@Component
class Gb28181SipEarlyInit {

    Gb28181SipEarlyInit() {
        Gb28181SipFactoryInit.ensure();
    }
}
