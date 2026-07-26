package com.zrlog.plugin.message;

import java.util.Collections;
import java.util.List;

public class ArticleExtensionQueryResult {

    private boolean success;
    private String errorMessage;
    private Long page;
    private Long size;
    private Long total;
    private List<ArticleExtensionArticle> rows;

    public static ArticleExtensionQueryResult success(long page, long size, long total,
                                                      List<ArticleExtensionArticle> rows) {
        ArticleExtensionQueryResult result = new ArticleExtensionQueryResult();
        result.setSuccess(true);
        result.setPage(page);
        result.setSize(size);
        result.setTotal(total);
        result.setRows(rows);
        return result;
    }

    public static ArticleExtensionQueryResult error(String message) {
        ArticleExtensionQueryResult result = new ArticleExtensionQueryResult();
        result.setSuccess(false);
        result.setErrorMessage(message);
        result.setRows(Collections.emptyList());
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

    public Long getPage() {
        return page;
    }

    public void setPage(Long page) {
        this.page = page;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }

    public List<ArticleExtensionArticle> getRows() {
        return rows;
    }

    public void setRows(List<ArticleExtensionArticle> rows) {
        this.rows = rows;
    }
}
