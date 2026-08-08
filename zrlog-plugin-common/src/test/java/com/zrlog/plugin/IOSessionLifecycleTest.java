package com.zrlog.plugin;

import com.zrlog.plugin.client.ClientActionHandler;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.data.codec.SocketCodec;
import com.zrlog.plugin.data.codec.SocketDecode;
import com.zrlog.plugin.data.codec.SocketEncode;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.type.ActionType;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IOSessionLifecycleTest {

    @Test
    public void shouldSkipDispatchAfterSessionCloses() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        try (SessionHarness harness = new SessionHarness(new ClientActionHandler() {
            @Override
            public void initConnect(IOSession session, MsgPacket msgPacket) {
                handled.incrementAndGet();
            }
        })) {
            harness.session.addCloseListener(closed::incrementAndGet);
            harness.session.close();

            harness.session.dispatchIfOpen(initPacket(), released::incrementAndGet);

            assertEquals(0, handled.get());
            assertEquals(1, released.get());
            assertEquals(1, closed.get());
        }
    }

    @Test
    public void shouldRunCloseListenerWhenResourcesCloseWhileDispatchRemainsActive() throws Exception {
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        AtomicInteger released = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        try (SessionHarness harness = new SessionHarness(new ClientActionHandler() {
            @Override
            public void initConnect(IOSession session, MsgPacket msgPacket) {
                handlerStarted.countDown();
                try {
                    releaseHandler.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        })) {
            harness.session.addCloseListener(closed::incrementAndGet);
            Thread dispatchThread = new Thread(() ->
                    harness.session.dispatchIfOpen(initPacket(), released::incrementAndGet));
            dispatchThread.start();
            assertTrue(handlerStarted.await(5, TimeUnit.SECONDS));

            harness.session.close();
            assertEquals(1, closed.get());

            releaseHandler.countDown();
            dispatchThread.join(5000);
            assertEquals(1, released.get());
            assertEquals(1, closed.get());
        } finally {
            releaseHandler.countDown();
        }
    }

    @Test
    public void unrelatedActiveDispatchShouldNotDelayPendingRequestAbortOnClose() throws Exception {
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        AtomicInteger aborts = new AtomicInteger();
        try (SessionHarness harness = new SessionHarness(new ClientActionHandler() {
            @Override
            public void initConnect(IOSession session, MsgPacket msgPacket) {
                handlerStarted.countDown();
                try {
                    releaseHandler.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        })) {
            int msgId = 16;
            long now = System.currentTimeMillis();
            harness.session.getPipeMap().put(msgId,
                    new PipeInfo(initPacket(), null, null, now, now + TimeUnit.MINUTES.toMillis(10),
                            aborts::incrementAndGet));
            Thread dispatchThread = new Thread(() ->
                    harness.session.dispatchIfOpen(initPacket(), () -> {
                    }));
            dispatchThread.start();
            assertTrue(handlerStarted.await(5, TimeUnit.SECONDS));

            harness.session.close();

            assertEquals(1, aborts.get());
            assertFalse(harness.session.getPipeMap().containsKey(msgId));
            assertTrue(dispatchThread.isAlive());

            releaseHandler.countDown();
            dispatchThread.join(5000);
            assertFalse(dispatchThread.isAlive());
        } finally {
            releaseHandler.countDown();
        }
    }

    @Test
    public void shouldHoldResponseReservationUntilCallbackCompletesWhenSessionCloses() throws Exception {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicInteger released = new AtomicInteger();
        AtomicInteger aborts = new AtomicInteger();
        try (SessionHarness harness = new SessionHarness(new ClientActionHandler())) {
            int msgId = 17;
            long now = System.currentTimeMillis();
            PipeInfo pipeInfo = new PipeInfo(initPacket(), null, response -> {
                callbackStarted.countDown();
                try {
                    releaseCallback.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, now, now + TimeUnit.SECONDS.toMillis(5), aborts::incrementAndGet);
            harness.session.getPipeMap().put(msgId, pipeInfo);
            MsgPacket response = new MsgPacket(new byte[]{1}, ContentType.BYTE,
                    MsgPacketStatus.RESPONSE_SUCCESS, msgId, ActionType.SERVICE.name());

            Thread dispatchThread = new Thread(() -> harness.session.dispatchIfOpen(response, released::incrementAndGet));
            dispatchThread.start();
            assertTrue(callbackStarted.await(5, TimeUnit.SECONDS));

            harness.session.close();
            assertEquals(0, released.get());
            assertEquals(0, aborts.get());

            releaseCallback.countDown();
            dispatchThread.join(5000);
            assertFalse(dispatchThread.isAlive());
            assertEquals(1, released.get());
            assertEquals(0, aborts.get());
        } finally {
            releaseCallback.countDown();
        }
    }

    @Test
    public void shouldHoldResponseReservationWhileTimeoutCleanerRunsDuringCallback() throws Exception {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicInteger released = new AtomicInteger();
        try (SessionHarness harness = new SessionHarness(new ClientActionHandler())) {
            int msgId = 18;
            PipeInfo pipeInfo = new PipeInfo(initPacket(), null, response -> {
                callbackStarted.countDown();
                try {
                    releaseCallback.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, 1L, 1L);
            harness.session.getPipeMap().put(msgId, pipeInfo);
            MsgPacket response = new MsgPacket(new byte[]{1}, ContentType.BYTE,
                    MsgPacketStatus.RESPONSE_SUCCESS, msgId, ActionType.SERVICE.name());

            Thread dispatchThread = new Thread(() -> harness.session.dispatchIfOpen(response, released::incrementAndGet));
            dispatchThread.start();
            assertTrue(callbackStarted.await(5, TimeUnit.SECONDS));

            ClearIdlMsgPacketRunnable cleaner = new ClearIdlMsgPacketRunnable();
            cleaner.addTask(harness.session.getPipeMap());
            cleaner.run();
            assertEquals(0, released.get());
            assertTrue(harness.session.getPipeMap().containsKey(msgId));

            releaseCallback.countDown();
            dispatchThread.join(5000);
            assertFalse(dispatchThread.isAlive());
            assertEquals(1, released.get());
        } finally {
            releaseCallback.countDown();
        }
    }

    @Test
    public void shouldRejectPluginLogLabelLongerThan128Characters() throws Exception {
        try (SessionHarness harness = new SessionHarness(new ClientActionHandler())) {
            Plugin plugin = new Plugin();
            plugin.setShortName(repeat('x', 129));

            harness.session.setPlugin(plugin);

            assertFalse(harness.session.getSystemAttr().containsKey(IOSession.PLUGIN_LOG_LABEL_ATTR));
            assertEquals("message", harness.session.logPrefix("message"));

            String maximumLengthLabel = repeat('y', 128);
            harness.session.setPluginLogLabel(maximumLengthLabel);
            assertEquals("[" + maximumLengthLabel + "] message", harness.session.logPrefix("message"));
        }
    }

    private static MsgPacket initPacket() {
        return new MsgPacket("{}", ContentType.JSON, MsgPacketStatus.SEND_REQUEST, 1,
                ActionType.INIT_CONNECT.name());
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static class SessionHarness implements AutoCloseable {

        private final ServerSocketChannel server;
        private final SocketChannel sender;
        private final Selector selector;
        private final IOSession session;

        private SessionHarness(ClientActionHandler actionHandler) throws Exception {
            server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            sender = SocketChannel.open(server.getLocalAddress());
            SocketChannel receiver = server.accept();
            selector = Selector.open();
            session = new IOSession(receiver, selector,
                    new SocketCodec(new SocketEncode(), new SocketDecode(Runnable::run)), actionHandler);
        }

        @Override
        public void close() throws Exception {
            session.close();
            sender.close();
            server.close();
            selector.close();
        }
    }
}
