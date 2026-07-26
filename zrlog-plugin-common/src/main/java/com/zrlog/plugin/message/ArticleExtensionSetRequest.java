package com.zrlog.plugin.message;

import java.util.List;
import java.util.Map;

public class ArticleExtensionSetRequest {

    private Long articleId;
    private Map<String, Object> values;
    private List<String> indexedPaths;

    public Long getArticleId() {
        return articleId;
    }

    public void setArticleId(Long articleId) {
        this.articleId = articleId;
    }

    public Map<String, Object> getValues() {
        return values;
    }

    public void setValues(Map<String, Object> values) {
        this.values = values;
    }

    public List<String> getIndexedPaths() {
        return indexedPaths;
    }

    public void setIndexedPaths(List<String> indexedPaths) {
        this.indexedPaths = indexedPaths;
    }
}
