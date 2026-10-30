package com.zrlog.plugin.client;

import com.google.gson.Gson;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.api.IPluginService;
import com.zrlog.plugin.api.Service;
import com.zrlog.plugin.data.codec.ContentType;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.data.codec.SocketCodec;
import com.zrlog.plugin.data.codec.SocketDecode;
import com.zrlog.plugin.data.codec.SocketEncode;
import com.zrlog.plugin.message.CapabilityCancelRequest;
import com.zrlog.plugin.message.CapabilityCancelResult;
import com.zrlog.plugin.message.CapabilityInvokeRequest;
import com.zrlog.plugin.message.CapabilityInvokeResult;
import com.zrlog.plugin.type.ActionType;
import org.junit.Test;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Tests the real SDK dispatcher and response gate, replacing only Socket encoding and OS channels. */
public class CapabilityCompletionTest {
    @Test
    public void staticViewResourceReturnsUtf8AndMissingResourceIsAConfirmedFailure() throws Exception {
        for (boolean missing : new boolean[]{false, true}) {
            try (Harness h = new Harness()) {
                h.view = true; h.viewResource = true;
                h.viewUri = missing ? "/missing.html" : "/fixture-view.html";
                h.invoke(true).get(5, TimeUnit.SECONDS);
                assertEquals(!missing, h.result().isSuccess());
                assertTrue(h.result().isExecutionCompleted()); assertFalse(h.result().isOutcomeUncertain());
                if (missing) assertNull(h.result().getRenderedText());
                else assertEquals("<aside>公开资源</aside>\n", h.result().getRenderedText());
            }
        }
    }

    @Test
    public void staticViewResourceCannotReadOutsideTemplates() throws Exception {
        try (Harness h = new Harness()) {
            h.view = true; h.viewResource = true; h.viewUri = "/../fixture-view.html";
            h.invoke(true).get(5, TimeUnit.SECONDS);
            assertFalse(h.result().isSuccess()); assertTrue(h.result().isExecutionCompleted());
            assertNull(h.result().getRenderedText());
        }
    }

    @Test
    public void earlyResponseWaitsForHandlerCleanup() throws Exception {
        try (Harness h = new Harness()) {
            Future<?> running = h.invoke(true);
            assertTrue(h.state.started.await(5, TimeUnit.SECONDS));
            assertTrue(h.responses.isEmpty());
            assertFalse(running.isDone());
            h.state.release.countDown();
            running.get(5, TimeUnit.SECONDS);
            assertTrue(h.result().isSuccess());
            assertTrue(h.result().isExecutionCompleted());
            assertFalse(h.result().isOutcomeUncertain());
        }
    }

    @Test
    public void failureAfterAnEarlySuccessCannotBeReportedAsSuccess() throws Exception {
        try (Harness h = new Harness()) {
            h.state.failAfterResponse = true;
            Future<?> running = h.invoke(true);
            assertTrue(h.state.started.await(5, TimeUnit.SECONDS));
            h.state.release.countDown();
            running.get(5, TimeUnit.SECONDS);
            assertFalse(h.result().isSuccess());
            assertTrue(h.result().isExecutionCompleted());
            assertTrue(h.result().isOutcomeUncertain());
        }
    }

    @Test
    public void cancellationAcknowledgesTerminationOnlyAfterCleanupReturns() throws Exception {
        try (Harness h = new Harness()) {
            h.state.cooperative = true;
            Future<?> running = h.invoke(true);
            assertTrue(h.state.started.await(5, TimeUnit.SECONDS));
            h.cancel("0000000001.00000001793297580247.94783943-99bf-4773-89bc-5883a479898b");
            assertTrue(h.state.interrupted.await(5, TimeUnit.SECONDS));
            assertTrue("An interrupt request is not termination proof", h.responses.isEmpty());
            assertFalse(running.isDone());
            h.state.cleanup.countDown();
            running.get(5, TimeUnit.SECONDS);
            assertTrue(h.result().isExecutionCompleted());
            assertTrue(h.result().isOutcomeUncertain());
            assertFalse(h.result().isSuccess());
            CapabilityCancelResult cancelled = new Gson().fromJson(h.responses.get(1).getDataStr(), CapabilityCancelResult.class);
            assertTrue(cancelled.isExecutionCompleted());
            assertEquals("0000000001.00000001793297580247.94783943-99bf-4773-89bc-5883a479898b", cancelled.getRequestId());
            assertEquals(ActionType.CAPABILITY_CANCEL.name(), h.responses.get(1).getMethodStr());
        }
    }

