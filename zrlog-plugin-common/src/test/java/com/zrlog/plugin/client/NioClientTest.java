package com.zrlog.plugin.client;

import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.api.Capability;
import com.zrlog.plugin.api.IPluginService;
import com.zrlog.plugin.api.ScheduledCapability;
import com.zrlog.plugin.api.Service;
import com.zrlog.plugin.data.codec.SocketCodec;
import com.zrlog.plugin.data.codec.SocketDecode;
import com.zrlog.plugin.data.codec.SocketEncode;
import com.zrlog.plugin.data.codec.SocketPacketLimits;
import com.zrlog.plugin.data.codec.SocketPacketMemoryBudget;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.message.PluginCapability;
import org.junit.Test;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
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
    public void shouldRegisterCapabilityWithLegacyServiceAlias() {
        List<PluginCapability> capabilities = new NioClient().readCapabilities(NotificationService.class);

        assertEquals(1, capabilities.size());
        PluginCapability capability = capabilities.get(0);
        assertEquals("notification.email.send", capability.getKey());
        assertEquals("emailService", capability.getServiceName());
        assertEquals("notification_channel", capability.getType());
        assertEquals(Collections.singletonList("notification"), capability.getExposure());
        assertEquals("medium", capability.getRiskLevel());
        assertEquals(Boolean.TRUE, capability.getReadOnly());
        assertEquals(Boolean.TRUE, capability.getRequiresConfirmation());
        assertEquals(Integer.valueOf(45), capability.getTimeoutSeconds());
        assertEquals(Integer.valueOf(2), capability.getConcurrency());
        assertEquals(Boolean.TRUE, capability.getEnabled());
        assertEquals("email", capability.getChannel());
    }

    @Test
    public void shouldRegisterScheduledCapabilityWithSchedulerContract() {
        List<PluginCapability> capabilities = new NioClient().readCapabilities(ReminderService.class);

        assertEquals(1, capabilities.size());
        PluginCapability capability = capabilities.get(0);
        assertEquals("reminder.scanDueTasks", capability.getKey());
        assertEquals("reminder.scanDueTasks", capability.getServiceName());
        assertEquals("scheduled", capability.getType());
        assertEquals(Collections.singletonList("scheduler"), capability.getExposure());
        assertEquals("medium", capability.getRiskLevel());
        assertEquals(Boolean.TRUE, capability.getRequiresConfirmation());
        assertEquals(Integer.valueOf(60), capability.getTimeoutSeconds());
        assertEquals(Integer.valueOf(1), capability.getConcurrency());
        assertEquals("*/5 * * * *", capability.getDefaultCron());
        assertEquals("Asia/Shanghai", capability.getTimezone());
    }

    @Test
    public void shouldRejectMismatchedCapabilityAndScheduleKeys() {
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> new NioClient().readCapabilities(MismatchedScheduledService.class));

        assertTrue(error.getMessage().contains("@Capability key must equal @ScheduledCapability key"));
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

    @Test
    public void shouldOnlyTreatTypedSocketSessionCloseAsNormalExit() {
        assertTrue(NioClient.isSocketSessionClosed(new SocketDecode.SocketSessionClosedException()));
        assertFalse(NioClient.isSocketSessionClosed(new EOFException("FILE payload ended early")));
        assertFalse(NioClient.isSocketSessionClosed(new IOException("connect closed")));
    }

    @Test
    public void shouldClassifyDecoderSocketEofAsNormalExit() throws Exception {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel sender = SocketChannel.open(server.getLocalAddress());
                 SocketChannel receiver = server.accept();
                 Selector selector = Selector.open()) {
                SocketDecode decoder = new SocketDecode(Runnable::run);
                IOSession session = new IOSession(receiver, selector,
                        new SocketCodec(new SocketEncode(), decoder), new ClientActionHandler());
                sender.close();

                Exception closed = assertThrows(
                        SocketDecode.SocketSessionClosedException.class,
                        () -> decoder.doDecode(session));

                assertTrue(NioClient.isSocketSessionClosed(closed));
                session.close();
            }
        }
    }

    @Service("emailService")
    @Capability(
            key = "notification.email.send",
            type = "notification_channel",
            exposure = {"notification"},
            riskLevel = "medium",
            readOnly = true,
            requiresConfirmation = true,
            timeoutSeconds = 45,
            concurrency = 2,
            channel = "email"
    )
    public static class NotificationService implements IPluginService {
        @Override
        public void handle(IOSession session, MsgPacket msgPacket) {
        }
    }

    @Service("reminder.scanDueTasks")
    @Capability(key = "reminder.scanDueTasks", riskLevel = "medium", requiresConfirmation = true)
    @ScheduledCapability(
            key = "reminder.scanDueTasks",
            defaultCron = "*/5 * * * *",
            timezone = "Asia/Shanghai",
            timeoutSeconds = 60
    )
    public static class ReminderService implements IPluginService {
        @Override
        public void handle(IOSession session, MsgPacket msgPacket) {
        }
    }

    @Service("mismatched")
    @Capability(key = "capability.key")
    @ScheduledCapability(key = "schedule.key")
    public static class MismatchedScheduledService implements IPluginService {
        @Override
        public void handle(IOSession session, MsgPacket msgPacket) {
        }
    }
}
