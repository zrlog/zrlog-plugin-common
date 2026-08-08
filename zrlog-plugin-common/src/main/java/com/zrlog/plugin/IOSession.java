package com.zrlog.plugin;

import com.google.gson.Gson;
import com.zrlog.plugin.api.IActionHandler;
import com.zrlog.plugin.common.IOUtil;
import com.zrlog.plugin.common.IdUtil;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.common.PluginExecutionTimeouts;
import com.zrlog.plugin.data.codec.*;
import com.zrlog.plugin.data.codec.convert.JsonConvertMsgBody;
import com.zrlog.plugin.message.CapabilityInvokeRequest;
import com.zrlog.plugin.message.NotificationRequest;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.message.SchedulerQueryRequest;
import com.zrlog.plugin.message.SchedulerUpdateRequest;
import com.zrlog.plugin.render.IRenderHandler;
import com.zrlog.plugin.type.ActionType;

import java.io.File;
import java.nio.channels.Channel;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class IOSession {

    public static final String PLUGIN_LOG_LABEL_ATTR = "_zrlog_plugin_log_label";
    public static final int DEFAULT_MAX_PENDING_REQUESTS = 128;
    public static final long DEFAULT_MAX_PENDING_REQUEST_BYTES = SocketPacketLimits.DEFAULT_MAX_DATA_LENGTH_BYTES;
    private static final int MAX_PLUGIN_LOG_LABEL_LENGTH = 128;

    private static final Logger LOGGER = LoggerUtil.getLogger(IOSession.class);
    private static final SocketPacketMemoryBudget GLOBAL_PENDING_REQUEST_MEMORY_BUDGET =
            new SocketPacketMemoryBudget(DEFAULT_MAX_PENDING_REQUEST_BYTES);

    private final Map<String, Object> attr = new ConcurrentHashMap<>();
    private final Map<Integer, PipeInfo> pipeMap = new ConcurrentHashMap<>();
    private final Map<String, Object> systemAttr = new ConcurrentHashMap<>();
    private final IActionHandler actionHandler;
    private volatile Plugin plugin;
    private final AtomicLong sendMsgCounter = new AtomicLong(0);
    private final AtomicLong receiveMsgCounter = new AtomicLong(0);
    private final MsgPacketDispose msgPacketDispose = new MsgPacketDispose();
    private final IRenderHandler renderHandler;
    private final SocketEncode socketEncode;
    private final int maxPendingRequests;
    private final SocketPacketMemoryBudget pendingRequestMemoryBudget;
    private final SocketPacketMemoryBudget globalPendingRequestMemoryBudget;
    private final Object dispatchLifecycleMonitor = new Object();
    private final List<Runnable> closeListeners = new ArrayList<>();
    private boolean closed;
    private boolean resourcesClosed;
    private boolean pipeCleanupStarted;
    private int activeDispatches;
    private static final ReentrantLock lock = new ReentrantLock();
    private static ClearIdlMsgPacketRunnable clearIdlMsgPacketRunnable;
    private static ScheduledExecutorService executor;

    public IOSession(SocketChannel channel, Selector selector, SocketCodec socketCodec, IActionHandler actionHandler, IRenderHandler renderHandler) {
        this(channel, selector, socketCodec, actionHandler, renderHandler, DEFAULT_MAX_PENDING_REQUESTS,
                new SocketPacketMemoryBudget(DEFAULT_MAX_PENDING_REQUEST_BYTES),
                GLOBAL_PENDING_REQUEST_MEMORY_BUDGET);
    }

    IOSession(SocketChannel channel, Selector selector, SocketCodec socketCodec, IActionHandler actionHandler,
              IRenderHandler renderHandler, int maxPendingRequests) {
        this(channel, selector, socketCodec, actionHandler, renderHandler, maxPendingRequests,
                new SocketPacketMemoryBudget(DEFAULT_MAX_PENDING_REQUEST_BYTES),
                GLOBAL_PENDING_REQUEST_MEMORY_BUDGET);
    }

    IOSession(SocketChannel channel,
              Selector selector,
              SocketCodec socketCodec,
              IActionHandler actionHandler,
              IRenderHandler renderHandler,
              int maxPendingRequests,
              SocketPacketMemoryBudget pendingRequestMemoryBudget,
              SocketPacketMemoryBudget globalPendingRequestMemoryBudget) {
        if (maxPendingRequests <= 0) {
            throw new IllegalArgumentException("maxPendingRequests must be greater than zero");
        }
        systemAttr.put("_channel", channel);
        systemAttr.put("_selector", selector);
        systemAttr.put("_decode", socketCodec.getSocketDecode());
        systemAttr.put("_encode", socketCodec.getSocketEncode());
        systemAttr.put("_actionHandler", actionHandler);

        this.socketEncode = socketCodec.getSocketEncode();
        this.maxPendingRequests = maxPendingRequests;
        this.pendingRequestMemoryBudget = Objects.requireNonNull(pendingRequestMemoryBudget,
                "pendingRequestMemoryBudget");
        this.globalPendingRequestMemoryBudget = Objects.requireNonNull(globalPendingRequestMemoryBudget,
                "globalPendingRequestMemoryBudget");
        this.actionHandler = actionHandler;
        this.renderHandler = renderHandler;
        lock.lock();
        try {
            if (Objects.isNull(executor)) {
                executor = Executors.newSingleThreadScheduledExecutor();
                clearIdlMsgPacketRunnable = new ClearIdlMsgPacketRunnable();
                executor.scheduleAtFixedRate(clearIdlMsgPacketRunnable, 0, 1, TimeUnit.SECONDS);
            }
        } finally {
            lock.unlock();
        }
        clearIdlMsgPacketRunnable.addTask(pipeMap);
    }

    public AtomicLong getSendMsgCounter() {
        return sendMsgCounter;
    }

    public AtomicLong getReceiveMsgCounter() {
        return receiveMsgCounter;
    }

    public IOSession(SocketChannel channel, Selector selector, SocketCodec socketCodec, IActionHandler actionHandler) {
        this(channel, selector, socketCodec, actionHandler, null);
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public void setPlugin(Plugin plugin) {
        this.plugin = plugin;
        setPluginLogLabel(pluginLogLabel(plugin));
    }

    public void setPluginLogLabel(String label) {
        String normalizedLabel = normalize(label);
        if (normalizedLabel == null) {
            systemAttr.remove(PLUGIN_LOG_LABEL_ATTR);
        } else {
            systemAttr.put(PLUGIN_LOG_LABEL_ATTR, normalizedLabel);
        }
    }

    public String logPrefix(String message) {
        String label = pluginLogLabel();
        if (label == null) {
            return message;
        }
        return "[" + label + "] " + message;
    }

    private String pluginLogLabel() {
        Object label = systemAttr.get(PLUGIN_LOG_LABEL_ATTR);
        if (label != null) {
            String normalized = normalize(label.toString());
            if (normalized != null) {
                return normalized;
            }
        }
        return pluginLogLabel(plugin);
    }

    private String pluginLogLabel(Plugin plugin) {
        if (plugin == null) {
            return null;
        }
        return normalize(plugin.getShortName());
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || trimmed.length() > MAX_PLUGIN_LOG_LABEL_LENGTH ? null : trimmed;
    }

    public <T> T getResponseSync(ContentType contentType, Object data, ActionType actionType, Class<T> clazz) {
        int msgId = IdUtil.getInt();
        MsgPacketStatus status = MsgPacketStatus.SEND_REQUEST;
        MsgPacket msgPacket = new MsgPacket(data, contentType, status, msgId, actionType.name());
        sendMsg(msgPacket);
        try (ResponseLease lease = getResponseLeaseByMsgId(msgId)) {
            if (lease == null) {
                return null;
            }
            MsgPacket response = lease.getPacket();
            if (response.getStatus() == MsgPacketStatus.RESPONSE_SUCCESS) {
                if (response.getContentType() == ContentType.JSON) {
                    return new JsonConvertMsgBody().toObj(response.getData(), clazz);
                }
            } else {
                throw new RuntimeException("some error");
            }
            throw new RuntimeException("unSupport response " + response.getContentType());
        }
    }

    public void sendMsg(ContentType contentType, Object data, String methodStr, int msgId, MsgPacketStatus status, IMsgPacketCallBack callBack) {
        MsgPacket msgPacket = new MsgPacket(data, contentType, status, msgId, methodStr);
        sendMsg(msgPacket, callBack);
    }

    public void sendMsg(ContentType contentType, Object data, String methodStr, int msgId, MsgPacketStatus status) {
        MsgPacket msgPacket = new MsgPacket(data, contentType, status, msgId, methodStr);
        sendMsg(msgPacket, null);
    }

    public void sendMsg(MsgPacket msgPacket, IMsgPacketCallBack callBack) {
        sendMsg(msgPacket, callBack, PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT);
    }

    public void sendMsg(MsgPacket msgPacket, IMsgPacketCallBack callBack, Duration responseTimeout) {
        sendMsgIfOpen(msgPacket, callBack, responseTimeout, null);
    }

    private boolean sendMsgIfOpen(MsgPacket msgPacket,
                                  IMsgPacketCallBack callBack,
                                  Duration responseTimeout,
                                  Runnable requestAbort) {
        PipeInfo registeredPipeInfo = null;
        PipeInfo displacedPipeInfo = null;
        String rejectionReason = null;
        try {
            ensurePluginLogLabel();
            synchronized (dispatchLifecycleMonitor) {
                if (closed) {
                    rejectionReason = "session is closed";
                } else if (msgPacket.getStatus() == MsgPacketStatus.SEND_REQUEST) {
                    int msgId = msgPacket.getMsgId();
                    PipeInfo currentPipeInfo = pipeMap.get(msgId);
                    if (currentPipeInfo == null && pipeMap.size() >= maxPendingRequests) {
                        rejectionReason = "pending request limit " + maxPendingRequests + " reached";
                    } else {
                        int requestBytes = retainedRequestBytes(msgPacket);
                        if (!pendingRequestMemoryBudget.tryReserve(requestBytes)) {
                            rejectionReason = "pending request memory limit "
                                    + pendingRequestMemoryBudget.getMaxBytes() + " bytes reached";
                        } else if (!globalPendingRequestMemoryBudget.tryReserve(requestBytes)) {
                            pendingRequestMemoryBudget.release(requestBytes);
                            rejectionReason = "global pending request memory limit "
                                    + globalPendingRequestMemoryBudget.getMaxBytes() + " bytes reached";
                        } else {
                            Runnable requestRelease = new PendingRequestReservation(
                                    pendingRequestMemoryBudget, globalPendingRequestMemoryBudget, requestBytes);
                            long now = System.currentTimeMillis();
                            registeredPipeInfo = new PipeInfo(msgPacket, null, callBack, now,
                                    now + responseTimeout(responseTimeout).toMillis(), requestAbort, requestRelease);
                            displacedPipeInfo = pipeMap.put(msgId, registeredPipeInfo);
                        }
                    }
                }
            }
            if (rejectionReason != null) {
                LOGGER.fine(logPrefix("Reject plugin message " + msgPacket.getMsgId() + ": " + rejectionReason));
                return false;
            }
            if (displacedPipeInfo != null) {
                displacedPipeInfo.releaseResponse();
            }
            socketEncode.doEncode(this, msgPacket);
            return true;
        } catch (Exception e) {
            rollbackRegisteredPipeInfo(msgPacket.getMsgId(), registeredPipeInfo);
            closeAfterSendFailure(e);
            LOGGER.log(Level.SEVERE, logPrefix("Unable to send plugin message " + msgPacket.getMsgId()), e);
            return false;
        } catch (Error e) {
            rollbackRegisteredPipeInfo(msgPacket.getMsgId(), registeredPipeInfo);
            closeAfterSendFailure(e);
            throw e;
        }
    }

    private void sendRequestOrThrow(MsgPacket msgPacket,
                                    IMsgPacketCallBack callBack,
                                    Duration responseTimeout) {
        sendRequestOrThrow(msgPacket, callBack, responseTimeout, null);
    }

    private void sendRequestOrThrow(MsgPacket msgPacket,
                                    IMsgPacketCallBack callBack,
                                    Duration responseTimeout,
                                    Runnable requestAbort) {
        if (!sendMsgIfOpen(msgPacket, callBack, responseTimeout, requestAbort)) {
            throw new IllegalStateException("Unable to send plugin request " + msgPacket.getMsgId()
                    + " (" + msgPacket.getMethodStr() + ")");
        }
    }

    private void rollbackRegisteredPipeInfo(int msgId, PipeInfo registeredPipeInfo) {
        if (registeredPipeInfo != null) {
            pipeMap.remove(msgId, registeredPipeInfo);
            registeredPipeInfo.releaseResponse();
        }
    }

    private int retainedRequestBytes(MsgPacket msgPacket) {
        return msgPacket.getData() == null ? 0 : msgPacket.getData().capacity();
    }

    private void closeAfterSendFailure(Throwable failure) {
        try {
            close();
        } catch (RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private void ensurePluginLogLabel() {
        if (systemAttr.get(PLUGIN_LOG_LABEL_ATTR) != null) {
            return;
        }
        setPluginLogLabel(pluginLogLabel(plugin));
    }

    public void sendMsg(MsgPacket msgPacket) {
        sendMsg(msgPacket, null);
    }

    public void sendJsonMsg(Object data, String method, int id, MsgPacketStatus status) {
        sendMsg(ContentType.JSON, data, method, id, status, null);
    }

    public void sendJsonMsg(Object data, String method, int id, MsgPacketStatus status, IMsgPacketCallBack callBack) {
        sendMsg(ContentType.JSON, data, method, id, status, callBack);
    }

    public void responseHtml(String templatePath, Map dataMap, String method, int id, IMsgPacketCallBack callBack) {
        if (renderHandler != null) {
            sendMsg(ContentType.HTML, renderHandler.render(templatePath, getPlugin(), dataMap), method, id, MsgPacketStatus.RESPONSE_SUCCESS, callBack);
        } else {
            sendMsg(ContentType.HTML, IOUtil.getStringInputStream(IOSession.class.getResourceAsStream(templatePath)), method, id, MsgPacketStatus.RESPONSE_SUCCESS, callBack);
        }
    }

    public void responseHtmlStr(String htmlString, String method, int id) {
        sendMsg(ContentType.HTML, htmlString, method, id, MsgPacketStatus.RESPONSE_SUCCESS, null);
    }

    public void responseXmlStr(String htmlString, String method, int id) {
        sendMsg(ContentType.XML, htmlString, method, id, MsgPacketStatus.RESPONSE_SUCCESS, null);
    }

    public void responseHtmlStr(String htmlString, String method, int id, IMsgPacketCallBack callBack) {
        sendMsg(ContentType.HTML, htmlString, method, id, MsgPacketStatus.RESPONSE_SUCCESS, callBack);
    }

    public void responseHtml(String templatePath, Map dataMap, String method, int id) {
        responseHtml(templatePath, dataMap, method, id, null);
    }

    public void sendFileMsg(File file, int id, MsgPacketStatus status) {
        MsgPacket msgPacket = new MsgPacket(file, ContentType.FILE, status, id,
                ActionType.HTTP_ATTACHMENT_FILE.name());
        if (!sendMsgIfOpen(msgPacket, null, PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT, null)) {
            throw new IllegalStateException("Unable to send plugin FILE message " + id
                    + " (" + ActionType.HTTP_ATTACHMENT_FILE.name() + ")");
        }
    }

    public int requestService(String name, Map map, IMsgPacketCallBack msgPacketCallBack) {
        return requestService(name, map, msgPacketCallBack, PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT);
    }

    public int requestService(String name, Map map, IMsgPacketCallBack msgPacketCallBack, Duration responseTimeout) {
        return requestService(name, map, msgPacketCallBack, responseTimeout, null);
    }

    public int requestService(String name,
                              Map map,
                              IMsgPacketCallBack msgPacketCallBack,
                              Duration responseTimeout,
                              Runnable requestAbort) {
        int msgId = IdUtil.getInt();
        map.put("name", name);
        MsgPacket msgPacket = new MsgPacket(map, ContentType.JSON, MsgPacketStatus.SEND_REQUEST, msgId, ActionType.SERVICE.name());
        sendRequestOrThrow(msgPacket, msgPacketCallBack, responseTimeout, requestAbort);
        return msgId;
    }

    public int requestCapability(String pluginId, String capabilityKey, Map<String, Object> payload, IMsgPacketCallBack msgPacketCallBack) {
        int msgId = IdUtil.getInt();
        CapabilityInvokeRequest request = new CapabilityInvokeRequest();
        request.setPluginId(pluginId);
        request.setCapabilityKey(capabilityKey);
        request.setPayload(payload);
        MsgPacket msgPacket = new MsgPacket(request, ContentType.JSON, MsgPacketStatus.SEND_REQUEST, msgId, ActionType.CAPABILITY_INVOKE.name());
        sendRequestOrThrow(msgPacket, msgPacketCallBack, PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT);
        return msgId;
    }

    public int publishNotification(NotificationRequest request, IMsgPacketCallBack msgPacketCallBack) {
        int msgId = IdUtil.getInt();
        MsgPacket msgPacket = new MsgPacket(request, ContentType.JSON, MsgPacketStatus.SEND_REQUEST, msgId, ActionType.NOTIFICATION_PUBLISH.name());
        sendRequestOrThrow(msgPacket, msgPacketCallBack, PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT);
        return msgId;
    }

    public int querySchedule(String capabilityKey, IMsgPacketCallBack msgPacketCallBack) {
        int msgId = IdUtil.getInt();
        SchedulerQueryRequest request = new SchedulerQueryRequest();
        request.setCapabilityKey(capabilityKey);
        MsgPacket msgPacket = new MsgPacket(request, ContentType.JSON, MsgPacketStatus.SEND_REQUEST, msgId, ActionType.SCHEDULER_QUERY.name());
        sendRequestOrThrow(msgPacket, msgPacketCallBack, PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT);
        return msgId;
    }

    public int querySchedule(String capabilityKey) {
        return querySchedule(capabilityKey, null);
    }

    public int queryPluginProcess(IMsgPacketCallBack msgPacketCallBack, Duration responseTimeout) {
        int msgId = IdUtil.getInt();
        MsgPacket msgPacket = new MsgPacket(new byte[0], ContentType.BYTE, MsgPacketStatus.SEND_REQUEST, msgId,
                ActionType.PLUGIN_PROCESS_QUERY.name());
        sendRequestOrThrow(msgPacket, msgPacketCallBack, responseTimeout);
        return msgId;
    }

    public int queryPluginProcess(IMsgPacketCallBack msgPacketCallBack) {
        return queryPluginProcess(msgPacketCallBack, PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT);
    }

    public int queryPluginProcess() {
        return queryPluginProcess(null);
    }

    /**
     * @deprecated Scheduler writes are managed by the runtime scheduler center.
     */
    @Deprecated
    public int updateSchedule(String capabilityKey, String cron, Boolean enabled, IMsgPacketCallBack msgPacketCallBack) {
        int msgId = IdUtil.getInt();
        SchedulerUpdateRequest request = new SchedulerUpdateRequest();
        request.setCapabilityKey(capabilityKey);
        request.setCron(cron);
        request.setEnabled(enabled);
        MsgPacket msgPacket = new MsgPacket(request, ContentType.JSON, MsgPacketStatus.SEND_REQUEST, msgId, ActionType.SCHEDULER_UPDATE.name());
        sendRequestOrThrow(msgPacket, msgPacketCallBack, PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT);
        return msgId;
    }

    /**
     * @deprecated Scheduler writes are managed by the runtime scheduler center.
     */
    @Deprecated
    public int updateSchedule(String capabilityKey, String cron, Boolean enabled) {
        return updateSchedule(capabilityKey, cron, enabled, null);
    }

    /**
     * @deprecated Scheduler writes are managed by the runtime scheduler center.
     */
    @Deprecated
    public int updateSchedule(String capabilityKey, String cron, IMsgPacketCallBack msgPacketCallBack) {
        return updateSchedule(capabilityKey, cron, null, msgPacketCallBack);
    }

    /**
     * @deprecated Scheduler writes are managed by the runtime scheduler center.
     */
    @Deprecated
    public int updateSchedule(String capabilityKey, String cron) {
        return updateSchedule(capabilityKey, cron, null, null);
    }

    /**
     * @deprecated Scheduler writes are managed by the runtime scheduler center.
     */
    @Deprecated
    public int updateScheduleEnabled(String capabilityKey, boolean enabled, IMsgPacketCallBack msgPacketCallBack) {
        return updateSchedule(capabilityKey, null, enabled, msgPacketCallBack);
    }

    /**
     * @deprecated Scheduler writes are managed by the runtime scheduler center.
     */
    @Deprecated
    public int updateScheduleEnabled(String capabilityKey, boolean enabled) {
        return updateScheduleEnabled(capabilityKey, enabled, null);
    }

    public int requestService(String name, Map map) {
        return requestService(name, map, null);
    }

    public <T> T callService(String name, Map map, Class<T> clazz) {
        int messageId = requestService(name, map);
        try (ResponseLease lease = getResponseLeaseByMsgId(messageId)) {
            return new Gson().fromJson(new String(lease.getPacket().getData().array()), clazz);
        }
    }

    public void dispose(MsgPacket msgPacket) {
        dispose(msgPacket, () -> {
        });
    }

    public void dispatchIfOpen(MsgPacket msgPacket, Runnable release) {
        synchronized (dispatchLifecycleMonitor) {
            if (closed) {
                release.run();
                return;
            }
            activeDispatches++;
        }
        try {
            dispose(msgPacket, release);
        } finally {
            completeDispatch();
        }
    }

    public void dispose(MsgPacket msgPacket, Runnable release) {
        boolean releaseTransferred = false;
        try {
            if (msgPacket.getStatus() == MsgPacketStatus.RESPONSE_SUCCESS || msgPacket.getStatus() == MsgPacketStatus.RESPONSE_ERROR) {
                PipeInfo pipeInfo = pipeMap.get(msgPacket.getMsgId());
                if (pipeInfo != null) {
                    IMsgPacketCallBack callBack = pipeInfo.getiMsgPacketCallBack();
                    boolean callbackResponse = callBack != null;
                    if (callbackResponse) {
                        releaseTransferred = true;
                        if (!pipeInfo.retainResponseForCallback(msgPacket, release)) {
                            return;
                        }
                    } else {
                        releaseTransferred = true;
                        pipeInfo.setResponseMsgPacket(msgPacket, release);
                    }
                    if (pipeMap.get(msgPacket.getMsgId()) != pipeInfo) {
                        if (callbackResponse) {
                            pipeInfo.finishResponseCallback();
                        }
                        pipeInfo.completeResponse();
                        return;
                    }
                    if (callBack != null) {
                        try {
                            callBack.handler(msgPacket);
                        } finally {
                            pipeInfo.finishResponseCallback();
                            clearIdlMsgPacketRunnable.removeCompletedPipe(pipeMap, msgPacket.getMsgId(), pipeInfo);
                        }
                        // 不进行多次处理
                        return;
                    }
                    return;
                }
            }
            msgPacketDispose.handler(this, msgPacket, actionHandler);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "handle error", e);
        } finally {
            if (!releaseTransferred) {
                release.run();
            }
        }
    }

    public void addCloseListener(Runnable listener) {
        List<Runnable> listeners = null;
        synchronized (dispatchLifecycleMonitor) {
            closeListeners.add(Objects.requireNonNull(listener, "listener"));
            listeners = drainCloseListenersIfReady();
        }
        runCloseListeners(listeners);
    }

    public boolean isClosed() {
        synchronized (dispatchLifecycleMonitor) {
            return closed;
        }
    }

    public void close() {
        boolean closeResources = false;
        synchronized (dispatchLifecycleMonitor) {
            if (!closed) {
                closed = true;
                closeResources = true;
            }
        }
        if (closeResources) {
            closeResources();
        }
        boolean closePipes;
        synchronized (dispatchLifecycleMonitor) {
            if (closeResources) {
                resourcesClosed = true;
            }
            closePipes = claimPipeCleanupIfReady();
        }
        completeClose(closePipes);
    }

    private void closeResources() {
        try {
            ((Channel) systemAttr.get("_channel")).close();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "", e);
        } finally {
            Object decoder = systemAttr.get("_decode");
            if (decoder instanceof SocketDecode) {
                ((SocketDecode) decoder).close();
            }
        }
    }

    private void completeDispatch() {
        boolean closePipes;
        synchronized (dispatchLifecycleMonitor) {
            activeDispatches = Math.max(0, activeDispatches - 1);
            closePipes = claimPipeCleanupIfReady();
        }
        completeClose(closePipes);
    }

    private boolean claimPipeCleanupIfReady() {
        if (!closed || !resourcesClosed || pipeCleanupStarted) {
            return false;
        }
        pipeCleanupStarted = true;
        return true;
    }

    private void completeClose(boolean closePipes) {
        try {
            if (closePipes) {
                clearIdlMsgPacketRunnable.removePipeMap(pipeMap);
            }
        } finally {
            List<Runnable> listeners;
            synchronized (dispatchLifecycleMonitor) {
                listeners = drainCloseListenersIfReady();
            }
            runCloseListeners(listeners);
        }
    }

    private List<Runnable> drainCloseListenersIfReady() {
        if (!closed || !resourcesClosed || closeListeners.isEmpty()) {
            return null;
        }
        List<Runnable> listeners = new ArrayList<>(closeListeners);
        closeListeners.clear();
        return listeners;
    }

    private void runCloseListeners(List<Runnable> listeners) {
        if (listeners == null) {
            return;
        }
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Unable to run plugin session close listener", e);
            }
        }
    }

    public Map<Integer, PipeInfo> getPipeMap() {
        return pipeMap;
    }

    public Map<String, Object> getSystemAttr() {
        return systemAttr;
    }

    public Map<String, Object> getAttr() {
        return attr;
    }

    public MsgPacket getRequestMsgPacketByMsgId(int msgId) {
        PipeInfo pipeInfo = pipeMap.get(msgId);
        if (Objects.isNull(pipeInfo)) {
            return null;
        }
        return pipeInfo.getRequestMsgPackage();
    }

    /**
     * @deprecated Use {@link #getResponseLeaseByMsgId(int)} and close the lease after consuming the packet.
     */
    @Deprecated
    public MsgPacket getResponseMsgPacketByMsgId(int msgId) {
        return getResponseMsgPacketByMsgId(msgId, PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT);
    }

    /**
     * @deprecated Use {@link #getResponseLeaseByMsgId(int, Duration)} and close the lease after consuming the packet.
     */
    @Deprecated
    public MsgPacket getResponseMsgPacketByMsgId(int msgId, Duration readTimeout) {
        try (ResponseLease lease = getResponseLeaseByMsgId(msgId, readTimeout)) {
            return lease == null ? null : lease.getPacket();
        }
    }

    public ResponseLease getResponseLeaseByMsgId(int msgId) {
        return getResponseLeaseByMsgId(msgId, PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT);
    }

    public ResponseLease getResponseLeaseByMsgId(int msgId, Duration readTimeout) {
        Duration timeoutDuration = responseTimeout(readTimeout);
        long timeout = timeoutDuration.toMillis();
        PipeInfo ownedPipe = pipeMap.get(msgId);
        if (ownedPipe == null) {
            return null;
        }
        ownedPipe.extendExpireAt(System.currentTimeMillis() + timeoutDuration.toMillis());
        try {
            int sleepSeek = 10;
            while (true) {
                if (pipeMap.get(msgId) != ownedPipe) {
                    return null;
                }
                ResponseLease lease = ownedPipe.claimResponse();
                if (lease != null) {
                    pipeMap.remove(msgId, ownedPipe);
                    return lease;
                }
                if (timeout <= 0) {
                    return null;
                }
                try {
                    Thread.sleep(Math.min(sleepSeek, timeout));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                timeout -= sleepSeek;
            }
        } finally {
            if (pipeMap.remove(msgId, ownedPipe)) {
                ownedPipe.releaseResponse();
            }
        }
    }

    public void clearMessageCacheByMsgId(int msgId) {
        clearIdlMsgPacketRunnable.removePipeByMsgId(pipeMap, msgId);
    }

    private Duration responseTimeout(Duration readTimeout) {
        if (readTimeout == null || readTimeout.toMillis() <= 0) {
            return PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT;
        }
        return readTimeout;
    }

    private static final class PendingRequestReservation implements Runnable {

        private final SocketPacketMemoryBudget sessionBudget;
        private final SocketPacketMemoryBudget globalBudget;
        private final int reservedBytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private PendingRequestReservation(SocketPacketMemoryBudget sessionBudget,
                                          SocketPacketMemoryBudget globalBudget,
                                          int reservedBytes) {
            this.sessionBudget = sessionBudget;
            this.globalBudget = globalBudget;
            this.reservedBytes = reservedBytes;
        }

        @Override
        public void run() {
            if (released.compareAndSet(false, true)) {
                sessionBudget.release(reservedBytes);
                globalBudget.release(reservedBytes);
            }
        }
    }

}
