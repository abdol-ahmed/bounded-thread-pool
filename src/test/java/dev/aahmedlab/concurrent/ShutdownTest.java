package dev.aahmedlab.concurrent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ShutdownTest {

  private BoundedExecutor pool;

  @AfterEach
  void tearDown() throws InterruptedException {
    if (pool != null) {
      BoundedExecutorTestSupport.shutdownAndAwait(pool, 2, TimeUnit.SECONDS);
    }
  }

  @Test
  void shutdownRejectsNewSubmissions() {
    pool = BoundedExecutorTestSupport.createBlockingPool(2, 5);

    pool.shutdown();

    assertThrows(
        RejectedExecutionException.class,
        () -> {
          pool.submit(() -> {});
        });
  }

  @Test
  void shutdownCompletesQueuedTasks() throws Exception {
    pool = BoundedExecutorTestSupport.createBlockingPool(1, 5);
    var latches = BoundedExecutorTestSupport.createTaskLatches();
    AtomicInteger completedCount = new AtomicInteger(0);

    // Submit blocking task
    pool.submit(
        BoundedExecutorTestSupport.createBlockingTask(latches, completedCount::incrementAndGet));

    assertTrue(latches.started.await(1, TimeUnit.SECONDS));

    // Submit tasks to queue
    for (int i = 0; i < 3; i++) {
      pool.submit(completedCount::incrementAndGet);
    }

    pool.shutdown();

    // Release the blocking task
    latches.finish.countDown();

    // With graceful shutdown, all queued tasks should complete
    // Wait for all tasks to finish
    Thread.sleep(500);

    // All tasks should complete with graceful shutdown
    assertEquals(4, completedCount.get(), "All tasks should complete with graceful shutdown");
  }

  @Test
  void shutdownNowReturnsQueuedTasks() throws Exception {
    pool = BoundedExecutorTestSupport.createBlockingPool(1, 5);
    var latches = BoundedExecutorTestSupport.createTaskLatches();

    // Submit blocking task
    pool.submit(BoundedExecutorTestSupport.createBlockingTask(latches, null));
    assertTrue(latches.started.await(1, TimeUnit.SECONDS));

    // Submit tasks to queue
    for (int i = 0; i < 3; i++) {
      final int taskId = i;
      pool.submit(
          () -> {
            // This should not execute
          });
    }

    // Shutdown now should return queued tasks
    List<Runnable> remaining = pool.shutdownNow();
    assertEquals(3, remaining.size());

    latches.finish.countDown();
  }

  @Test
  void awaitTerminationWaitsForAllWorkers() throws Exception {
    pool = BoundedExecutorTestSupport.createBlockingPool(2, 5);
    var latches1 = BoundedExecutorTestSupport.createTaskLatches();
    var latches2 = BoundedExecutorTestSupport.createTaskLatches();

    // Submit blocking tasks to both workers
    pool.submit(BoundedExecutorTestSupport.createBlockingTask(latches1, null));
    pool.submit(BoundedExecutorTestSupport.createBlockingTask(latches2, null));

    assertTrue(latches1.started.await(1, TimeUnit.SECONDS));
    assertTrue(latches2.started.await(1, TimeUnit.SECONDS));

    pool.shutdown();

    // With graceful shutdown, workers should finish all queued tasks
    // So awaitTermination should wait for tasks to complete
    // But since tasks are blocked, it should timeout
    assertFalse(pool.awaitTermination(100, TimeUnit.MILLISECONDS));

    // Release tasks
    latches1.finish.countDown();
    latches2.finish.countDown();

    // Now awaitTermination should succeed
    assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS));
  }

  @Test
  void shutdownInterruptsWorkers() throws Exception {
    pool = BoundedExecutorTestSupport.createBlockingPool(1, 5);
    AtomicInteger interruptedCount = new AtomicInteger(0);

    // Submit task that checks for interruption
    pool.submit(
        () -> {
          try {
            Thread.sleep(500); // Reduced from 5000ms
          } catch (InterruptedException e) {
            interruptedCount.incrementAndGet();
            Thread.currentThread().interrupt();
          }
        });

    Thread.sleep(100); // Let task start

    pool.shutdown();

    // With graceful shutdown, worker should NOT be interrupted
    // It should continue running until sleep finishes
    Thread.sleep(600); // Let the task finish naturally

    // Task should have completed without interruption
    assertEquals(
        0, interruptedCount.get(), "Worker should not be interrupted by graceful shutdown");

    // Pool should terminate after task completes
    assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS));
  }

  @Test
  void introspectionMethodsAreCallable() throws Exception {
    pool = BoundedExecutorTestSupport.createBlockingPool(2, 5);

    assertEquals(PoolState.RUNNING, pool.getPoolState());
    assertEquals(2, pool.getPoolSize());
    assertEquals(0, pool.getQueueSize());
    assertEquals(5, pool.getQueueRemainingCapacity());
    assertFalse(pool.isQueueFull());
    assertFalse(pool.isTerminated());

    pool.shutdown();
    assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS));
    assertTrue(pool.isTerminated());
  }
}
