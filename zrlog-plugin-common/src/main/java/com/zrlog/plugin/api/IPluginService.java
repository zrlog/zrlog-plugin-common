package com.zrlog.plugin.api;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.data.codec.MsgPacket;

public interface IPluginService {

    /**
     * For a capability request with requireCompletion, complete all owned work (including child
     * operations and cleanup) before returning. Respond to interruption by stopping that work.
     * The SDK holds the response until this method exits; detached background work is outside
     * that completion contract and must not be used for request-bound capabilities.
     */
    void handle(IOSession session, MsgPacket msgPacket);
}
