package com.zrlog.plugin.common;

import com.zrlog.plugin.IMsgPacketCallBack;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.ResponseLease;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.message.NotificationChannelQueryResult;
import com.zrlog.plugin.type.ActionType;

import java.time.Duration;
import java.util.HashMap;
import java.util.Objects;

public class SessionNotificationChannelRepository {

    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(15);

    private final IOSession session;

    public SessionNotificationChannelRepository(IOSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public static SessionNotificationChannelRepository of(IOSession session) {
        return new SessionNotificationChannelRepository(session);
    }

    public NotificationChannelQueryResult query() {
        return query(DEFAULT_READ_TIMEOUT);
    }

    public NotificationChannelQueryResult query(Duration readTimeout) {
        int msgId = request(null);
        try (ResponseLease lease = session.getResponseLeaseByMsgId(msgId, normalizeTimeout(readTimeout))) {
            if (lease == null) {
                return NotificationChannelQueryResult.error("query notification channels timeout");
            }
            MsgPacket response = lease.getPacket();
            NotificationChannelQueryResult result;
            try {
                result = response.convertToClass(NotificationChannelQueryResult.class);
            } catch (RuntimeException e) {
                return NotificationChannelQueryResult.error("query notification channels failed");
            }
            if (result == null) {
                return NotificationChannelQueryResult.error("query notification channels failed");
            }
            if (response.getStatus() == MsgPacketStatus.RESPONSE_ERROR) {
                result.setSuccess(false);
                if (result.getCode() == 0) {
                    result.setCode(1);
                }
                if (result.getMessage() == null || result.getMessage().trim().isEmpty()) {
                    result.setMessage("query notification channels failed");
                }
            }
            result.normalize();
            return result;
        }
    }

    public int request(IMsgPacketCallBack msgPacketCallBack) {
        int msgId = IdUtil.getInt();
        MsgPacket msgPacket = new MsgPacket(new HashMap<String, Object>(), ContentType.JSON,
                MsgPacketStatus.SEND_REQUEST, msgId, ActionType.NOTIFICATION_CHANNEL_QUERY.name());
        session.sendMsg(msgPacket, msgPacketCallBack);
        return msgId;
    }

    private Duration normalizeTimeout(Duration readTimeout) {
        if (readTimeout == null || readTimeout.toMillis() <= 0) {
            return DEFAULT_READ_TIMEOUT;
        }
        return readTimeout;
    }
}
