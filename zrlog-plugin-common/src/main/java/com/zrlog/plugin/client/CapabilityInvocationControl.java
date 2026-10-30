package com.zrlog.plugin.client;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.message.CapabilityCancelRequest;
import com.zrlog.plugin.message.CapabilityCancelResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded, session-scoped cancellation tracking; no business work runs on a cancellation worker. */
final class CapabilityInvocationControl {
    private static final int LIMIT = IOSession.DEFAULT_MAX_PENDING_REQUESTS;
    private final Map<Key, Execution> active = new LinkedHashMap<>();
    private final Map<Key, Integer> queuedCancellation = new LinkedHashMap<>();
    private final Map<Key, Boolean> completed = new LinkedHashMap<>();

    synchronized Execution begin(IOSession session, int messageId, String requestId) {
        Key key = new Key(session, messageId, requestId);
        if (active.size() >= LIMIT || active.containsKey(key) || completed.containsKey(key)) return null;
        Execution execution = new Execution(key, Thread.currentThread());
        execution.cancellation = queuedCancellation.remove(key);
        execution.cancelled = execution.cancellation != null;
        active.put(key, execution);
        return execution;
    }

    void cancel(IOSession session, MsgPacket packet, CapabilityCancelRequest request) {
        Key key = new Key(session, request.getInvocationMessageId(), request.getRequestId());
        boolean ended;
        synchronized (this) {
            ended = completed.containsKey(key);
            if (!ended) {
                Execution execution = active.get(key);
                if (execution != null) {
                    execution.cancelled = true;
                    // One caller owns an invocation. Never accumulate arbitrary cancellation callbacks.
                    if (execution.cancellation == null) execution.cancellation = packet.getMsgId();
                    // Interrupt under the same lock as finish(), so a reused pool thread is never interrupted late.
                    execution.thread.interrupt();
                } else {
                    trim(queuedCancellation);
                    queuedCancellation.putIfAbsent(key, packet.getMsgId());
                }
            }
        }
        if (ended) acknowledge(key, packet.getMsgId());
    }

    void finish(Execution execution) {
        Integer cancellation;
        synchronized (this) {
            active.remove(execution.key);
            trim(completed);
            completed.put(execution.key, Boolean.TRUE);
            cancellation = execution.cancellation;
        }
        if (execution.cancelled) Thread.interrupted();
        if (cancellation != null) acknowledge(execution.key, cancellation);
    }

    private static void acknowledge(Key key, int cancellation) {
        CapabilityCancelResult result = new CapabilityCancelResult();
        result.setRequestId(key.requestId);
        result.setExecutionCompleted(true);
        key.session.sendJsonMsg(result, com.zrlog.plugin.type.ActionType.CAPABILITY_CANCEL.name(), cancellation, MsgPacketStatus.RESPONSE_SUCCESS);
    }

    private static void trim(Map<?, ?> values) {
        if (values.size() >= LIMIT) values.remove(values.keySet().iterator().next());
    }

    static final class Execution {
        private final Key key;
        private final Thread thread;
        private Integer cancellation;
        private volatile boolean cancelled;
        private Execution(Key key, Thread thread) { this.key = key; this.thread = thread; }
        boolean isCancelled() { return cancelled; }
    }

    private static final class Key {
        private final IOSession session;
        private final int messageId;
        private final String requestId;
        private Key(IOSession session, int messageId, String requestId) {
            this.session = session; this.messageId = messageId; this.requestId = requestId;
        }
        @Override public int hashCode() { return Objects.hash(System.identityHashCode(session), messageId, requestId); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return session == key.session && messageId == key.messageId && Objects.equals(requestId, key.requestId);
        }
    }
}
