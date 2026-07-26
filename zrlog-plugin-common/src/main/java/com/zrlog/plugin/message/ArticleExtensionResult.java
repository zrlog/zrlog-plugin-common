package com.zrlog.plugin.message;

import java.util.Collections;
import java.util.Map;

public class ArticleExtensionResult {

    private boolean success;
    private String errorMessage;
    private Long articleId;
    private String namespace;
    private Map<String, Object> values;

    public static ArticleExtensionResult success(Long articleId, String namespace, Map<String, Object> values) {
        ArticleExtensionResult result = new ArticleExtensionResult();
        result.setSuccess(true);
        result.setArticleId(articleId);
        result.setNamespace(namespace);
        result.setValues(values);
        return result;
    }

    public static ArticleExtensionResult error(String message) {
        ArticleExtensionResult result = new ArticleExtensionResult();
        result.setSuccess(false);
        result.setErrorMessage(message);
        result.setValues(Collections.emptyMap());
        return result;
    }

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

    public Long getArticleId() {
        return articleId;
    }

    public void setArticleId(Long articleId) {
        this.articleId = articleId;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public Map<String, Object> getValues() {
        return values;
    }

    public void setValues(Map<String, Object> values) {
        this.values = values;
    }
}
