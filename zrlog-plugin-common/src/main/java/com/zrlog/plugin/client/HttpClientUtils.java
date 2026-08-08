package com.zrlog.plugin.client;

import com.google.gson.Gson;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.ResponseLease;
import com.zrlog.plugin.common.IdUtil;
import com.zrlog.plugin.common.type.HttpMethod;
import com.zrlog.plugin.data.codec.*;
import com.zrlog.plugin.type.ActionType;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 提供一个标准的访问 http 请求的工具库
 * 避免插件库都去依赖 http 库，导致插件依赖各种的 httpclient 混乱
 * 同时缩小一下 native image 打包体积
 */
public class HttpClientUtils {

    public static HttpResponseInfo sendHttpRequest(BaseHttpRequestInfo httpRequestInfo, IOSession ioSession, Duration readTimeout) {
        int id = IdUtil.getInt();
        ioSession.sendMsg(ContentType.JSON, httpRequestInfo, ActionType.HTTP_METHOD.name(), id, MsgPacketStatus.SEND_REQUEST);
        try (ResponseLease lease = ioSession.getResponseLeaseByMsgId(id, readTimeout)) {
            MsgPacket msgPacket = lease.getPacket();
            return new Gson().fromJson(new String(msgPacket.getData().array()), HttpResponseInfo.class);
        }
    }

    public static <T> T sendGetRequest(String url, Class<T> clazz, IOSession ioSession, Duration readTimeout) {
        return sendGetRequest(url, clazz, null, ioSession, readTimeout);
    }


    public static <T> T sendGetRequest(String url, Class<T> clazz, Map<String, String> headerMap, IOSession ioSession, Duration readTimeout) {
        BaseHttpRequestInfo baseHttpRequestInfo = new BaseHttpRequestInfo();
        baseHttpRequestInfo.setHttpMethod(HttpMethod.GET);
        if (Objects.isNull(headerMap)) {
            baseHttpRequestInfo.setHeader(new HashMap<>());
        } else {
            baseHttpRequestInfo.setHeader(new LinkedHashMap<>(headerMap));
        }

        baseHttpRequestInfo.setAccessUrl(url);
        HttpResponseInfo httpResponseInfo = sendHttpRequest(baseHttpRequestInfo, ioSession, readTimeout);
        if (Objects.isNull(httpResponseInfo)) {
            throw new RuntimeException("Response is null");
        }
        if (httpResponseInfo.getStatusCode() != 200) {
            throw new RuntimeException("Error send http request, status code:" + httpResponseInfo.getStatusCode() + " body => " + new String(httpResponseInfo.getResponseBody()));
        }
        if (clazz.getName().equals(String.class.getName())) {
            return (T) new String(httpResponseInfo.getResponseBody());
        }
        return new Gson().fromJson(new String(httpResponseInfo.getResponseBody()), clazz);
    }
}
