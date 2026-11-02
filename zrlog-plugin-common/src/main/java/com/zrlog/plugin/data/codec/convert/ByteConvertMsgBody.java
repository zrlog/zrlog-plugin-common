package com.zrlog.plugin.data.codec.convert;

import java.nio.ByteBuffer;

public class ByteConvertMsgBody implements ConvertMsgBody {

    @Override
    public ByteBuffer toByteBuffer(Object obj) {
        if (obj instanceof ByteBuffer) {
            return (ByteBuffer) obj;
        } else if (obj instanceof byte[]) {
            return ByteBuffer.wrap((byte[]) obj);
        }
        return null;
    }

    @Override
    public Object toObj(ByteBuffer byteBuffer) {
        return null;
    }
}
