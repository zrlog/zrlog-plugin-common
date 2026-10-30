package com.zrlog.plugin.message;

/** Acknowledges handler termination, never just acceptance of an interrupt request. */
public class CapabilityCancelResult {
    private boolean executionCompleted;
    private String requestId;

    public boolean isExecutionCompleted() { return executionCompleted; }
    public void setExecutionCompleted(boolean executionCompleted) { this.executionCompleted = executionCompleted; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
