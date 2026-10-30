package com.zrlog.plugin.support;

import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** OS-free channels for real IOSession dispatch tests; no transport is simulated beyond encoding. */
public final class MemorySocketChannels {
    public static class MemoryChannel extends SocketChannel {
        public MemoryChannel() { super(SelectorProvider.provider()); }
        protected void implCloseSelectableChannel() { }
        protected void implConfigureBlocking(boolean block) { }
        public SocketChannel bind(SocketAddress local) { return this; }
        public <T> SocketChannel setOption(SocketOption<T> option, T value) { return this; }
        public <T> T getOption(SocketOption<T> option) { return null; }
        public Set<SocketOption<?>> supportedOptions() { return Collections.emptySet(); }
        public SocketChannel shutdownInput() { return this; }
        public SocketChannel shutdownOutput() { return this; }
        public Socket socket() { throw new UnsupportedOperationException(); }
        public boolean isConnected() { return true; }
        public boolean isConnectionPending() { return false; }
        public boolean connect(SocketAddress remote) { return true; }
        public boolean finishConnect() { return true; }
        public SocketAddress getRemoteAddress() { return null; }
        public SocketAddress getLocalAddress() { return null; }
        public int read(ByteBuffer buffer) { throw new UnsupportedOperationException(); }
        public long read(ByteBuffer[] buffers, int offset, int length) { throw new UnsupportedOperationException(); }
        public int write(ByteBuffer buffer) { throw new UnsupportedOperationException(); }
        public long write(ByteBuffer[] buffers, int offset, int length) { throw new UnsupportedOperationException(); }
    }

    public static class MemorySelector extends AbstractSelector {
        public MemorySelector() { super(SelectorProvider.provider()); }
        protected void implCloseSelector() { }
        protected SelectionKey register(AbstractSelectableChannel channel, int ops, Object attachment) { throw new UnsupportedOperationException(); }
        public Set<SelectionKey> keys() { return Collections.emptySet(); }
        public Set<SelectionKey> selectedKeys() { return Collections.emptySet(); }
        public int selectNow() { return 0; }
        public int select(long timeout) { return 0; }
        public int select() { return 0; }
        public Selector wakeup() { return this; }
    }
}
