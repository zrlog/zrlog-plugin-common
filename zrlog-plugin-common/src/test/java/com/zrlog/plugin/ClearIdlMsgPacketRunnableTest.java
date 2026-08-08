package com.zrlog.plugin;

import org.junit.Test;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClearIdlMsgPacketRunnableTest {

    @Test
    public void shouldRemainUsableWhenTasksAreAddedDuringCleanup() throws Exception {
        ClearIdlMsgPacketRunnable cleaner = new ClearIdlMsgPacketRunnable();
        CountDownLatch iterationStarted = new CountDownLatch(1);
        CountDownLatch continueIteration = new CountDownLatch(1);
        cleaner.addTask(new BlockingEntrySetMap(iterationStarted, continueIteration));
        Map<Integer, PipeInfo> expiredMessages = expiredMessages(11);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> cleanup = executor.submit(cleaner);
            assertTrue(iterationStarted.await(5, TimeUnit.SECONDS));
            cleaner.addTask(expiredMessages);
            continueIteration.countDown();
            cleanup.get(5, TimeUnit.SECONDS);

            cleaner.run();

            assertFalse(expiredMessages.containsKey(11));
        } finally {
            continueIteration.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldContinueWithOtherTasksAndFutureRunsAfterOneTaskFails() {
        ClearIdlMsgPacketRunnable cleaner = new ClearIdlMsgPacketRunnable();
        cleaner.addTask(new ThrowOnceEntrySetMap());
        Map<Integer, PipeInfo> firstMessages = expiredMessages(21);
        cleaner.addTask(firstMessages);

        cleaner.run();

        assertFalse(firstMessages.containsKey(21));
        Map<Integer, PipeInfo> laterMessages = expiredMessages(22);
        cleaner.addTask(laterMessages);
        cleaner.run();
        assertFalse(laterMessages.containsKey(22));
    }

    @Test
    public void shouldNotReleaseExpiredResponseWhileCallbackIsActive() {
        ClearIdlMsgPacketRunnable cleaner = new ClearIdlMsgPacketRunnable();
        Map<Integer, PipeInfo> messages = new ConcurrentHashMap<>();
        PipeInfo pipeInfo = new PipeInfo(null, null, response -> {
        }, 1L, 1L);
        AtomicInteger releases = new AtomicInteger();
        assertTrue(pipeInfo.retainResponseForCallback(null, releases::incrementAndGet));
        messages.put(31, pipeInfo);
        cleaner.addTask(messages);

        cleaner.run();

        assertTrue(messages.containsKey(31));
        assertEquals(0, releases.get());

        pipeInfo.finishResponseCallback();
        cleaner.run();
        assertFalse(messages.containsKey(31));
        assertEquals(1, releases.get());
    }

    @Test
    public void shouldNotifyAbortedRequestOnceWhenItExpires() {
        ClearIdlMsgPacketRunnable cleaner = new ClearIdlMsgPacketRunnable();
        Map<Integer, PipeInfo> messages = new ConcurrentHashMap<>();
        AtomicInteger aborts = new AtomicInteger();
        messages.put(41, new PipeInfo(null, null, null, 1L, 1L, aborts::incrementAndGet));
        cleaner.addTask(messages);

        cleaner.run();
        cleaner.run();

        assertFalse(messages.containsKey(41));
        assertEquals(1, aborts.get());
    }

    @Test
    public void shouldRemovePipeMapByIdentityWhenEmptyMapsAreEqual() {
        ClearIdlMsgPacketRunnable cleaner = new ClearIdlMsgPacketRunnable();
        Map<Integer, PipeInfo> first = new ConcurrentHashMap<>();
        Map<Integer, PipeInfo> second = new ConcurrentHashMap<>();
        cleaner.addTask(first);
        cleaner.addTask(second);

        cleaner.removePipeMap(second);

        AtomicInteger aborts = new AtomicInteger();
        first.put(51, new PipeInfo(null, null, null, 1L, 1L, aborts::incrementAndGet));
        cleaner.run();
        cleaner.run();

        assertFalse(first.containsKey(51));
        assertEquals(1, aborts.get());
    }

    private static Map<Integer, PipeInfo> expiredMessages(int msgId) {
        Map<Integer, PipeInfo> messages = new ConcurrentHashMap<>();
        messages.put(msgId, new PipeInfo(null, null, null, 1L, 1L));
        return messages;
    }

    private static class BlockingEntrySetMap extends AbstractMap<Integer, PipeInfo> {

        private final CountDownLatch iterationStarted;
        private final CountDownLatch continueIteration;

        private BlockingEntrySetMap(CountDownLatch iterationStarted, CountDownLatch continueIteration) {
            this.iterationStarted = iterationStarted;
            this.continueIteration = continueIteration;
        }

        @Override
        public Set<Entry<Integer, PipeInfo>> entrySet() {
            iterationStarted.countDown();
            try {
                if (!continueIteration.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to continue iteration");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return Collections.emptySet();
        }
    }

    private static class ThrowOnceEntrySetMap extends AbstractMap<Integer, PipeInfo> {

        private final AtomicBoolean firstCall = new AtomicBoolean(true);

        @Override
        public Set<Entry<Integer, PipeInfo>> entrySet() {
            if (firstCall.compareAndSet(true, false)) {
                throw new IllegalStateException("test failure");
            }
            return Collections.emptySet();
        }
    }
}
