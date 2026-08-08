package com.zrlog.plugin.client;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.data.codec.SocketCodec;
import com.zrlog.plugin.data.codec.SocketDecode;
import com.zrlog.plugin.data.codec.SocketEncode;
import com.zrlog.plugin.data.codec.SocketPacketLimits;
import com.zrlog.plugin.data.codec.SocketPacketMemoryBudget;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class NioClientTest {

    @Test
    public void shouldReadCacheableStaticPathsFromAssetManifest() {
        String manifest = "{"
                + "\"files\":{"
                + "\"main.js\":\"/static/js/main.12345678.js\","
                + "\"main.css\":\"https://cdn.example.com/plugin/static/css/main.12345678.css\","
                + "\"index.html\":\"/index.html\""
                + "},"
                + "\"entrypoints\":[\"static/js/runtime.js\",\"/static/css/main.12345678.css\"],"
                + "\"nested\":{\"logo\":\"/static/media/logo.svg?v=1\"}"
                + "}";

        Set<String> paths = NioClient.parseAssetManifestStaticPaths(manifest);

        assertTrue(paths.contains("/static/js/main.12345678.js"));
        assertTrue(paths.contains("/static/css/main.12345678.css"));
        assertTrue(paths.contains("/static/js/runtime.js"));
        assertTrue(paths.contains("/static/media/logo.svg"));
        assertFalse(paths.contains("/index.html"));
        assertEquals(4, paths.size());
    }

    @Test
    public void shouldCreateBoundedMessageHandlerExecutor() {
        ThreadPoolExecutor executor = NioClient.newMessageHandlerExecutor();
        try {
            assertEquals(4, executor.getCorePoolSize());
            assertEquals(4, executor.getMaximumPoolSize());
            assertEquals(8, executor.getQueue().remainingCapacity());
            assertTrue(executor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.CallerRunsPolicy);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldCreatePerSessionSocketPacketMemoryBudget() {
        SocketPacketMemoryBudget first = NioClient.newSocketPacketMemoryBudget();
        SocketPacketMemoryBudget second = NioClient.newSocketPacketMemoryBudget();

        assertEquals(32L * 1024 * 1024, first.getMaxBytes());
        assertEquals(SocketPacketLimits.DEFAULT_MAX_DATA_LENGTH_BYTES, first.getMaxBytes());
        assertNotSame(first, second);
        assertNotNull(NioClient.newSocketDecode(Runnable::run));
    }

    @Test
    public void shouldTerminateExactlyOnceWhenClientSessionCloses() throws Exception {
        AtomicInteger exitCount = new AtomicInteger();
        AtomicInteger exitStatus = new AtomicInteger(-1);
        NioClient client = new NioClient() {
            @Override
            void terminateProcess(int status) {
                exitStatus.set(status);
                exitCount.incrementAndGet();
            }
        };

        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel sender = SocketChannel.open(server.getLocalAddress());
                 SocketChannel receiver = server.accept();
                 Selector selector = Selector.open()) {
                IOSession session = new IOSession(receiver, selector,
                        new SocketCodec(new SocketEncode(), new SocketDecode(Runnable::run)),
                        new ClientActionHandler());
                client.exitWhenSessionCloses(session);

                session.close();
                session.close();

                assertEquals(1, exitCount.get());
                assertEquals(0, exitStatus.get());
            }
        }
    }
}
