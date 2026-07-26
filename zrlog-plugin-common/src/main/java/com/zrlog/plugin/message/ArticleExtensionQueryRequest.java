package com.zrlog.plugin.message;

import java.util.List;

public class ArticleExtensionQueryRequest {

    private List<ArticleExtensionFilter> filters;
    private Long page;
    private Long size;

    public List<ArticleExtensionFilter> getFilters() {
        return filters;
    }

    public void setFilters(List<ArticleExtensionFilter> filters) {
        this.filters = filters;
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
}
