package com.zrlog.plugin.api;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.data.codec.MsgPacket;

public interface IActionHandler {

    void service(final IOSession session, final MsgPacket msgPacket);

    void initConnect(final IOSession session, final MsgPacket msgPacket);

    void getFile(final IOSession session, final MsgPacket msgPacket);

    void loadWebSite(final IOSession session, final MsgPacket msgPacket);

    void setWebSite(final IOSession session, final MsgPacket msgPacket);

    void httpMethod(final IOSession session, final MsgPacket msgPacket);

    void addComment(final IOSession session, final MsgPacket msgPacket);

    void deleteComment(final IOSession session, final MsgPacket msgPacket);

    void plugin(final IOSession session, final MsgPacket msgPacket);

    void getDbProperties(final IOSession session, final MsgPacket msgPacket);

    void attachment(final IOSession session, final MsgPacket msgPacket);

    void loadPublicInfo(final IOSession session, final MsgPacket msgPacket);

    void getCurrentTemplate(final IOSession session, final MsgPacket msgPacket);

    void getBlogRuntimePath(final IOSession session, final MsgPacket msgPacket);

    void createArticle(final IOSession session, final MsgPacket msgPacket);

    default void getArticleExtension(final IOSession session, final MsgPacket msgPacket) {
    }

    default void setArticleExtension(final IOSession session, final MsgPacket msgPacket) {
    }

    default void queryArticleExtension(final IOSession session, final MsgPacket msgPacket) {
    }

    void refreshCache(final IOSession session, final MsgPacket msgPacket);

    void articleVisitViewCountAddOne(final IOSession session, final MsgPacket msgPacket);

    default void listComment(final IOSession session, final MsgPacket msgPacket) {
    }

    default void capabilityInvoke(final IOSession session, final MsgPacket msgPacket) {
    }

    default void notificationPublish(final IOSession session, final MsgPacket msgPacket) {
    }

    default void notificationChannelQuery(final IOSession session, final MsgPacket msgPacket) {
    }

    default void schedulerQuery(final IOSession session, final MsgPacket msgPacket) {
    }

    default void pluginProcessQuery(final IOSession session, final MsgPacket msgPacket) {
    }

    default void schedulerUpdate(final IOSession session, final MsgPacket msgPacket) {
    }
}
