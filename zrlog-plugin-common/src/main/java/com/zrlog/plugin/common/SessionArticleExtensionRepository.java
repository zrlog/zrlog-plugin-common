package com.zrlog.plugin.common;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.ResponseLease;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.message.ArticleExtensionGetRequest;
import com.zrlog.plugin.message.ArticleExtensionQueryRequest;
import com.zrlog.plugin.message.ArticleExtensionQueryResult;
import com.zrlog.plugin.message.ArticleExtensionResult;
import com.zrlog.plugin.message.ArticleExtensionSetRequest;
import com.zrlog.plugin.type.ActionType;

import java.time.Duration;
import java.util.Objects;

public class SessionArticleExtensionRepository {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private final IOSession session;

    public SessionArticleExtensionRepository(IOSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public static SessionArticleExtensionRepository of(IOSession session) {
        return new SessionArticleExtensionRepository(session);
    }

    public ArticleExtensionResult get(long articleId) {
        return get(articleId, DEFAULT_TIMEOUT);
    }

    public ArticleExtensionResult get(long articleId, Duration timeout) {
        try (ResponseLease lease = request(new ArticleExtensionGetRequest(articleId),
                ActionType.ARTICLE_EXTENSION_GET, timeout)) {
            if (lease == null) {
                return ArticleExtensionResult.error("get article extension timeout");
            }
            MsgPacket response = lease.getPacket();
            ArticleExtensionResult result = response.convertToClass(ArticleExtensionResult.class);
            return result == null ? ArticleExtensionResult.error("get article extension failed") : result;
        } catch (RuntimeException e) {
            return ArticleExtensionResult.error("get article extension failed");
        }
    }

    public ArticleExtensionResult set(ArticleExtensionSetRequest request) {
        return set(request, DEFAULT_TIMEOUT);
    }

    public ArticleExtensionResult set(ArticleExtensionSetRequest request, Duration timeout) {
        try (ResponseLease lease = request(request, ActionType.ARTICLE_EXTENSION_SET, timeout)) {
            if (lease == null) {
                return ArticleExtensionResult.error("set article extension timeout");
            }
            MsgPacket response = lease.getPacket();
            ArticleExtensionResult result = response.convertToClass(ArticleExtensionResult.class);
            return result == null ? ArticleExtensionResult.error("set article extension failed") : result;
        } catch (RuntimeException e) {
            return ArticleExtensionResult.error("set article extension failed");
        }
    }

    public ArticleExtensionQueryResult query(ArticleExtensionQueryRequest request) {
        return query(request, DEFAULT_TIMEOUT);
    }

    public ArticleExtensionQueryResult query(ArticleExtensionQueryRequest request, Duration timeout) {
        try (ResponseLease lease = request(request, ActionType.ARTICLE_EXTENSION_QUERY, timeout)) {
            if (lease == null) {
                return ArticleExtensionQueryResult.error("query article extension timeout");
            }
            MsgPacket response = lease.getPacket();
            ArticleExtensionQueryResult result = response.convertToClass(ArticleExtensionQueryResult.class);
            return result == null ? ArticleExtensionQueryResult.error("query article extension failed") : result;
        } catch (RuntimeException e) {
            return ArticleExtensionQueryResult.error("query article extension failed");
        }
    }

    private ResponseLease request(Object data, ActionType actionType, Duration timeout) {
        int msgId = IdUtil.getInt();
        session.sendMsg(new MsgPacket(data, ContentType.JSON,
                MsgPacketStatus.SEND_REQUEST, msgId, actionType.name()));
        return session.getResponseLeaseByMsgId(msgId, normalizeTimeout(timeout));
    }

    private Duration normalizeTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return DEFAULT_TIMEOUT;
        }
        return timeout;
    }
}
