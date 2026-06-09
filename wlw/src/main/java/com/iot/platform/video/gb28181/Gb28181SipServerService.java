package com.iot.platform.video.gb28181;

import com.iot.platform.video.gb28181.entity.Gb28181PlatformConfig;
import com.iot.platform.video.gb28181.parser.Gb28181StringMsgParserFactory;
import com.iot.platform.video.gb28181.service.Gb28181PlatformConfigService;
import gov.nist.javax.sip.SipStackImpl;
import gov.nist.javax.sip.message.SIPRequest;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import javax.sip.Dialog;
import javax.sip.DialogTerminatedEvent;
import javax.sip.IOExceptionEvent;
import javax.sip.InvalidArgumentException;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.SipFactory;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import javax.sip.TimeoutEvent;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.AuthorizationHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.CSeqHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.ViaHeader;
import javax.sip.header.WWWAuthenticateHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@Order(Ordered.HIGHEST_PRECEDENCE)
@org.springframework.context.annotation.DependsOn("gb28181DatabaseMigrator")
public class Gb28181SipServerService implements SipListener, ApplicationRunner {

    private static final AtomicInteger STACK_SEQ = new AtomicInteger();

    private static final Charset GB2312 = Charset.forName("GB2312");
    private static final Pattern SIP_USER = Pattern.compile("sip:([^@;>]+)", Pattern.CASE_INSENSITIVE);

    private final Gb28181PlatformConfigService platformConfigService;
    private final Gb28181DeviceRegistry deviceRegistry;
    private final Gb28181PlayService playService;
    private final Gb28181PlayManager playManager;

    @Getter
    private AddressFactory addressFactory;
    @Getter
    private HeaderFactory headerFactory;
    @Getter
    private MessageFactory messageFactory;

    private SipStack sipStack;
    private SipProvider udpSipProvider;
    private SipProvider tcpSipProvider;
    @Getter
    private volatile boolean udpListening;
    @Getter
    private volatile boolean tcpListening;
    private ScheduledExecutorService keepaliveScheduler;
    private final ConcurrentHashMap<String, String> registerNonces = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Gb28181PlayService.PendingInvite> pendingInvites = new ConcurrentHashMap<>();
    private final AtomicLong registerReceivedCount = new AtomicLong();
    private final AtomicBoolean sipInboundSeen = new AtomicBoolean();

    /** 自检用虚拟设备 ID，勿与真实摄像机编码相同 */
    private static final String SELF_TEST_DEVICE_ID = "34020000009999000001";

    @Getter
    private volatile String sipListenHost = "";
    @Getter
    private volatile Instant lastRegisterAt;
    @Getter
    private volatile String lastStartupError = "";

    public Gb28181SipServerService(
            Gb28181PlatformConfigService platformConfigService,
            Gb28181DeviceRegistry deviceRegistry,
            @Lazy Gb28181PlayService playService,
            @Lazy Gb28181PlayManager playManager) {
        this.platformConfigService = platformConfigService;
        this.deviceRegistry = deviceRegistry;
        this.playService = playService;
        this.playManager = playManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        restartFromDatabase();
    }

    public long getRegisterReceivedCount() {
        return registerReceivedCount.get();
    }

    /** REGISTER 401 时下发的 nonce，供 INVITE 鉴权重试。 */
    public String getRegisterNonce(String deviceId) {
        if (deviceId == null) {
            return null;
        }
        return registerNonces.get(deviceId.trim());
    }

    @PreDestroy
    public void stop() {
        shutdownStack();
    }

