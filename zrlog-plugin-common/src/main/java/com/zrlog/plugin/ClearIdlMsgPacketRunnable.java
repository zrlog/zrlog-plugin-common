package com.zrlog.plugin;

import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.common.PluginExecutionTimeouts;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

class ClearIdlMsgPacketRunnable implements Runnable {

    private static final Logger LOGGER = LoggerUtil.getLogger(ClearIdlMsgPacketRunnable.class);

    private final List<Map<Integer, PipeInfo>> pipeMaps = new CopyOnWriteArrayList<>();

    public void addTask(Map<Integer, PipeInfo> pipeMap) {
        pipeMaps.add(Objects.requireNonNull(pipeMap, "pipeMap"));
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        for (Map<Integer, PipeInfo> pipeMap : pipeMaps) {
            try {
                clearExpired(pipeMap, now);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Unable to clear expired plugin messages", e);
            }
        }
    }

    private void clearExpired(Map<Integer, PipeInfo> pipeMap, long now) {
        for (Map.Entry<Integer, PipeInfo> entry : pipeMap.entrySet()) {
            try {
                PipeInfo pipeInfo = entry.getValue();
                Long expireAt = pipeInfo.getExpireAt();
                if (expireAt == null) {
                    expireAt = pipeInfo.getCratedAt() + PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT.toMillis();
                }
                if (now >= expireAt) {
                    if (pipeInfo.releaseExpiredResponse()) {
                        pipeMap.remove(entry.getKey(), pipeInfo);
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Unable to clear an expired plugin message", e);
            }
        }
    }

    public void removePipeMap(Map<Integer, PipeInfo> pipeMap) {
        boolean removed = pipeMaps.removeIf(candidate -> candidate == pipeMap);
        if (removed) {
            for (Map.Entry<Integer, PipeInfo> entry : pipeMap.entrySet()) {
                PipeInfo pipeInfo = entry.getValue();
                if (pipeMap.remove(entry.getKey(), pipeInfo)) {
                    pipeInfo.releaseResponse();
                }
            }
        }
    }

    public void removePipeByMsgId(Map<Integer, PipeInfo> pipeInfoMap, int msgId) {
        try {
            PipeInfo pipeInfo = pipeInfoMap.remove(msgId);
            if (pipeInfo != null) {
                pipeInfo.releaseResponse();
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Unable to remove plugin message " + msgId, e);
        }
    }

    public void removePipe(Map<Integer, PipeInfo> pipeInfoMap, int msgId, PipeInfo pipeInfo) {
        try {
            pipeInfoMap.remove(msgId, pipeInfo);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Unable to remove plugin message " + msgId, e);
        } finally {
            pipeInfo.releaseResponse();
        }
    }

    public void removeCompletedPipe(Map<Integer, PipeInfo> pipeInfoMap, int msgId, PipeInfo pipeInfo) {
        try {
            pipeInfoMap.remove(msgId, pipeInfo);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Unable to remove completed plugin message " + msgId, e);
        } finally {
            pipeInfo.completeResponse();
        }
    }
}
