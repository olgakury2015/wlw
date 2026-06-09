package com.iot.platform.video.gb28181.parser;

import gov.nist.javax.sip.parser.MessageParser;
import gov.nist.javax.sip.parser.MessageParserFactory;
import gov.nist.javax.sip.stack.SIPTransactionStack;

public class Gb28181StringMsgParserFactory implements MessageParserFactory {

    /**
     * msg parser is completely stateless, reuse isntance for the whole stack
     * fixes https://github.com/RestComm/jain-sip/issues/92
     */
    private static final GBStringMsgParser MSG_PARSER = new GBStringMsgParser();
    /*
     * (non-Javadoc)
     * @see gov.nist.javax.sip.parser.MessageParserFactory#createMessageParser(gov.nist.javax.sip.stack.SIPTransactionStack)
     */
    public MessageParser createMessageParser(SIPTransactionStack stack) {
        return MSG_PARSER;
    }
}
