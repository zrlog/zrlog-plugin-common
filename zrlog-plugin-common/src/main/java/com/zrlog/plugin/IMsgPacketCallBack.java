package com.zrlog.plugin;

import com.zrlog.plugin.data.codec.MsgPacket;

public interface IMsgPacketCallBack {
    void handler(MsgPacket responseMsgPacket);
}
