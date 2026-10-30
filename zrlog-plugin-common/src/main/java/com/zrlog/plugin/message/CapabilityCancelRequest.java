package com.zrlog.plugin.message;

/** Cancellation targets both the transport identity and the durable invocation identity. */
public class CapabilityCancelRequest {
    private int invocationMessageId;
    private String requestId;

    public int getInvocationMessageId() { return invocationMessageId; }
    public void setInvocationMessageId(int invocationMessageId) { this.invocationMessageId = invocationMessageId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
