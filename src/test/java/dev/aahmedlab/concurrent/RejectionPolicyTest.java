package dev.aahmedlab.concurrent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RejectionPolicyTest {

  private BoundedExecutor pool;

  @AfterEach
  void tearDown() throws InterruptedException {
    if (pool != null) {
      BoundedExecutorTestSupport.shutdownAndAwait(pool, 2, TimeUnit.SECONDS);
    }
  }

  @Test
  void abortPolicyRejectsWhenQueueFull() throws Exception {
    pool = BoundedExecutorTestSupport.createAbortPool(1, 1);
    var latches = BoundedExecutorTestSupport.createTaskLatches();

    // Submit first task that blocks
    pool.submit(BoundedExecutorTestSupport.createBlockingTask(latches, null));

    // Wait for first task to start
    assertTrue(latches.started.await(1, TimeUnit.SECONDS));

    // Submit second task, should fill queue
    pool.submit(() -> {});

    // Third task should be rejected
    assertThrows(RejectedExecutionException.class, () -> pool.submit(() -> {}));

    latches.finish.countDown();
  }

  @Test
  void discardPolicySilentlyDropsWhenQueueFull() throws Exception {
    pool = BoundedExecutorTestSupport.createDiscardPool(1, 1);
    var latches = BoundedExecutorTestSupport.createTaskLatches();

    CountDownLatch acceptedRan = new CountDownLatch(1);
    CountDownLatch discardedRan = new CountDownLatch(1);

    // Submit blocking task
    pool.submit(BoundedExecutorTestSupport.createBlockingTask(latches, null));
    assertTrue(latches.started.await(1, TimeUnit.SECONDS));

    // This should be accepted into queue
    pool.submit(acceptedRan::countDown);

    // This should be silently discarded
    pool.submit(discardedRan::countDown);

    latches.finish.countDown();

    assertTrue(acceptedRan.await(2, TimeUnit.SECONDS), "accepted task didn't run");
    assertFalse(discardedRan.await(200, TimeUnit.MILLISECONDS), "discarded task ran");
  }

  @Test
  void discardOldestPolicyDropsOldestWhenQueueFull() throws Exception {
    pool = BoundedExecutorTestSupport.createDiscardOldestPool(1, 2);
    var latches = BoundedExecutorTestSupport.createTaskLatches();

    CountDownLatch oldestRan = new CountDownLatch(1);
    CountDownLatch newerRan = new CountDownLatch(1);
    CountDownLatch latestRan = new CountDownLatch(1);

    // Submit blocking task
    pool.submit(BoundedExecutorTestSupport.createBlockingTask(latches, null));
    assertTrue(latches.started.await(1, TimeUnit.SECONDS));

    // Submit tasks to fill queue
    pool.submit(oldestRan::countDown);
    pool.submit(newerRan::countDown);

    // This should discard the oldest
    pool.submit(latestRan::countDown);

    latches.finish.countDown();

    assertTrue(newerRan.await(2, TimeUnit.SECONDS), "newer task didn't run");
    assertTrue(latestRan.await(2, TimeUnit.SECONDS), "latest task didn't run");
    assertFalse(
        oldestRan.await(200, TimeUnit.MILLISECONDS),
        "oldest task ran but should have been dropped");
  }

  @Test
  void callerRunsPolicyExecutesInCallerThread() throws Exception {
    pool = BoundedExecutorTestSupport.createCallerRunsPool(1, 1);
    var latches = BoundedExecutorTestSupport.createTaskLatches();

    CountDownLatch workerTaskRan = new CountDownLatch(1);
    CountDownLatch callerTaskRan = new CountDownLatch(1);
    Thread testThread = Thread.currentThread();

    // Submit blocking task
    pool.submit(BoundedExecutorTestSupport.createBlockingTask(latches, null));
    assertTrue(latches.started.await(1, TimeUnit.SECONDS));

    // Submit task that should run in worker
    pool.submit(
        () -> {
          assertNotEquals(testThread, Thread.currentThread());
          workerTaskRan.countDown();
        });

    // This task should run in caller thread
    pool.submit(
        () -> {
          assertEquals(testThread, Thread.currentThread());
          callerTaskRan.countDown();
        });

    // Caller-runs should execute immediately
    assertTrue(callerTaskRan.await(200, TimeUnit.MILLISECONDS), "caller task didn't run on caller");

    latches.finish.countDown();
    assertTrue(workerTaskRan.await(2, TimeUnit.SECONDS), "worker task didn't run");
  }
}