    /** 保存平台配置后调用，按数据库重新启停 SIP */
    public synchronized void restartFromDatabase() {
        shutdownStack();
        lastStartupError = "";
        Gb28181PlatformConfig cfg = platformConfigService.getOrCreate();
        if (!cfg.isEnabled()) {
            log.info("GB/T 28181 未启用（请在控制台「国标 28181」页启用并保存）");
            return;
        }
        if (!platformConfigService.isStackIpValid(cfg)) {
            lastStartupError = "media-host 无效: " + cfg.getMediaHost();
            log.error("GB/T 28181 SIP 未启动：媒体地址 media-host 无效（当前为「{}」）。"
                            + "请在「国标 28181」页填写本机局域网 IPv4，例如 192.168.0.113，勿填 admin、用户名或域名。",
                    cfg.getMediaHost());
            return;
        }
        try {
            Gb28181SipFactoryInit.ensure();
            SipFactory sipFactory = SipFactory.getInstance();
            sipFactory.setPathName("gov.nist");
            addressFactory = sipFactory.createAddressFactory();
            headerFactory = sipFactory.createHeaderFactory();
            messageFactory = sipFactory.createMessageFactory();

            final String stackIp = platformConfigService.resolveSipStackIp(cfg);
            final int sipPort = cfg.getPort();
            registerReceivedCount.set(0);
            lastRegisterAt = null;
            sipInboundSeen.set(false);

            String preferredBind = platformConfigService.resolveSipUdpBindHost(cfg);
            String actualBind = startListening(sipFactory, cfg, stackIp, preferredBind, sipPort);
            sipListenHost = actualBind;

            keepaliveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "gb28181-keepalive");
                t.setDaemon(true);
                return t;
            });
            int timeout = cfg.getKeepaliveTimeoutSeconds();
            final String bindForLog = actualBind;
            keepaliveScheduler.scheduleAtFixedRate(
                    () -> deviceRegistry.refreshOnlineFlags(timeout),
                    30,
                    30,
                    TimeUnit.SECONDS);
            keepaliveScheduler.scheduleAtFixedRate(() -> {
                if (registerReceivedCount.get() == 0) {
                    log.warn("GB28181 仍未收到任何 REGISTER（已监听 {}:{} UDP={} TCP={}）。请检查：① 摄像机「SIP服务器地址」={}；"
                                    + "② 防火墙入站 UDP/TCP {}；③ 海康「传输协议」与平台一致；④ 启动日志「SIP 已启动」",
                            bindForLog, sipPort, udpListening, tcpListening, stackIp, sipPort);
                }
            }, 120, 120, TimeUnit.SECONDS);

            log.info("GB/T 28181 SIP 已启动 bind={}:{} udp={} tcp={} stackIp={} id={} domain={}",
                    actualBind, sipPort, udpListening, tcpListening, stackIp, cfg.getSipId(), cfg.getSipDomain());
            if (!udpListening && !tcpListening) {
                lastStartupError = "UDP/TCP 均未成功绑定";
                log.error("GB/T 28181 SIP 未监听任何端口，请检查 {}:{} 是否被占用", actualBind, sipPort);
                shutdownStack();
                return;
            }
            log.info("GB28181 等待摄像机 REGISTER → {}:{}（UDP{} TCP{}；摄像机「SIP服务器地址」须为 {}）",
                    stackIp, sipPort, udpListening ? "开" : "关", tcpListening ? "开" : "关", stackIp);
            final String testHost = stackIp;
            final String testDomain = cfg.getSipDomain();
            keepaliveScheduler.schedule(
                    () -> runUdpSelfTest(testHost, sipPort, testDomain),
                    5,
                    TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            lastStartupError = root.getMessage() != null ? root.getMessage() : e.toString();
            log.error("GB/T 28181 SIP 启动失败: {} — {}（若提示端口占用，请结束占用 UDP {} 的旧 Java 进程后重启）",
                    e.getMessage(), lastStartupError, cfg.getPort(), e);
        }
    }

    /**
     * 依次尝试绑定地址；同时监听 UDP 与 TCP（海康等设备可能走 TCP SIP）。
     * 对齐 wvp {@code SipLayer.addListeningPoint}。
     */
    private String startListening(
            SipFactory sipFactory, Gb28181PlatformConfig cfg, String stackIp, String preferredBind, int sipPort)
            throws Exception {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        ordered.add(stackIp);
        if (preferredBind != null && !preferredBind.isEmpty()) {
            ordered.add(preferredBind);
        }
        ordered.add("0.0.0.0");
        List<String> candidates = new ArrayList<>(ordered);
        Exception last = null;
        udpListening = false;
        tcpListening = false;
        for (String bindHost : candidates) {
            try {
                shutdownStackPartial();
                Properties props = Gb28181SipStackProperties.create("wlw-gb28181-" + STACK_SEQ.incrementAndGet());
                sipStack = sipFactory.createSipStack(props);
                if (sipStack instanceof SipStackImpl) {
                    ((SipStackImpl) sipStack).setMessageParserFactory(new Gb28181StringMsgParserFactory());
                }
                udpListening = bindTransport(sipStack, bindHost, sipPort, "udp");
                tcpListening = bindTransport(sipStack, bindHost, sipPort, "tcp");
                if (udpListening || tcpListening) {
                    if (!bindHost.equals(candidates.get(0))) {
                        log.warn("GB28181 已绑定 {}:{}（曾尝试 {}）", bindHost, sipPort, candidates.get(0));
                    }
                    return bindHost;
                }
            } catch (Exception e) {
                last = e;
                log.warn("GB28181 尝试绑定 {}:{} 失败: {}", bindHost, sipPort, e.getMessage());
                shutdownStackPartial();
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IllegalStateException("GB28181 SIP 绑定失败");
    }

    private boolean bindTransport(SipStack stack, String bindHost, int port, String transport) {
        try {
            javax.sip.ListeningPoint lp = stack.createListeningPoint(bindHost, port, transport);
            SipProvider provider = stack.createSipProvider(lp);
            provider.addSipListener(this);
            if ("tcp".equalsIgnoreCase(transport)) {
                tcpSipProvider = provider;
                tcpListening = true;
                log.info("GB28181 SIP {}://{}:{} 已启动", transport, bindHost, port);
            } else {
                udpSipProvider = provider;
                udpListening = true;
                log.info("GB28181 SIP {}://{}:{} 已启动", transport, bindHost, port);
            }
            return true;
        } catch (Exception e) {
            log.warn("GB28181 {}://{}:{} 绑定失败: {}", transport, bindHost, port, e.getMessage());
            return false;
        }
    }

    /** 向本机 SIP 端口发送测试 REGISTER，区分「平台收不到 UDP」与「摄像机未发包」。 */
    private void runUdpSelfTest(String targetHost, int port, String sipDomain) {
        if (keepaliveScheduler == null || (!udpListening && !tcpListening)) {
            return;
        }
        sipInboundSeen.set(false);
        String callId = "wlw-selftest-" + System.currentTimeMillis() + "@127.0.0.1";
        String msg = "REGISTER sip:" + SELF_TEST_DEVICE_ID + "@" + sipDomain + " SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP 127.0.0.1:15080;branch=z9hG4bK-wlw-selftest\r\n"
                + "From: <sip:" + SELF_TEST_DEVICE_ID + "@" + sipDomain + ">;tag=wlwself\r\n"
                + "To: <sip:" + SELF_TEST_DEVICE_ID + "@" + sipDomain + ">\r\n"
                + "Call-ID: " + callId + "\r\n"
                + "CSeq: 1 REGISTER\r\n"
                + "Contact: <sip:" + SELF_TEST_DEVICE_ID + "@127.0.0.1:15080>\r\n"
                + "Max-Forwards: 70\r\n"
                + "Expires: 60\r\n"
                + "Content-Length: 0\r\n\r\n";
        try (DatagramSocket sender = new DatagramSocket()) {
            byte[] data = msg.getBytes(StandardCharsets.ISO_8859_1);
            sender.send(new DatagramPacket(data, data.length, InetAddress.getByName(targetHost), port));
            log.info("GB28181 已发送本机 UDP 自检 REGISTER → {}:{}", targetHost, port);
        } catch (Exception e) {
            log.warn("GB28181 自检包发送失败: {}", e.getMessage());
            return;
        }
        keepaliveScheduler.schedule(() -> {
            if (sipInboundSeen.get()) {
                log.info("GB28181 UDP 自检通过：平台能接收发往 {}:{} 的 SIP。若海康仍不在线，问题在摄像机侧（IP/网关/未保存国标配置）。",
                        targetHost, port);
            } else {
                log.error("GB28181 UDP 自检失败：发往 {}:{} 的 REGISTER 平台未收到（无「SIP 入站」）。"
                                + "请为 java.exe 添加入站允许，或暂时关闭防火墙测试。",
                        targetHost, port);
            }
        }, 2, TimeUnit.SECONDS);
    }

    private void shutdownStackPartial() {
        udpSipProvider = null;
        tcpSipProvider = null;
        udpListening = false;
        tcpListening = false;
        if (sipStack != null) {
            try {
                sipStack.stop();
            } catch (Exception e) {
                log.debug("GB28181 sipStack.stop: {}", e.getMessage());
            }
            sipStack = null;
        }
    }

    private void shutdownStack() {
        if (keepaliveScheduler != null) {
            keepaliveScheduler.shutdownNow();
            keepaliveScheduler = null;
        }
        shutdownStackPartial();
    }

    public boolean isRunning() {
        return udpListening || tcpListening;
    }

    public boolean isTcpListening() {
        return tcpListening;
    }

    public SipProvider requireProvider() {
        return requireProvider("UDP");
    }

    /** 按设备 REGISTER 时的传输协议选择 SipProvider（对齐 wvp）。 */
    public SipProvider requireProvider(String transport) {
        if ("TCP".equalsIgnoreCase(transport) && tcpSipProvider != null) {
            return tcpSipProvider;
        }
        if (udpSipProvider != null) {
            return udpSipProvider;
        }
        if (tcpSipProvider != null) {
            return tcpSipProvider;
        }
        throw new IllegalStateException("GB28181 SIP 未启动，请在「国标 28181」页启用并保存平台配置");
    }

    private SipProvider providerFromEvent(RequestEvent event) {
        if (event.getSource() instanceof SipProvider) {
            return (SipProvider) event.getSource();
        }
        return requireProvider();
    }

    void registerPendingInvite(String callId, Gb28181PlayService.PendingInvite pending) {
        pendingInvites.put(callId, pending);
    }

    void unregisterPendingInvite(String callId) {
        pendingInvites.remove(callId);
    }

    public Gb28181PlayService.PendingInvite getPendingInvite(String callId) {
        return pendingInvites.get(callId);
    }

    @Override
    public void processRequest(RequestEvent event) {
        sipInboundSeen.set(true);
        Request request = event.getRequest();
        String method = request.getMethod();
        log.info("GB28181 SIP 入站 method={}", method);
        try {
            if (Request.REGISTER.equals(method)) {
                handleRegister(event);
            } else if (Request.MESSAGE.equals(method)) {
                handleMessage(event);
            } else if (Request.ACK.equals(method)) {
                // no-op
            } else if (Request.BYE.equals(method)) {
                handleBye(event);
            } else {
                sendResponse(event, Response.NOT_IMPLEMENTED);
            }
        } catch (Exception e) {
            log.warn("处理 SIP {} 失败: {}", method, e.getMessage());
        }
    }

    @Override
    public void processResponse(ResponseEvent event) {
        Response response = event.getResponse();
        if (response == null) {
            return;
        }
        CSeqHeader cSeq = (CSeqHeader) response.getHeader(CSeqHeader.NAME);
        if (cSeq == null || !Request.INVITE.equals(cSeq.getMethod())) {
            return;
        }
        javax.sip.header.CallIdHeader callIdHeader =
                (javax.sip.header.CallIdHeader) response.getHeader(javax.sip.header.CallIdHeader.NAME);
        if (callIdHeader == null) {
            return;
        }
        Gb28181PlayService.PendingInvite pending = pendingInvites.get(callIdHeader.getCallId());
        if (pending == null) {
            return;
        }
        playService.handleInviteResponse(event, pending);
    }

    private void handleRegister(RequestEvent event) throws Exception {
        Request request = event.getRequest();
        logRegisterInbound(request);
        String deviceId = extractDeviceId(request);
        if (deviceId == null) {
            log.warn("GB28181 REGISTER 无法解析设备 ID（请检查 From/Contact）");
            sendResponse(event, Response.BAD_REQUEST);
            return;
        }
        if (SELF_TEST_DEVICE_ID.equals(deviceId)) {
            log.info("GB28181 自检 REGISTER 已到达 SIP 栈（不写入设备表）");
            sendResponse(event, Response.OK);
            return;
        }
        registerReceivedCount.incrementAndGet();
        lastRegisterAt = Instant.now();
        log.info("GB28181 收到 REGISTER deviceId={}（累计 {} 次）", deviceId, registerReceivedCount.get());
        Gb28181PlatformConfig cfg = platformConfigService.getOrCreate();
        ExpiresHeader expiresHeader = (ExpiresHeader) request.getHeader(ExpiresHeader.NAME);
        if (expiresHeader != null && expiresHeader.getExpires() == 0) {
            deviceRegistry.unregister(deviceId);
            sendResponse(event, Response.OK);
            log.info("GB28181 设备已注销 deviceId={}", deviceId);
            return;
        }
        AuthorizationHeader auth = (AuthorizationHeader) request.getHeader(AuthorizationHeader.NAME);
        String password = platformConfigService.resolveDevicePassword(deviceId);
        if (!org.springframework.util.StringUtils.hasText(password) && auth != null
                && org.springframework.util.StringUtils.hasText(auth.getUsername())) {
            password = platformConfigService.resolveDevicePassword(auth.getUsername());
        }

        if (auth == null) {
            if (password.isEmpty()) {
                acceptRegister(event, deviceId, request, cfg);
                return;
            }
            String nonce = Gb28181DigestHelper.newNonce();
            registerNonces.put(deviceId, nonce);
            Response challenge = messageFactory.createResponse(Response.UNAUTHORIZED, request);
            WWWAuthenticateHeader wwwDomain = headerFactory.createWWWAuthenticateHeader(
                    Gb28181DigestHelper.wwwAuthenticateHeader(cfg.getSipDomain(), nonce));
            challenge.addHeader(wwwDomain);
            if (org.springframework.util.StringUtils.hasText(cfg.getSipId())
                    && !cfg.getSipId().trim().equals(cfg.getSipDomain())) {
                WWWAuthenticateHeader wwwId = headerFactory.createWWWAuthenticateHeader(
                        Gb28181DigestHelper.wwwAuthenticateHeader(cfg.getSipId().trim(), nonce));
                challenge.addHeader(wwwId);
            }
            sendResponse(event, challenge);
            log.info("GB28181 REGISTER 已下发 401 挑战 deviceId={} realm={}（及 sipId realm 备选）",
                    deviceId, cfg.getSipDomain());
            return;
        }

        String nonce = registerNonces.get(deviceId);
        if (nonce == null || nonce.isEmpty()) {
            nonce = auth.getNonce();
        }
        String uri = auth.getURI() != null ? auth.getURI().toString() : request.getRequestURI().toString();
        boolean ok = password.isEmpty() || verifyRegisterDigest(auth, cfg, nonce, password, uri);
        if (!ok) {
            log.warn("GB28181 REGISTER 密码校验失败 deviceId={} qop={} realm={} uri={}（请核对密码；realm 须与摄像机一致）",
                    deviceId, auth.getQop(), auth.getRealm(), uri);
            sendResponse(event, Response.FORBIDDEN);
            return;
        }
        if (auth != null && org.springframework.util.StringUtils.hasText(auth.getNonce())) {
            registerNonces.put(deviceId, auth.getNonce());
        }
        acceptRegister(event, deviceId, request, cfg);
    }

    /** 海康等设备 Digest 的 realm 可能为 SIP 域或平台 SIP ID，按报文 realm 优先再回退。 */
    private boolean verifyRegisterDigest(
            AuthorizationHeader auth, Gb28181PlatformConfig cfg, String nonce, String password, String uri) {
        String realmInAuth = auth.getRealm();
        if (org.springframework.util.StringUtils.hasText(realmInAuth)) {
            if (Gb28181DigestHelper.verifyAuthorization(
                    Request.REGISTER, uri, auth, realmInAuth.trim(), nonce, password)) {
                return true;
            }
        }
        if (Gb28181DigestHelper.verifyAuthorization(
                Request.REGISTER, uri, auth, cfg.getSipDomain(), nonce, password)) {
            return true;
        }
        return org.springframework.util.StringUtils.hasText(cfg.getSipId())
                && Gb28181DigestHelper.verifyAuthorization(
                Request.REGISTER, uri, auth, cfg.getSipId().trim(), nonce, password);
    }

    private void acceptRegister(RequestEvent event, String deviceId, Request request, Gb28181PlatformConfig cfg)
            throws Exception {
        applyRemoteContact(event, deviceId, request);
        deviceRegistry.registerOrUpdate(deviceId);
        Gb28181DeviceSession dev = deviceRegistry.get(deviceId);
        if (dev != null) {
            dev.setSipTransport(resolveTransport(request));
            ExpiresHeader reqExpires = (ExpiresHeader) request.getHeader(ExpiresHeader.NAME);
            if (reqExpires != null && reqExpires.getExpires() > 0) {
                dev.setExpiresSeconds(reqExpires.getExpires());
            } else {
                dev.setExpiresSeconds(cfg.getRegisterExpires());
            }
        }
        Response ok = messageFactory.createResponse(Response.OK, request);
        ContactHeader contact = (ContactHeader) request.getHeader(ContactHeader.NAME);
        if (contact != null) {
            ok.addHeader(contact);
        }
        ExpiresHeader reqExpires = (ExpiresHeader) request.getHeader(ExpiresHeader.NAME);
        if (reqExpires != null) {
            ok.addHeader(reqExpires);
        } else {
            ok.addHeader(headerFactory.createExpiresHeader(cfg.getRegisterExpires()));
        }
        sendResponse(event, ok);
        log.info("GB28181 设备已注册 deviceId={} transport={}", deviceId,
                dev != null ? dev.getSipTransport() : "UDP");
        try {
            sendCatalogQuery(deviceId);
        } catch (Exception e) {
            log.debug("GB28181 Catalog 查询发送失败 device={}: {}", deviceId, e.toString());
        }
    }

    /** 向摄像机查询 Catalog，用于核对「视频通道编码 ID」。 */
    public void sendCatalogQuery(String deviceId) throws Exception {
        if (deviceId == null || deviceId.trim().isEmpty()) {
            return;
        }
        Gb28181DeviceSession dev = deviceRegistry.get(deviceId.trim());
        if (dev == null || !Gb28181NetUtil.isIpv4(dev.getContactHost())) {
            return;
        }
        Gb28181PlatformConfig cfg = platformConfigService.getOrCreate();
        String platformId = cfg.getSipId();
        String domain = cfg.getSipDomain();
        String mediaIp = platformConfigService.effectiveMediaHost(cfg);
        int sn = (int) (System.currentTimeMillis() % 100_000);
        String xml = Gb28181XmlHelper.catalogQuery(deviceId.trim(), sn);
        SipURI requestUri = addressFactory.createSipURI(deviceId.trim(), dev.getContactHost());
        if (dev.getContactPort() > 0) {
            requestUri.setPort(dev.getContactPort());
        }
        SipURI fromUri = addressFactory.createSipURI(platformId, domain);
        Address fromAddress = addressFactory.createAddress(fromUri);
        FromHeader fromHeader = headerFactory.createFromHeader(fromAddress, "tag" + sn);
        SipURI toUri = addressFactory.createSipURI(deviceId.trim(), domain);
        ToHeader toHeader = headerFactory.createToHeader(addressFactory.createAddress(toUri), null);
        CallIdHeader callId = headerFactory.createCallIdHeader("gb-cat-" + deviceId.trim() + "-" + sn);
        CSeqHeader cSeq = headerFactory.createCSeqHeader(1L, Request.MESSAGE);
        MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);
        ArrayList<javax.sip.header.ViaHeader> viaHeaders = new ArrayList<>();
        String stackIp = platformConfigService.resolveSipStackIp(cfg);
        viaHeaders.add(headerFactory.createViaHeader(stackIp, cfg.getPort(), "udp", null));
        Request message = messageFactory.createRequest(
                requestUri, Request.MESSAGE, callId, cSeq, fromHeader, toHeader, viaHeaders, maxForwards);
        SipURI contactUri = addressFactory.createSipURI(platformId, mediaIp);
        contactUri.setPort(cfg.getPort());
        message.addHeader(headerFactory.createContactHeader(addressFactory.createAddress(contactUri)));
        ContentTypeHeader ct = headerFactory.createContentTypeHeader("Application", "MANSCDP+xml");
        message.setContent(xml, ct);
        String transport = dev.getSipTransport() != null ? dev.getSipTransport() : "UDP";
        requireProvider(transport).getNewClientTransaction(message).sendRequest();
        log.info("GB28181 已发送 Catalog 查询 device={} via {}", deviceId.trim(), transport);
    }

    private void handleBye(RequestEvent event) throws Exception {
        sendResponse(event, Response.OK);
        Request request = event.getRequest();
        String deviceId = extractDeviceId(request);
        String callId = null;
        CallIdHeader callIdHeader = (CallIdHeader) request.getHeader(CallIdHeader.NAME);
        if (callIdHeader != null) {
            callId = callIdHeader.getCallId();
        }
        log.info("GB28181 收到摄像机 BYE deviceId={}（可能因 RTSP/第二路点播冲突或会话被占用）", deviceId);
        playManager.onRemoteBye(deviceId, callId);
    }

    private void handleMessage(RequestEvent event) throws Exception {
        Request request = event.getRequest();
        byte[] raw = request.getRawContent();
        String xml = raw != null ? new String(raw, GB2312) : "";
        String cmd = Gb28181XmlHelper.cmdType(xml);
        String deviceId = Gb28181XmlHelper.deviceId(xml);
        if (deviceId != null) {
            deviceRegistry.touchKeepalive(deviceId);
            applyRemoteContact(event, deviceId, request);
            Gb28181DeviceSession dev = deviceRegistry.get(deviceId);
            if (dev != null) {
                dev.setSipTransport(resolveTransport(request));
            }
        }
        if ("Keepalive".equalsIgnoreCase(cmd)) {
            log.info("GB28181 心跳 deviceId={}", deviceId);
            Gb28181PlatformConfig cfg = platformConfigService.getOrCreate();
            if (registerReceivedCount.get() == 0 && cfg.isRequireSipRegister()) {
                log.warn("GB28181 海康显示在线但平台从未收到 REGISTER（仅心跳）。请核对：① 平台「国标28181」页「设备密码」与海康密码完全一致后保存；"
                        + "② 海康国标页点保存/刷新注册；③ 查看是否有 REGISTER 401/403 日志；"
                        + "或取消勾选「必须 SIP REGISTER 后才点播」");
            } else if (registerReceivedCount.get() == 0 && deviceId != null) {
                log.debug("GB28181 仅心跳未 REGISTER deviceId={}（已允许按心跳点播）", deviceId);
            }
        } else if ("Catalog".equalsIgnoreCase(cmd)) {
            List<String> ids = Gb28181XmlHelper.catalogDeviceIds(xml);
            if (deviceId != null && !ids.isEmpty()) {
                Gb28181DeviceSession dev = deviceRegistry.get(deviceId);
                if (dev != null) {
                    dev.setCatalogDeviceIds(ids);
                    dev.setCatalogUpdatedAt(Instant.now());
                }
                List<String> channels = new ArrayList<>();
                for (String id : ids) {
                    if (!id.equals(deviceId)) {
                        channels.add(id);
                    }
                }
                log.info("GB28181 Catalog device={} 视频通道={}（完整列表={}）",
                        deviceId, channels.isEmpty() ? ids : channels, ids);
            } else {
                log.debug("GB28181 Catalog 上报 deviceId={} 无 DeviceID 列表", deviceId);
            }
        }
        sendResponse(event, Response.OK);
    }

    private void parseContact(String deviceId, Request request) {
        ContactHeader contact = (ContactHeader) request.getHeader(ContactHeader.NAME);
        if (contact == null) {
            parseRemoteEndpoint(deviceId, request);
            return;
        }
        Address addr = contact.getAddress();
        if (!(addr.getURI() instanceof SipURI)) {
            parseRemoteEndpoint(deviceId, request);
            return;
        }
        SipURI uri = (SipURI) addr.getURI();
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 5060;
        deviceRegistry.setContact(deviceId, host, port, uri.toString());
    }

    /**
     * 海康等设备 Contact/From 常为 {@code @3402000000} 域而非摄像机 IP；
     * 用 UDP 报文源地址作为 INVITE 目标（须在 REGISTER 之后或仅有 MESSAGE 时生效）。
     */
    private void applyUdpPeerContact(RequestEvent event, String deviceId) {
        if (deviceId == null || event == null) {
            return;
        }
        Gb28181DeviceSession existing = deviceRegistry.get(deviceId);
        if (existing != null && Gb28181NetUtil.isIpv4(existing.getContactHost())) {
            return;
        }
        if (!(event instanceof gov.nist.javax.sip.RequestEventExt)) {
            return;
        }
        gov.nist.javax.sip.RequestEventExt ext = (gov.nist.javax.sip.RequestEventExt) event;
        String ip = ext.getRemoteIpAddress();
        int port = ext.getRemotePort() > 0 ? ext.getRemotePort() : 5060;
        if (!Gb28181NetUtil.isIpv4(ip)) {
            return;
        }
        deviceRegistry.setContact(deviceId, ip, port, "sip:" + deviceId + "@" + ip + ":" + port);
        log.info("GB28181 从 UDP 源设置 Contact deviceId={} {}:{}（Contact 头无有效 IPv4）",
                deviceId, ip, port);
    }

    /** 从 From / Contact 解析摄像机 IP，供 INVITE 使用（仅有 MESSAGE 心跳时也需要）。 */
    private void parseRemoteEndpoint(String deviceId, Request request) {
        if (deviceId == null || request == null) {
            return;
        }
        ContactHeader contact = (ContactHeader) request.getHeader(ContactHeader.NAME);
        if (contact != null) {
            Address addr = contact.getAddress();
            if (addr.getURI() instanceof SipURI) {
                SipURI uri = (SipURI) addr.getURI();
                if (uri.getHost() != null && !uri.getHost().isEmpty()) {
                    int port = uri.getPort() > 0 ? uri.getPort() : 5060;
                    deviceRegistry.setContact(deviceId, uri.getHost(), port, uri.toString());
                    return;
                }
            }
        }
        FromHeader from = (FromHeader) request.getHeader(FromHeader.NAME);
        if (from == null || !(from.getAddress().getURI() instanceof SipURI)) {
            return;
        }
        SipURI uri = (SipURI) from.getAddress().getURI();
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            return;
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 5060;
        deviceRegistry.setContact(deviceId, uri.getHost(), port, uri.toString());
    }

    private void logRegisterInbound(Request request) {
        try {
            FromHeader from = (FromHeader) request.getHeader(FromHeader.NAME);
            ToHeader to = (ToHeader) request.getHeader(ToHeader.NAME);
            String fromStr = from != null ? from.toString() : "-";
            String toStr = to != null ? to.toString() : "-";
            log.info("GB28181 REGISTER 入站 Request-URI={} From={} To={}",
                    request.getRequestURI(), fromStr, toStr);
        } catch (Exception e) {
            log.info("GB28181 REGISTER 入站（解析头失败: {}）", e.getMessage());
        }
    }

    private String extractDeviceId(Request request) {
        FromHeader from = (FromHeader) request.getHeader(FromHeader.NAME);
        if (from == null) {
            return null;
        }
        Address addr = from.getAddress();
        if (addr.getURI() instanceof SipURI) {
            return ((SipURI) addr.getURI()).getUser();
        }
        Matcher m = SIP_USER.matcher(addr.getURI().toString());
        return m.find() ? m.group(1) : null;
    }

    private void sendResponse(RequestEvent event, int status) throws SipException, InvalidArgumentException, ParseException {
        sendResponse(event, messageFactory.createResponse(status, event.getRequest()));
    }

    private void sendResponse(RequestEvent event, Response response)
            throws SipException, InvalidArgumentException, ParseException {
        Request request = event.getRequest();
        ServerTransaction tx = event.getServerTransaction();
        if (tx == null) {
            tx = providerFromEvent(event).getNewServerTransaction(request);
        }
        tx.sendResponse(response);
    }

    private void applyRemoteContact(RequestEvent event, String deviceId, Request request) {
        parseContact(deviceId, request);
        applyUdpPeerContact(event, deviceId);
        if (request instanceof SIPRequest) {
            Gb28181RemoteAddress.Info remote = Gb28181RemoteAddress.fromRequest((SIPRequest) request);
            if (Gb28181NetUtil.isIpv4(remote.getIp())) {
                deviceRegistry.setContact(deviceId, remote.getIp(), remote.getPort(),
                        "sip:" + deviceId + "@" + remote.getIp() + ":" + remote.getPort());
            }
        }
    }

    private static String resolveTransport(Request request) {
        if (request instanceof SIPRequest) {
            return Gb28181RemoteAddress.transportFromRequest((SIPRequest) request);
        }
        ViaHeader via = (ViaHeader) request.getHeader(ViaHeader.NAME);
        if (via != null && "TCP".equalsIgnoreCase(via.getTransport())) {
            return "TCP";
        }
        return "UDP";
    }

    @Override
    public void processTimeout(TimeoutEvent timeoutEvent) {
    }

    @Override
    public void processIOException(IOExceptionEvent exceptionEvent) {
        log.warn("GB28181 SIP IO: {}", exceptionEvent.getHost());
    }

    @Override
    public void processTransactionTerminated(TransactionTerminatedEvent transactionTerminatedEvent) {
    }

    @Override
    public void processDialogTerminated(DialogTerminatedEvent dialogTerminatedEvent) {
    }
}
