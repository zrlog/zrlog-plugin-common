package com.zrlog.plugin;

import com.zrlog.plugin.api.IActionHandler;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.type.ActionType;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by xiaochun on 2016/2/12.
 */
public class MsgPacketDispose {

    private static final Logger LOGGER = LoggerUtil.getLogger(MsgPacketDispose.class);

    public void handler(final IOSession session, final MsgPacket msgPacket, IActionHandler actionHandler) {
        if (msgPacket.getStatus() == MsgPacketStatus.RESPONSE_SUCCESS || msgPacket.getStatus() == MsgPacketStatus.RESPONSE_ERROR) {
            return;
        }
        ActionType action = ActionType.valueOf(msgPacket.getMethodStr());
        if (action == ActionType.INIT_CONNECT) {
            actionHandler.initConnect(session, msgPacket);
        } else if (action == ActionType.HTTP_METHOD && isHeartbeatRequest(msgPacket)) {
            session.sendMsg(ContentType.BYTE, new byte[0], msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS, null);
        } else if (action == ActionType.HTTP_FILE) {
            actionHandler.getFile(session, msgPacket);
        } else if (action == ActionType.GET_WEBSITE) {
            actionHandler.loadWebSite(session, msgPacket);
        } else if (action == ActionType.SET_WEBSITE) {
            actionHandler.setWebSite(session, msgPacket);
        } else if (action == ActionType.ADD_COMMENT) {
            actionHandler.addComment(session, msgPacket);
        } else if (action == ActionType.SERVICE) {
            actionHandler.service(session, msgPacket);
        } else if (action == ActionType.PLUGIN_PROCESS_QUERY) {
            actionHandler.pluginProcessQuery(session, msgPacket);
        } else if (action.name().startsWith("PLUGIN")) {
            actionHandler.plugin(session, msgPacket);
        } else if (action == ActionType.HTTP_METHOD) {
            actionHandler.httpMethod(session, msgPacket);
        } else if (action == ActionType.DELETE_COMMENT) {
            actionHandler.deleteComment(session, msgPacket);
        } else if (action == ActionType.GET_DB_PROPERTIES) {
            actionHandler.getDbProperties(session, msgPacket);
        } else if (action == ActionType.HTTP_ATTACHMENT_FILE) {
            actionHandler.attachment(session, msgPacket);
        } else if (action == ActionType.LOAD_PUBLIC_INFO) {
            actionHandler.loadPublicInfo(session, msgPacket);
        } else if (action == ActionType.CURRENT_TEMPLATE) {
            actionHandler.getCurrentTemplate(session, msgPacket);
        } else if (action == ActionType.BLOG_RUN_TIME) {
            actionHandler.getBlogRuntimePath(session, msgPacket);
        } else if (action == ActionType.CREATE_ARTICLE) {
            actionHandler.createArticle(session, msgPacket);
        } else if (action == ActionType.ARTICLE_EXTENSION_GET) {
            actionHandler.getArticleExtension(session, msgPacket);
        } else if (action == ActionType.ARTICLE_EXTENSION_SET) {
            actionHandler.setArticleExtension(session, msgPacket);
        } else if (action == ActionType.ARTICLE_EXTENSION_QUERY) {
            actionHandler.queryArticleExtension(session, msgPacket);
        } else if (action == ActionType.REFRESH_CACHE) {
            actionHandler.refreshCache(session, msgPacket);
        } else if (action == ActionType.ARTICLE_VISIT_COUNT_ADD_ONE) {
            actionHandler.articleVisitViewCountAddOne(session, msgPacket);
        } else if (action == ActionType.LIST_COMMENT) {
            actionHandler.listComment(session, msgPacket);
        } else if (action == ActionType.CAPABILITY_INVOKE) {
            actionHandler.capabilityInvoke(session, msgPacket);
        } else if (action == ActionType.NOTIFICATION_PUBLISH) {
            actionHandler.notificationPublish(session, msgPacket);
        } else if (action == ActionType.NOTIFICATION_CHANNEL_QUERY) {
            actionHandler.notificationChannelQuery(session, msgPacket);
        } else if (action == ActionType.SCHEDULER_QUERY) {
            actionHandler.schedulerQuery(session, msgPacket);
        } else if (action == ActionType.SCHEDULER_UPDATE) {
            actionHandler.schedulerUpdate(session, msgPacket);
        } else {
            LOGGER.log(Level.WARNING, "UnSupport Method " + action);
        }
    }

    private boolean isHeartbeatRequest(MsgPacket msgPacket) {
        return msgPacket.getContentType() == ContentType.BYTE && msgPacket.getDataLength() == 0;
    }
}
