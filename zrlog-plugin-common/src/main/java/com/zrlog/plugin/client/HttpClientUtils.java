package com.zrlog.plugin.client;

import com.google.gson.Gson;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.ResponseLease;
import com.zrlog.plugin.common.IdUtil;
import com.zrlog.plugin.common.type.HttpMethod;
import com.zrlog.plugin.data.codec.*;
import com.zrlog.plugin.type.ActionType;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    public static final long MAX_FILE_DOWNLOAD_BYTES = 256L * 1024 * 1024;

    /**
     * Download through plugin-core's HTTP client and the streamed FILE transport.
     * Requires a core supporting file responses. The destination is replaced only after success.
     * Network and transport temporary files are released within the request lifecycle.
     * Only HTTP 200 is accepted; redirects are never followed.
     *
     * @param url HTTP(S) resource URL; caller is responsible for resource authorization
     * @param destination output file in an existing directory, owned by the caller after success
     * @param maxBytes positive response limit, at most {@link #MAX_FILE_DOWNLOAD_BYTES}
     * @param headers headers for this URL only; null sends no caller headers
     * @param ioSession plugin connection to the core performing the download
     * @param readTimeout response wait timeout, including download and FILE transfer
     * @throws IOException on HTTP, transport, size-limit or local file failure
     */
    public static void downloadToFile(String url, Path destination, long maxBytes,
                                      Map<String, String> headers, IOSession ioSession,
                                      Duration readTimeout) throws IOException {
        if (maxBytes <= 0 || maxBytes > MAX_FILE_DOWNLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid HTTP file download limit");
        }
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(ioSession, "ioSession");
        BaseHttpRequestInfo request = new BaseHttpRequestInfo();
        request.setAccessUrl(url);
        request.setHttpMethod(HttpMethod.GET);
        request.setHeader(headers == null ? new HashMap<>() : new LinkedHashMap<>(headers));
        request.setResponseBodyAsFile(true);
        request.setMaxResponseBodyBytes(maxBytes);
        int id = IdUtil.getInt();
        try {
            ioSession.sendMsg(new MsgPacket(request, ContentType.JSON, MsgPacketStatus.SEND_REQUEST,
                    id, ActionType.HTTP_METHOD.name()), null, readTimeout);
        } catch (RuntimeException e) {
            throw new IOException("Unable to send HTTP file request", e);
        }
        try (ResponseLease lease = ioSession.getResponseLeaseByMsgId(id, readTimeout)) {
            if (lease == null) {
                throw new IOException("HTTP file download timed out or the session closed");
            }
            MsgPacket packet = lease.getPacket();
            if (packet.getStatus() != MsgPacketStatus.RESPONSE_SUCCESS
                    || packet.getContentType() != ContentType.FILE || packet.getFilePayload() == null) {
                if (packet.getContentType() == ContentType.JSON) {
                    HttpResponseInfo response = new Gson().fromJson(packet.getDataStr(), HttpResponseInfo.class);
                    if (response != null && response.getStatusCode() != 200) {
                        throw new IOException("HTTP file download failed (HTTP " + response.getStatusCode() + ")");
                    }
                }
                throw new IOException("HTTP file response unavailable; upgrade plugin-core to support file downloads");
            }
            FilePacketPayload payload = packet.getFilePayload();
            if (payload.getFileLength() > maxBytes) {
                throw new IOException("HTTP file exceeds " + maxBytes + " bytes");
            }
            Path target = destination.toAbsolutePath();
            Path staging = Files.createTempFile(target.getParent(), ".http-download-", ".tmp");
            try {
                payload.copyTo(staging);
                try {
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(staging);
            }
        } catch (RuntimeException e) {
            throw new IOException("Unable to receive HTTP file response", e);
        }
    }

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
