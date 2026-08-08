package com.zrlog.plugin;

import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugin.common.PluginExecutionTimeouts;
import com.zrlog.plugin.data.codec.MsgPacket;

import java.util.logging.Level;
import java.util.logging.Logger;

public class PipeInfo {

    private static final Logger LOGGER = LoggerUtil.getLogger(PipeInfo.class);

    public PipeInfo(MsgPacket requestMsgPackage, MsgPacket responseMsgPacket, IMsgPacketCallBack iMsgPacketCallBack, Long createdAt) {
        this(requestMsgPackage, responseMsgPacket, iMsgPacketCallBack, createdAt,
                createdAt + PluginExecutionTimeouts.DEFAULT_EXECUTION_TIMEOUT.toMillis());
    }

    public PipeInfo(MsgPacket requestMsgPackage,
                    MsgPacket responseMsgPacket,
                    IMsgPacketCallBack iMsgPacketCallBack,
                    Long createdAt,
                    Long expireAt) {
        this(requestMsgPackage, responseMsgPacket, iMsgPacketCallBack, createdAt, expireAt, null);
    }

    public PipeInfo(MsgPacket requestMsgPackage,
                    MsgPacket responseMsgPacket,
                    IMsgPacketCallBack iMsgPacketCallBack,
                    Long createdAt,
                    Long expireAt,
                    Runnable requestAbort) {
        this(requestMsgPackage, responseMsgPacket, iMsgPacketCallBack, createdAt, expireAt, requestAbort, null);
    }

    PipeInfo(MsgPacket requestMsgPackage,
             MsgPacket responseMsgPacket,
             IMsgPacketCallBack iMsgPacketCallBack,
             Long createdAt,
             Long expireAt,
             Runnable requestAbort,
             Runnable requestRelease) {
        this.requestMsgPackage = requestMsgPackage;
        this.responseMsgPacket = responseMsgPacket;
        this.iMsgPacketCallBack = iMsgPacketCallBack;
        this.cratedAt = createdAt;
        this.expireAt = expireAt;
        this.requestAbort = requestAbort;
        this.requestRelease = requestRelease;
    }

    private MsgPacket requestMsgPackage;
    private MsgPacket responseMsgPacket;

    private IMsgPacketCallBack iMsgPacketCallBack;
    private Long cratedAt;
    private Long expireAt;
    private Runnable responseRelease;
    private Runnable requestAbort;
    private Runnable requestRelease;
    private boolean responseTerminal;
    private boolean responseDispatchActive;

    public synchronized MsgPacket getResponseMsgPacket() {
        return responseMsgPacket;
    }

    public void setResponseMsgPacket(MsgPacket responseMsgPacket) {
        replaceResponse(responseMsgPacket, null, false);
    }

    public void setResponseMsgPacket(MsgPacket responseMsgPacket, Runnable release) {
        replaceResponse(responseMsgPacket, release, false);
    }

    boolean retainResponseForCallback(MsgPacket responseMsgPacket, Runnable release) {
        return replaceResponse(responseMsgPacket, release, true);
    }

    void finishResponseCallback() {
        synchronized (this) {
            responseDispatchActive = false;
        }
    }

    public void releaseResponse() {
        terminateResponse(true);
    }

    void completeResponse() {
        terminateResponse(false);
    }

    private void terminateResponse(boolean aborted) {
        Runnable release;
        Runnable abort;
        Runnable releaseRequest;
        synchronized (this) {
            releaseRequest = detachRequestRelease();
            if (responseDispatchActive) {
                release = null;
                abort = null;
            } else {
                responseTerminal = true;
                release = responseRelease;
                responseRelease = null;
                responseMsgPacket = null;
                abort = aborted ? requestAbort : null;
                requestAbort = null;
            }
        }
        runTerminalAction(releaseRequest, "Unable to release plugin request");
        runTerminalAction(release, "Unable to release plugin response");
        runTerminalAction(abort, "Unable to notify aborted plugin request");
    }

    boolean releaseExpiredResponse() {
        Runnable release;
        Runnable abort;
        Runnable releaseRequest;
        synchronized (this) {
            if (responseDispatchActive) {
                return false;
            }
            releaseRequest = detachRequestRelease();
            responseTerminal = true;
            release = responseRelease;
            responseRelease = null;
            responseMsgPacket = null;
            abort = requestAbort;
            requestAbort = null;
        }
        runTerminalAction(releaseRequest, "Unable to release expired plugin request");
        runTerminalAction(release, "Unable to release expired plugin response");
        runTerminalAction(abort, "Unable to notify expired plugin request");
        return true;
    }

    synchronized ResponseLease claimResponse() {
        if (responseTerminal || responseDispatchActive || responseMsgPacket == null) {
            return null;
        }
        responseTerminal = true;
        ResponseLease lease = new ResponseLease(responseMsgPacket, responseRelease);
        responseMsgPacket = null;
        responseRelease = null;
        requestAbort = null;
        return lease;
    }

    private static void runTerminalAction(Runnable action, String message) {
        if (action == null) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, message, e);
        }
    }

    private boolean replaceResponse(MsgPacket responseMsgPacket, Runnable release, boolean dispatchActive) {
        Runnable releaseNow;
        Runnable releaseRequest;
        boolean retained;
        synchronized (this) {
            if (responseTerminal || responseDispatchActive) {
                releaseNow = release;
                releaseRequest = null;
                retained = false;
            } else {
                releaseNow = responseRelease;
                releaseRequest = detachRequestRelease();
                this.responseMsgPacket = responseMsgPacket;
                responseRelease = release;
                responseDispatchActive = dispatchActive;
                retained = true;
            }
        }
        if (releaseNow != null) {
            releaseNow.run();
        }
        runTerminalAction(releaseRequest, "Unable to release completed plugin request");
        return retained;
    }

    private Runnable detachRequestRelease() {
        requestMsgPackage = null;
        Runnable release = requestRelease;
        requestRelease = null;
        return release;
    }

    public synchronized MsgPacket getRequestMsgPackage() {
        return requestMsgPackage;
    }

    public synchronized void setRequestMsgPackage(MsgPacket requestMsgPackage) {
        this.requestMsgPackage = requestMsgPackage;
    }

    public IMsgPacketCallBack getiMsgPacketCallBack() {
        return iMsgPacketCallBack;
    }

    public void setiMsgPacketCallBack(IMsgPacketCallBack iMsgPacketCallBack) {
        this.iMsgPacketCallBack = iMsgPacketCallBack;
    }

    public Long getCratedAt() {
        return cratedAt;
    }

    public void setCratedAt(Long cratedAt) {
        this.cratedAt = cratedAt;
    }

    public synchronized Long getExpireAt() {
        return expireAt;
    }

    public synchronized void setExpireAt(Long expireAt) {
        this.expireAt = expireAt;
    }

    public synchronized void extendExpireAt(Long expireAt) {
        if (expireAt == null) {
            return;
        }
        if (this.expireAt == null || this.expireAt < expireAt) {
            this.expireAt = expireAt;
        }
    }
}
