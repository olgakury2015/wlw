package com.iot.platform.video.gb28181;

import gov.nist.javax.sip.message.SIPResponse;
import org.springframework.util.StringUtils;

import javax.sip.InvalidArgumentException;
import javax.sip.PeerUnavailableException;
import javax.sip.SipFactory;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.CSeqHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.UUID;

/**
 * 在 {@code AUTOMATIC_DIALOG_SUPPORT=off} 时手动构造 INVITE 的 ACK（对齐 wvp {@code SIPRequestHeaderProvider#createAckRequest}）。
 */
public final class Gb28181SipAckBuilder {

    private Gb28181SipAckBuilder() {
    }

    public static Request buildAck(
            SIPResponse inviteOk,
            String localIp,
            int localPort,
            String platformId,
            String remoteIp,
            int remotePort,
            String sdpBody) throws PeerUnavailableException, ParseException, InvalidArgumentException {
        SipFactory factory = SipFactory.getInstance();
        factory.setPathName("gov.nist");
        AddressFactory addressFactory = factory.createAddressFactory();
        HeaderFactory headerFactory = factory.createHeaderFactory();
        MessageFactory messageFactory = factory.createMessageFactory();

        String transport = "UDP";
        ViaHeader topVia = inviteOk.getTopmostViaHeader();
        if (topVia != null && StringUtils.hasText(topVia.getTransport())) {
            transport = topVia.getTransport();
        }

        SipURI requestUri = resolveRequestUri(inviteOk, addressFactory, remoteIp, remotePort);

        ArrayList<ViaHeader> viaHeaders = new ArrayList<>();
        String viaBranch = "z9hG4bK" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        viaHeaders.add(headerFactory.createViaHeader(localIp, localPort, transport, viaBranch));

        CSeqHeader cSeq = headerFactory.createCSeqHeader(inviteOk.getCSeqHeader().getSeqNumber(), Request.ACK);
        CallIdHeader callId = inviteOk.getCallIdHeader();
        FromHeader from = inviteOk.getFromHeader();
        ToHeader to = inviteOk.getToHeader();
        MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);

        Request ack = messageFactory.createRequest(
                requestUri, Request.ACK, callId, cSeq, from, to, viaHeaders, maxForwards);

        SipURI contactUri = addressFactory.createSipURI(platformId, localIp);
        contactUri.setPort(localPort);
        Address contactAddress = addressFactory.createAddress(contactUri);
        ack.addHeader(headerFactory.createContactHeader(contactAddress));

        if (sdpBody != null && !sdpBody.isEmpty()) {
            ContentTypeHeader ct = headerFactory.createContentTypeHeader("application", "sdp");
            ack.setContent(sdpBody, ct);
        }
        return ack;
    }

    private static SipURI resolveRequestUri(
            SIPResponse inviteOk, AddressFactory addressFactory, String remoteIp, int remotePort)
            throws ParseException {
        ContactHeader contact = inviteOk.getContactHeader();
        if (contact != null && contact.getAddress().getURI() instanceof SipURI) {
            SipURI cu = (SipURI) contact.getAddress().getURI();
            if (Gb28181NetUtil.isIpv4(cu.getHost())) {
                return cu;
            }
        }
        String user = inviteOk.getFromHeader().getAddress().getURI() instanceof SipURI
                ? ((SipURI) inviteOk.getFromHeader().getAddress().getURI()).getUser()
                : "unknown";
        if (!StringUtils.hasText(user)) {
            user = "unknown";
        }
        int port = remotePort > 0 ? remotePort : 5060;
        String host = Gb28181NetUtil.isIpv4(remoteIp) ? remoteIp : "127.0.0.1";
        SipURI uri = addressFactory.createSipURI(user, host);
        uri.setPort(port);
        return uri;
    }
}
