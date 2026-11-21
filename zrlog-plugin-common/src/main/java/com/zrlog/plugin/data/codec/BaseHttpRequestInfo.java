package com.zrlog.plugin.data.codec;

import com.zrlog.plugin.common.type.HttpMethod;

import java.util.Map;

public class BaseHttpRequestInfo {

    public static final String DARK_MODE_HEADER = "Dark-Mode";
    public static final String ADMIN_COLOR_PRIMARY_HEADER = "Admin-Color-Primary";
    public static final String ADMIN_THEME_HEADER = "Admin-Theme";
    public static final String ADMIN_COMPACT_MODE_HEADER = "Admin-Compact-Mode";
    public static final String DEFAULT_ADMIN_COLOR_PRIMARY = "#1677ff";
    public static final String DEFAULT_ADMIN_THEME = "default";

    protected String accessUrl;
    protected Map<String, String> header;
    protected byte[] requestBody;
    protected HttpMethod httpMethod;
    // Opt-in HTTP_METHOD response mode; ordinary callers keep the bounded JSON response.
    protected boolean responseBodyAsFile;
    protected Long maxResponseBodyBytes;
    protected Boolean darkMode;
    protected String adminColorPrimary;
    protected String adminTheme;
    protected Boolean adminCompactMode;

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
        return appearanceValue(adminColorPrimary, DEFAULT_ADMIN_COLOR_PRIMARY,
                ADMIN_COLOR_PRIMARY_HEADER, "admin_color_primary");
    }

    public void setAdminColorPrimary(String adminColorPrimary) {
        this.adminColorPrimary = adminColorPrimary;
    }

    public String getAdminTheme() {
        return appearanceValue(adminTheme, DEFAULT_ADMIN_THEME, ADMIN_THEME_HEADER);
    }

    public void setAdminTheme(String adminTheme) {
        this.adminTheme = adminTheme;
    }

    public boolean isAdminCompactMode() {
        return adminCompactMode != null
                ? adminCompactMode
                : Boolean.parseBoolean(getHeaderValue(ADMIN_COMPACT_MODE_HEADER));
    }

    public void setAdminCompactMode(Boolean adminCompactMode) {
        this.adminCompactMode = adminCompactMode;
    }

    private String appearanceValue(String value, String defaultValue, String key, String... aliases) {
        String normalized = nonBlank(value);
        if (normalized == null) {
            normalized = getHeaderValue(key, aliases);
        }
        return normalized == null ? defaultValue : normalized;
    }

    private String getHeaderValue(String key, String... aliases) {
        if (header == null || header.isEmpty()) {
            return null;
        }
        String value = getNonBlankHeader(key);
        if (value != null) {
            return value;
        }
        for (String alias : aliases) {
            value = getNonBlankHeader(alias);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String getNonBlankHeader(String key) {
        String value = nonBlank(header.get(key));
        if (value != null) {
            return value;
        }
        for (Map.Entry<String, String> entry : header.entrySet()) {
            value = nonBlank(entry.getValue());
            if (value != null && key.equalsIgnoreCase(entry.getKey())) {
                return value;
            }
        }
        return null;
    }

    private static String nonBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
