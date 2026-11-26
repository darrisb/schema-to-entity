package com.example.dynamicmeta.drift;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DR-004: the periodic drift check. It runs only when an interval is
 * configured, on one daemon thread, survives a check that fails, and stops when
 * the application shuts down. The service is mocked, so nothing here touches a
 * database or a snapshot folder.
 */
class DriftCheckSchedulerTests {

    /** Short enough for the test to see several runs, long enough to be stable. */
    private static final Duration FAST = Duration.ofMillis(30);

    @Test
    void anUnsetIntervalSchedulesNothing() {
        DriftService service = mock(DriftService.class);
        DriftCheckScheduler scheduler = new DriftCheckScheduler(service, null);

        scheduler.start();

        assertThat(scheduler.isRunning()).isFalse();
        verifyNoInteractions(service);
    }

    @Test
    void aNonPositiveIntervalIsTreatedAsUnset() {
        DriftService service = mock(DriftService.class);
        DriftCheckScheduler zero = new DriftCheckScheduler(service, Duration.ZERO);
        DriftCheckScheduler negative = new DriftCheckScheduler(service, Duration.ofSeconds(-1));

        zero.start();
        negative.start();

        assertThat(zero.isRunning()).isFalse();
        assertThat(negative.isRunning()).isFalse();
        verifyNoInteractions(service);
    }

    @Test
    void checksRunOnOneDaemonThreadUntilStopped() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Set<String> threadNames = ConcurrentHashMap.newKeySet();
        Set<Boolean> daemonThreads = ConcurrentHashMap.newKeySet();
        CountDownLatch firstRun = new CountDownLatch(1);
        DriftService service = mock(DriftService.class);
        when(service.checkAll()).thenAnswer(invocation -> {
            threadNames.add(Thread.currentThread().getName());
            daemonThreads.add(Thread.currentThread().isDaemon());
            runs.incrementAndGet();
            firstRun.countDown();
            return List.of();
        });
        DriftCheckScheduler scheduler = new DriftCheckScheduler(service, FAST);

        // Starting twice must not add a second watcher.
        scheduler.start();
        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();
        assertThat(firstRun.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(150);
        scheduler.stop();
        scheduler.stop();
        assertThat(scheduler.isRunning()).isFalse();

        assertThat(runs).hasValueGreaterThanOrEqualTo(2);
        assertThat(threadNames).containsExactly("schema-drift-check");
        assertThat(daemonThreads).containsExactly(true);
        verify(service, atLeast(2)).checkAll();

        // Stopped means stopped: no check is scheduled again.
        int settled = runs.get();
        Thread.sleep(150);
        assertThat(runs).hasValue(settled);
    }

    @Test
    void stoppingBeforeStartingIsHarmless() {
        DriftService service = mock(DriftService.class);
        DriftCheckScheduler scheduler = new DriftCheckScheduler(service, FAST);

        scheduler.stop();

        assertThat(scheduler.isRunning()).isFalse();
        verifyNoInteractions(service);
    }

    @Test
    void aCheckThatThrowsDoesNotCancelLaterChecks() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch secondRun = new CountDownLatch(1);
        DriftService service = mock(DriftService.class);
        when(service.checkAll()).thenAnswer(invocation -> {
            if (runs.incrementAndGet() == 1) {
                // A baseline file that cannot be read fails this run; the
                // schedule must not die with it.
                throw new IllegalStateException("snapshot file is not a readable baseline");
            }
            secondRun.countDown();
            return List.of();
        });
        DriftCheckScheduler scheduler = new DriftCheckScheduler(service, FAST);

        scheduler.start();
        try {
            assertThat(secondRun.await(5, TimeUnit.SECONDS))
                    .as("later checks keep running after one failed")
                    .isTrue();
        } finally {
            scheduler.stop();
        }
        assertThat(runs).hasValueGreaterThanOrEqualTo(2);
        assertThat(Mockito.mockingDetails(service).getInvocations()).isNotEmpty();
    }
}
