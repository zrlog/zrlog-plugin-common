package com.zrlog.plugin.data.codec;

import com.google.gson.Gson;
import com.zrlog.plugin.common.HexaConversionUtil;
import com.zrlog.plugin.data.codec.convert.*;

import java.nio.ByteBuffer;

public class MsgPacket {

    private final byte dStart;
    private int msgId;
    private MsgPacketStatus status;
    private ContentType contentType;
    private byte methodLength;
    private String methodStr;
    private ByteBuffer data;
    private FilePacketPayload filePayload;
    private int dataLength;

    public MsgPacket(Object data, ContentType contentType, MsgPacketStatus status, int msgId, String methodStr) {
        this();
        this.contentType = contentType;
        this.status = status;
        this.msgId = msgId;
        this.methodStr = methodStr;
        if (contentType == ContentType.FILE) {
            if (!(data instanceof java.io.File)) {
                throw new IllegalArgumentException("obj not a file " + data);
            }
            this.filePayload = FilePacketPayload.forSend((java.io.File) data);
            this.dataLength = filePayload.encodedLength();
            this.methodLength = (byte) methodStr.getBytes().length;
            return;
        }
        ConvertMsgBody convertMsgBody = getConvertMsgBody(contentType);
        if (convertMsgBody == null) {
            throw new RuntimeException("not found such convert " + contentType);
        }
        this.data = convertMsgBody.toByteBuffer(data);
        this.dataLength = this.data.array().length;
        this.methodLength = (byte) methodStr.getBytes().length;
    }

    private static ConvertMsgBody getConvertMsgBody(ContentType contentType) {
        if (contentType == ContentType.BYTE) {
            return new ByteConvertMsgBody();
        } else if (contentType == ContentType.JSON) {
            return new JsonConvertMsgBody();
        } else if (contentType == ContentType.HTML || contentType == ContentType.XML || contentType == ContentType.IMAGE_SVG_XML) {
            return new StringConvertMsgBody();
        }
        return null;
    }

    public MsgPacket() {
        dStart = PackageVersion.V1.getVersion();
    }


    public ContentType getContentType() {
        return contentType;
    }

    public void setContentType(ContentType contentType) {
        this.contentType = contentType;
    }

    public ByteBuffer getData() {
        return data;
    }

    public String getDataStr() {
        return new String(data.array());
    }

    public void setData(ByteBuffer data) {
        this.data = data;
        if (data != null) {
            this.filePayload = null;
        }
    }

    public FilePacketPayload getFilePayload() {
        return filePayload;
    }

    public void setFilePayload(FilePacketPayload filePayload) {
        this.filePayload = filePayload;
        if (filePayload != null) {
            this.data = null;
        }
    }

    FilePacketPayload detachFilePayload() {
        FilePacketPayload payload = filePayload;
        filePayload = null;
        return payload;
    }

    void releaseFilePayload() {
        FilePacketPayload payload = detachFilePayload();
        if (payload != null) {
            payload.close();
        }
    }

    public int getMsgId() {
        return msgId;
    }

    public void setMsgId(int msgId) {
        this.msgId = msgId;
    }

    public int getDataLength() {
        return dataLength;
    }

    public void setDataLength(int dataLength) {
        this.dataLength = dataLength;
    }


    public byte getdStart() {
        return dStart;
    }

    public byte getMethodLength() {
        return methodLength;
    }

    public void setMethodLength(byte methodLength) {
        this.methodLength = methodLength;
    }

    public String getMethodStr() {
        return methodStr;
    }

    public void setMethodStr(String methodStr) {
        this.methodStr = methodStr;
    }

    public MsgPacketStatus getStatus() {
        return status;
    }

    public void setStatus(MsgPacketStatus status) {
        this.status = status;
    }

    public <T> T convertToClass(Class<T> clazz) {
        return new Gson().fromJson(getDataStr(), clazz);
    }

    @Override
    public String toString() {
        if (contentType == ContentType.JSON) {
            String dataStr;
            if (data.array().length > 512) {
                dataStr = new String(HexaConversionUtil.subByts(data.array(), 0, 512));
            } else {
                dataStr = new String(data.array());
            }
            return "MsgPacket{" +
                    "contentType=" + contentType +
                    ", status=" + status.name().toLowerCase() +
                    ", methodStr=" + methodStr +
                    ", data=" + dataStr +
                    ", dataLength=" + dataLength +
                    '}';
        } else {
            return "MsgPacket{" +
                    "contentType=" + contentType +
                    ", status=" + status.name().toLowerCase() +
                    ", methodStr=" + methodStr +
                    ", data=this file" +
                    ", dataLength=" + dataLength +
                    '}';
        }

    }
}
