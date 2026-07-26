package com.zrlog.plugin.message;

public class ArticleExtensionGetRequest {

    private Long articleId;

    public ArticleExtensionGetRequest() {
    }

    public ArticleExtensionGetRequest(Long articleId) {
        this.articleId = articleId;
    }

    public Long getArticleId() {
        return articleId;
    }

    public void setArticleId(Long articleId) {
        this.articleId = articleId;
    }
}
