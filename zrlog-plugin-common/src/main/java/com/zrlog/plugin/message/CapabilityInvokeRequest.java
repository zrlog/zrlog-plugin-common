package com.zrlog.plugin.message;

import java.util.HashMap;
import java.util.Map;

public class CapabilityInvokeRequest {
    public static final String HOST_VIEW_CAPABILITY = "__zrlog_host_render_view__";
    public static final int MAX_VIEW_RESPONSE_BYTES = 2 * 1024 * 1024;

    private String pluginId;
    private String capabilityKey;
    private String source;
    private String requestId;
    private String traceId;
    private boolean requireCompletion;
    private Long deadlineEpochMillis;
    private com.zrlog.plugin.data.codec.HttpRequestInfo viewRequest;
    private boolean viewResource;
    private Map<String, Object> payload = new HashMap<>();

    /** Request-scoped execution: the SDK sends the response only after the handler returns. */
    public boolean isRequireCompletion() { return requireCompletion; }
    public void setRequireCompletion(boolean requireCompletion) { this.requireCompletion = requireCompletion; }
    public Long getDeadlineEpochMillis() { return deadlineEpochMillis; }
    public void setDeadlineEpochMillis(Long deadlineEpochMillis) { this.deadlineEpochMillis = deadlineEpochMillis; }
    public com.zrlog.plugin.data.codec.HttpRequestInfo getViewRequest() { return viewRequest; }
    public void setViewRequest(com.zrlog.plugin.data.codec.HttpRequestInfo viewRequest) { this.viewRequest = viewRequest; }
    public boolean isViewResource() { return viewResource; }
    public void setViewResource(boolean viewResource) { this.viewResource = viewResource; }

    public String getPluginId() {
        return pluginId;
    }

    public void setPluginId(String pluginId) {
        this.pluginId = pluginId;
    }

    public String getCapabilityKey() {
        return capabilityKey;
    }

    public void setCapabilityKey(String capabilityKey) {
        this.capabilityKey = capabilityKey;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }
}