    @Test
    public void queuedCancellationPreventsBusinessFromStarting() throws Exception {
        try (Harness h = new Harness()) {
            h.cancel("0000000001.00000001793297580247.94783943-99bf-4773-89bc-5883a479898b");
            assertTrue(h.responses.isEmpty());
            h.invoke(true).get(5, TimeUnit.SECONDS);
            assertEquals(0, h.state.calls.get());
            assertTrue(h.result().isExecutionCompleted());
            assertFalse(h.result().isSuccess());
            assertEquals(2, h.responses.size());
        }
    }

    @Test
    public void cancellationCannotTargetAnotherInvocationWithTheSameMessageId() throws Exception {
        try (Harness h = new Harness()) {
            Future<?> running = h.invoke(true);
            assertTrue(h.state.started.await(5, TimeUnit.SECONDS));
            h.cancel("different-run");
            assertEquals(1, h.state.interrupted.getCount());
            assertFalse(running.isDone());
            h.state.release.countDown();
            running.get(5, TimeUnit.SECONDS);
            assertTrue(h.result().isSuccess());
            assertFalse(h.result().isOutcomeUncertain());
            assertEquals(1, h.responses.size());
        }
    }

    @Test
    public void expiredQueuedRequestDoesNotInvokeThePlugin() throws Exception {
        try (Harness h = new Harness()) {
            h.deadline = System.currentTimeMillis() - 1;
            h.invoke(true).get(5, TimeUnit.SECONDS);
            assertEquals(0, h.state.calls.get());
            assertTrue(h.result().isExecutionCompleted());
            assertFalse(h.result().isSuccess());
        }
    }

    @Test
    public void legacyRequestsKeepTheirExistingResponseBehavior() throws Exception {
        try (Harness h = new Harness()) {
            Future<?> running = h.invoke(false);
            assertTrue(h.state.started.await(5, TimeUnit.SECONDS));
            assertTrue(h.result().isSuccess());
            assertFalse(h.result().isExecutionCompleted());
            assertFalse(running.isDone());
            h.state.release.countDown();
            running.get(5, TimeUnit.SECONDS);
        }
    }

    @Service("fixture")
    public static class FixtureService implements IPluginService {
        @Override public void handle(IOSession session, MsgPacket packet) {
            State state = (State) session.getAttr().get("fixture-state");
            state.calls.incrementAndGet();
            CapabilityInvokeResult result = new CapabilityInvokeResult();
            result.setSuccess(true);
            session.sendJsonMsg(result, packet.getMethodStr(), packet.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
            awaitCleanup(state);
        }
    }

    @Test
    public void renderedViewWaitsForItsHandlerAndPreservesUnicodeContent() throws Exception {
        try (Harness h = new Harness()) {
            h.view = true;
            Future<?> running = h.invoke(true);
            assertTrue(h.state.started.await(5, TimeUnit.SECONDS));
            assertTrue(h.responses.isEmpty());
            h.state.release.countDown(); running.get(5, TimeUnit.SECONDS);
            assertTrue(h.result().isSuccess()); assertTrue(h.result().isExecutionCompleted());
            assertEquals("<aside>插件内容</aside>", h.result().getRenderedText());
        }
    }

    @Test
    public void renderedViewCancellationWaitsForCleanupAndDiscardsEarlyContent() throws Exception {
        try (Harness h = new Harness()) {
            h.view = true; h.state.cooperative = true;
            Future<?> running = h.invoke(true);
            assertTrue(h.state.started.await(5, TimeUnit.SECONDS));
            h.cancel("0000000001.00000001793297580247.94783943-99bf-4773-89bc-5883a479898b");
            assertTrue(h.state.interrupted.await(5, TimeUnit.SECONDS));
            assertTrue(h.responses.isEmpty());
            h.state.cleanup.countDown(); running.get(5, TimeUnit.SECONDS);
            assertFalse(h.result().isSuccess()); assertTrue(h.result().isOutcomeUncertain());
            assertTrue(h.result().isExecutionCompleted());
        }
    }

    @Test
    public void viewFailureAfterResponseCannotConfirmTheEarlyHtml() throws Exception {
        try (Harness h = new Harness()) {
            h.view = true; h.state.failAfterResponse = true;
            Future<?> running = h.invoke(true);
            assertTrue(h.state.started.await(5, TimeUnit.SECONDS));
            h.state.release.countDown(); running.get(5, TimeUnit.SECONDS);
            assertFalse(h.result().isSuccess()); assertTrue(h.result().isExecutionCompleted());
            assertTrue(h.result().isOutcomeUncertain());
        }
    }

