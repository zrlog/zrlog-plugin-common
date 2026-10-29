package com.zrlog.plugin.data.codec;

import com.zrlog.plugin.common.type.HttpMethod;

import java.util.Map;

public class BaseHttpRequestInfo {

    public static final String DARK_MODE_HEADER = "Dark-Mode";
    public static final String ADMIN_COLOR_PRIMARY_HEADER = "Admin-Color-Primary";
    public static final String DEFAULT_ADMIN_COLOR_PRIMARY = "#1677ff";

    protected String accessUrl;
    protected Map<String, String> header;
    protected byte[] requestBody;
    protected HttpMethod httpMethod;
    // Opt-in HTTP_METHOD response mode; ordinary callers keep the bounded JSON response.
    protected boolean responseBodyAsFile;
    protected Long maxResponseBodyBytes;
    protected Boolean darkMode;
    protected String adminColorPrimary;

    public String getAccessUrl() {
        return accessUrl;
    }

    public void setAccessUrl(String accessUrl) {
        this.accessUrl = accessUrl;
    }

    public Map<String, String> getHeader() {
        return header;
    }

    public void setHeader(Map<String, String> header) {
        this.header = header;
    }

    public byte[] getRequestBody() {
        return requestBody;
    }

    public void setRequestBody(byte[] requestBody) {
        this.requestBody = requestBody;
    }

    public HttpMethod getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(HttpMethod httpMethod) {
        this.httpMethod = httpMethod;
    }

    public boolean isResponseBodyAsFile() {
        return responseBodyAsFile;
    }

    public void setResponseBodyAsFile(boolean responseBodyAsFile) {
        this.responseBodyAsFile = responseBodyAsFile;
    }

    public Long getMaxResponseBodyBytes() {
        return maxResponseBodyBytes;
    }

    public void setMaxResponseBodyBytes(Long maxResponseBodyBytes) {
        this.maxResponseBodyBytes = maxResponseBodyBytes;
    }

    public Boolean getDarkMode() {
        if (darkMode != null) {
            return darkMode;
        }
        return Boolean.parseBoolean(getHeaderValue(DARK_MODE_HEADER, "Dark_mode", "dark_mode"));
    }

    public void setDarkMode(Boolean darkMode) {
        this.darkMode = darkMode;
    }

    public boolean isDarkMode() {
        return Boolean.TRUE.equals(getDarkMode());
    }

    public String getAdminColorPrimary() {
        String value = adminColorPrimary;
        if (value == null || value.trim().isEmpty()) {
            value = getHeaderValue(ADMIN_COLOR_PRIMARY_HEADER, "admin_color_Primary", "admin_color_primary");
        }
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_ADMIN_COLOR_PRIMARY;
        }
        return value;
    }

    public void setAdminColorPrimary(String adminColorPrimary) {
        this.adminColorPrimary = adminColorPrimary;
    }

    private String getHeaderValue(String key, String... aliases) {
        if (header == null || header.isEmpty()) {
            return null;
        }
        String value = header.get(key);
        if (value != null && !value.trim().isEmpty()) {
            return value;
        }
        for (String alias : aliases) {
            value = header.get(alias);
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        for (Map.Entry<String, String> entry : header.entrySet()) {
            String entryKey = entry.getKey();
            if (entryKey == null) {
                continue;
            }
            if (entryKey.equalsIgnoreCase(key)) {
                return entry.getValue();
            }
            for (String alias : aliases) {
                if (entryKey.equalsIgnoreCase(alias)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }
}
