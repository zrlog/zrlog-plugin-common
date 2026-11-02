package com.zrlog.plugin.data.codec.convert;

import com.google.gson.Gson;

import java.nio.ByteBuffer;

public class JsonConvertMsgBody implements ConvertMsgBody {

    @Override
    public ByteBuffer toByteBuffer(Object obj) {
        byte[] jsonByte = new Gson().toJson(obj).getBytes();
        return ByteBuffer.wrap(jsonByte);
    }

    @Override
    public Object toObj(ByteBuffer byteBuffer) {
        return null;
    }

    public <T> T toObj(ByteBuffer byteBuffer, Class<T> clazz) {
        String jsonStr = new String(byteBuffer.array());
        return new Gson().fromJson(jsonStr, clazz);
    }
}
