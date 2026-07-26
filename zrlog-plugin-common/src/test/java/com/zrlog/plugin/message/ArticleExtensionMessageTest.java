package com.zrlog.plugin.message;

import com.google.gson.Gson;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ArticleExtensionMessageTest {

    private final Gson gson = new Gson();

    @Test
    public void shouldKeepArticleExtensionProtocolTyped() {
        ArticleExtensionSetRequest setRequest = gson.fromJson(
                "{\"articleId\":12,\"values\":{\"resourceIds\":[\"asset-1\"]},"
                        + "\"indexedPaths\":[\"resourceIds\"]}",
                ArticleExtensionSetRequest.class);
        ArticleExtensionQueryRequest queryRequest = gson.fromJson(
                "{\"filters\":[{\"path\":\"resourceIds\",\"values\":[\"asset-1\"]}],\"page\":1,\"size\":20}",
                ArticleExtensionQueryRequest.class);

        assertEquals(Long.valueOf(12), setRequest.getArticleId());
        assertEquals(Collections.singletonList("asset-1"), setRequest.getValues().get("resourceIds"));
        assertEquals("resourceIds", queryRequest.getFilters().get(0).getPath());
        assertTrue(ArticleExtensionResult.success(12L, "metadata", setRequest.getValues()).isSuccess());
        assertTrue(ArticleExtensionQueryResult.success(1, 20, 1, Collections.emptyList()).isSuccess());
    }
}