    public static class FixtureView {
        private final IOSession session;
        private final MsgPacket packet;
        public FixtureView(IOSession session, MsgPacket packet, com.zrlog.plugin.data.codec.HttpRequestInfo request) {
            this.session = session; this.packet = packet;
        }
        public void index() {
            State state = (State) session.getAttr().get("fixture-state");
            state.calls.incrementAndGet();
            session.sendMsg(ContentType.HTML, "<aside>插件内容</aside>", packet.getMethodStr(), packet.getMsgId(),
                    MsgPacketStatus.RESPONSE_SUCCESS, null);
            awaitCleanup(state);
        }
    }

    private static void awaitCleanup(State state) {
            state.started.countDown();
            try {
                if (!state.release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture not released");
            } catch (InterruptedException interrupted) {
                state.interrupted.countDown();
                if (!state.cooperative) throw new IllegalStateException(interrupted);
                try {
                    if (!state.cleanup.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Cleanup not released");
                } catch (InterruptedException repeated) { throw new IllegalStateException(repeated); }
            }
            if (state.failAfterResponse) throw new IllegalStateException("Failure after partial work");
    }

    private static final class State {
        private final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1),
                cleanup = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private boolean failAfterResponse, cooperative;
    }

    private static final class Harness implements AutoCloseable {
        private final State state = new State();
        private final List<MsgPacket> responses = new CopyOnWriteArrayList<>();
        private final ClientActionHandler handler = new ClientActionHandler();
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final IOSession session;
        private long deadline = System.currentTimeMillis() + 30000;
        private boolean view;
        private boolean viewResource;
        private String viewUri = "/index.action";
        private Harness() {
            SocketEncode encode = new SocketEncode() {
                @Override public void doEncode(IOSession ignored, MsgPacket packet) { responses.add(packet); }
            };
            session = new IOSession(new MemoryChannel(), new MemorySelector(),
                    new SocketCodec(encode, new SocketDecode(Runnable::run)), handler);
            session.getAttr().put("_pluginServices", Collections.singletonList(FixtureService.class));
            session.getAttr().put("fixture-state", state);
            session.getAttr().put("_actionClassList", Collections.singletonList(FixtureView.class));
        }
        private Future<?> invoke(boolean completion) {
            CapabilityInvokeRequest request = new CapabilityInvokeRequest();
            request.setCapabilityKey("fixture"); request.setRequestId("0000000001.00000001793297580247.94783943-99bf-4773-89bc-5883a479898b");
            request.setRequireCompletion(completion); request.setDeadlineEpochMillis(deadline);
            if (view) {
                request.setCapabilityKey(CapabilityInvokeRequest.HOST_VIEW_CAPABILITY);
                com.zrlog.plugin.data.codec.HttpRequestInfo info = new com.zrlog.plugin.data.codec.HttpRequestInfo();
                info.setUri(viewUri); info.setHttpMethod(com.zrlog.plugin.common.type.HttpMethod.GET);
                request.setViewRequest(info);
                request.setViewResource(viewResource);
            }
            MsgPacket packet = new MsgPacket(request, ContentType.JSON, MsgPacketStatus.SEND_REQUEST, 1,
                    ActionType.CAPABILITY_INVOKE.name());
            return worker.submit(() -> session.dispatchIfOpen(packet, () -> { }));
        }
        private void cancel(String requestId) {
            CapabilityCancelRequest request = new CapabilityCancelRequest();
            request.setInvocationMessageId(1); request.setRequestId(requestId);
            session.dispatchIfOpen(new MsgPacket(request, ContentType.JSON, MsgPacketStatus.SEND_REQUEST, 2,
                    ActionType.CAPABILITY_CANCEL.name()), () -> { });
        }
        private CapabilityInvokeResult result() {
            assertFalse(responses.isEmpty());
            return new Gson().fromJson(responses.get(0).getDataStr(), CapabilityInvokeResult.class);
        }
        @Override public void close() throws Exception {
            state.release.countDown(); state.cleanup.countDown();
            worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            session.close();
        }
    }

    private static final class MemoryChannel extends SocketChannel {
        private MemoryChannel() { super(SelectorProvider.provider()); }
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

    private static final class MemorySelector extends AbstractSelector {
        private MemorySelector() { super(SelectorProvider.provider()); }
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
