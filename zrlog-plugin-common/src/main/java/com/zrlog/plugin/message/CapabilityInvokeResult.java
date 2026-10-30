package com.zrlog.plugin.message;

import java.util.HashMap;
import java.util.Map;

public class CapabilityInvokeResult {

    private boolean success;
    private String errorMessage;
    private boolean executionCompleted;
    private boolean outcomeUncertain;
    private String renderedText;
    private Map<String, Object> data = new HashMap<>();

    public boolean isExecutionCompleted() { return executionCompleted; }
    public void setExecutionCompleted(boolean executionCompleted) { this.executionCompleted = executionCompleted; }
    public boolean isOutcomeUncertain() { return outcomeUncertain; }
    public void setOutcomeUncertain(boolean outcomeUncertain) { this.outcomeUncertain = outcomeUncertain; }
    public String getRenderedText() { return renderedText; }
    public void setRenderedText(String renderedText) { this.renderedText = renderedText; }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }
}
