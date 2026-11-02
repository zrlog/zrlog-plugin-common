package com.zrlog.plugin.data.codec;

public enum MsgPacketStatus {
    SEND_REQUEST((byte) 0), RESPONSE_SUCCESS((byte) 1), RESPONSE_ERROR((byte) 3), UNKNOWN((byte) -1);

    byte type;

    MsgPacketStatus(byte type) {
        this.type = type;
    }


    public byte getType() {
        return type;
    }

    public void setType(byte type) {
        this.type = type;
    }

    public static MsgPacketStatus getMsgPacketStatus(byte type) {
        MsgPacketStatus[] contentTypes = MsgPacketStatus.values();
        for (MsgPacketStatus contentType : contentTypes) {
            if (contentType.type == type) {
                return contentType;
            }
        }
        return MsgPacketStatus.UNKNOWN;
    }
}
