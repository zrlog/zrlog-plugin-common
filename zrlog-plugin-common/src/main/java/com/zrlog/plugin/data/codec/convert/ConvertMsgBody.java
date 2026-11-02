package com.zrlog.plugin.data.codec.convert;

import java.nio.ByteBuffer;

public interface ConvertMsgBody {
    ByteBuffer toByteBuffer(Object obj);

    Object toObj(ByteBuffer byteBuffer);
}
