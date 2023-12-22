package com.mxixm.fastboot.weixin.mvc.converter;

import com.mxixm.fastboot.weixin.exception.WxAppException;
import com.mxixm.fastboot.weixin.module.message.WxEncryptMessage;
import com.mxixm.fastboot.weixin.module.message.WxMessage;
import com.mxixm.fastboot.weixin.module.message.WxMessageProcessor;
import com.mxixm.fastboot.weixin.module.message.parameter.WxMessageParameter;
import com.mxixm.fastboot.weixin.module.web.WxRequest;
import com.mxixm.fastboot.weixin.service.WxXmlCryptoService;
import com.mxixm.fastboot.weixin.util.WxWebUtils;
import com.sun.xml.internal.bind.marshaller.CharacterEscapeHandler;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.xml.Jaxb2RootElementHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletRequest;
import javax.xml.bind.Marshaller;
import javax.xml.bind.PropertyException;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;

public class WxXmlMessageConverter extends Jaxb2RootElementHttpMessageConverter {

    private static final Log logger = LogFactory.getLog(MethodHandles.lookup().lookupClass());
    private static final CDataCharacterEscapeHandler characterEscapeHandler = new CDataCharacterEscapeHandler();
    private final WxMessageProcessor wxMessageProcessor;
    private final WxXmlCryptoService wxXmlCryptoService;

    public WxXmlMessageConverter(WxMessageProcessor wxMessageProcessor, WxXmlCryptoService wxXmlCryptoService) {
        this.wxMessageProcessor = wxMessageProcessor;
        this.wxXmlCryptoService = wxXmlCryptoService;
    }

    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return super.canRead(clazz, mediaType) && WxRequest.Body.class.isAssignableFrom(clazz) &&
                WxWebUtils.getWxRequestFromRequest() != null;
    }

    public WxRequest.Body read(HttpServletRequest request) throws IOException {
        return (WxRequest.Body) super.read(WxRequest.Body.class, new ServletServerHttpRequest(request));
    }

    @Override
    protected Object readFromSource(Class<?> clazz, HttpHeaders headers, Source source) {
        try {
            Assert.isAssignable(WxRequest.Body.class, clazz, "错误的使用了消息转化器");
            WxRequest wxRequest = WxWebUtils.getWxRequestFromRequest();
            WxRequest.Body body = (WxRequest.Body) super.readFromSource(clazz, headers, source);
            if (!wxRequest.isEncrypted()) {
                return body;
            }
            if (StringUtils.isEmpty(body.getEncrypt()) && !StringUtils.isEmpty(body.getFromUserName())) {
                return body;
            }
            String decryptedMessage = wxXmlCryptoService.decrypt(wxRequest, body.getEncrypt());
            return super.readFromSource(clazz, headers, new StreamSource(new ByteArrayInputStream(decryptedMessage.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) {
            logger.error("Error in readFromSource", e);
            throw new RuntimeException("Error processing source", e);
        }
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return (CharSequence.class.isAssignableFrom(clazz) || WxMessage.class.isAssignableFrom(clazz)) &&
                WxWebUtils.getWxRequestFromRequest() != null;
    }

    @Override
    protected void writeToResult(Object o, HttpHeaders headers, Result result) {
        try {
            WxRequest wxRequest = WxWebUtils.getWxRequestFromRequest();
            WxMessage wxMessage = processObject(o);
            if (!wxRequest.isEncrypted()) {
                super.writeToResult(wxMessage, headers, result);
            } else {
                StreamResult rawResult = new StreamResult(new StringWriter(256));
                super.writeToResult(wxMessage, headers, rawResult);
                WxEncryptMessage wxEncryptMessage = wxXmlCryptoService.encrypt(wxRequest, rawResult.getWriter().toString());
                super.writeToResult(wxEncryptMessage, headers, result);
            }
        } catch (Exception e) {
            logger.error("Error in writeToResult", e);
            throw new RuntimeException("Error writing result", e);
        }
    }

    private WxMessage processObject(Object o) {
        if (o instanceof CharSequence) {
            return WxMessage.textBuilder().content(o.toString()).build();
        } else if (o instanceof WxMessage) {
            return (WxMessage) o;
        } else {
            throw new WxAppException("错误的消息类型");
        }
    }

    @Override
    protected void customizeMarshaller(Marshaller marshaller) {
        try {
            marshaller.setProperty(Marshaller.JAXB_ENCODING, StandardCharsets.UTF_8.displayName());
            marshaller.setProperty("com.sun.xml.internal.bind.marshaller.CharacterEscapeHandler", characterEscapeHandler);
        } catch (Exception e) {
            logger.error("Failed to set marshaller property", e);
            throw new RuntimeException("Error customizing marshaller", e);
        }
    }

    public static class CDataCharacterEscapeHandler implements CharacterEscapeHandler {
        @Override
        public void escape(char[] ch, int start, int length, boolean isAttVal, Writer out) throws IOException {
            out.write("<![CDATA[");
            for (int i = start; i < start + length; i++) {
                switch (ch[i]) {
                    case '&':
                        out.write("&amp;");
                        break;
                    case '<':
                        out.write("&lt;");
                        break;
                    case '>':
                        out.write("&gt;");
                        break;
                    case '\"':
                        if (isAttVal) {
                            out.write("&quot;");
                        } else {
                            out.write('\"');
                        }
                        break;
                    default:
                        out.write(ch[i]);
                }
            }
            out.write("]]>");
        }
    }
}
