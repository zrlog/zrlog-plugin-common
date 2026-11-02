package com.zrlog.plugin.data.codec;

import java.util.HashMap;
import java.util.Map;

public class HttpRequestInfo extends BaseHttpRequestInfo {
    private String uri;
    private String userName;
    private Integer userId;
    private String version;
    private String fullUrl;
    private Map<String, String[]> param;

    public Map<String, String[]> getParam() {
        return param;
    }

    public void setParam(Map<String, String[]> param) {
        this.param = param;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }


    public Map simpleParam() {
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, String[]> entry : param.entrySet()) {
            if (entry.getValue().length == 1) {
                map.put(entry.getKey(), entry.getValue()[0]);
            } else {
                map.put(entry.getKey(), entry.getValue());
            }
        }
        return map;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getFullUrl() {
        if (fullUrl == null) {
            return uri;
        }
        return fullUrl;
    }

    public void setFullUrl(String fullUrl) {
        this.fullUrl = fullUrl;
    }

}
