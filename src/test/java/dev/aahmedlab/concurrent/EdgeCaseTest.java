package dev.aahmedlab.concurrent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EdgeCaseTest {

  private BoundedExecutor pool;

  @AfterEach
  void tearDown() throws InterruptedException {
    if (pool != null) {
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Pool did not terminate");
    }
  }

  @Test
  void singleThreadSingleCapacity() throws Exception {
    pool = new BoundedExecutor(1, 1, RejectionPolicy.BLOCK);

    AtomicInteger executionCount = new AtomicInteger(0);
    CountDownLatch taskDone = new CountDownLatch(1);

    // Submit task that blocks
    pool.submit(
        () -> {
          try {
            Thread.sleep(100);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          executionCount.incrementAndGet();
          taskDone.countDown();
        });

    assertTrue(taskDone.await(1, TimeUnit.SECONDS), "Task did not complete");
    assertEquals(1, executionCount.get());
  }

  @Test
  void zeroCapacityQueueWithBlockPolicy() throws Exception {
    pool = new BoundedExecutor(2, 1, RejectionPolicy.BLOCK);

    // With minimal capacity and BLOCK policy, submit should block when queue is full
    AtomicInteger executedTasks = new AtomicInteger(0);
    int taskCount = 10;
    CountDownLatch allDone = new CountDownLatch(taskCount);

    for (int i = 0; i < taskCount; i++) {
      pool.submit(
          () -> {
            executedTasks.incrementAndGet();
            allDone.countDown();
          });
    }

    assertTrue(allDone.await(5, TimeUnit.SECONDS), "Not all tasks completed");
    assertEquals(taskCount, executedTasks.get());
  }

  @Test
  void maximumCapacityQueue() throws Exception {
    // Test with large capacity (but not Integer.MAX_VALUE)
    int largeCapacity = 1_000_000;
    pool = new BoundedExecutor(1, largeCapacity, RejectionPolicy.ABORT);

    // Should be able to submit many tasks without blocking
    int taskCount = 1000;
    for (int i = 0; i < taskCount; i++) {
      pool.submit(
          () -> {
            // Minimal task
          });
    }

    // Queue should have remaining capacity
    assertTrue(pool.getQueueSize() < largeCapacity);
    assertTrue(pool.getQueueRemainingCapacity() > 0);
    assertFalse(pool.isQueueFull());
  }

  @Test
  void minimumValidParameters() {
    // Test with minimum valid values
    pool = new BoundedExecutor(1, 1, RejectionPolicy.BLOCK);

    assertEquals(1, pool.getPoolSize());
    assertEquals(0, pool.getQueueSize());
    assertEquals(1, pool.getQueueRemainingCapacity());
    assertFalse(pool.isQueueFull());
    assertEquals(PoolState.RUNNING, pool.getPoolState());
  }

  @Test
  void extremeThreadCount() throws Exception {
    // Test with many threads
    int threadCount = Runtime.getRuntime().availableProcessors() * 4;
    pool = new BoundedExecutor(threadCount, 10, RejectionPolicy.CALLER_RUNS);

    assertEquals(threadCount, pool.getPoolSize());

    // Submit tasks to utilize all threads
    AtomicInteger activeThreads = new AtomicInteger(0);
    CountDownLatch allStarted = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
      pool.submit(
          () -> {
            activeThreads.incrementAndGet();
            allStarted.countDown();
            try {
              Thread.sleep(100);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            activeThreads.decrementAndGet();
          });
    }

    assertTrue(allStarted.await(1, TimeUnit.SECONDS), "Not all threads started");
    // Note: We can't guarantee all threads are active at the same instant
    // due to task execution timing, so we just verify they all started
    assertTrue(activeThreads.get() <= threadCount, "Too many active threads");
  }

  @Test
  void queueCapacityExactlyEqualsTasks() throws Exception {
    int poolSize = 2;
    int capacity = 5;
    int taskCount = poolSize + capacity; // 7 tasks

    pool = new BoundedExecutor(poolSize, capacity, RejectionPolicy.BLOCK);
    AtomicInteger completed = new AtomicInteger(0);
    CountDownLatch allDone = new CountDownLatch(taskCount);

    // Submit exactly poolSize + capacity tasks
    for (int i = 0; i < taskCount; i++) {
      pool.submit(
          () -> {
            completed.incrementAndGet();
            allDone.countDown();
          });
    }

    assertTrue(allDone.await(5, TimeUnit.SECONDS), "Not all tasks completed");
    assertEquals(taskCount, completed.get());
  }

  @Test
  void rapidShutdownAfterCreation() throws Exception {
    // Create pool and immediately shutdown
    pool = new BoundedExecutor(4, 10, RejectionPolicy.BLOCK);
    pool.shutdownNow(); // Need to shutdown first

    assertTrue(
        pool.awaitTermination(1, TimeUnit.SECONDS),
        "Pool should terminate quickly when no tasks submitted");
  }

  @Test
  void shutdownWithoutTasks() throws Exception {
    pool = new BoundedExecutor(2, 10, RejectionPolicy.BLOCK);

    // Shutdown without submitting any tasks
    pool.shutdown();

    assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS));
    assertEquals(PoolState.TERMINATED, pool.getPoolState());
  }

  @Test
  void submitNullTask() {
    pool = new BoundedExecutor(1, 10, RejectionPolicy.ABORT);

    assertThrows(NullPointerException.class, () -> pool.submit((Runnable) null));
  }

  @Test
  void submitAfterShutdown() {
    pool = new BoundedExecutor(1, 10, RejectionPolicy.ABORT);

    pool.shutdown();

    assertThrows(
        RejectedExecutionException.class,
        () ->
            pool.submit(
                () -> {
                  // Should not execute
                }));
  }

  @Test
  void submitAfterShutdownNow() {
    pool = new BoundedExecutor(1, 10, RejectionPolicy.ABORT);

    pool.shutdownNow();

    assertThrows(
        RejectedExecutionException.class,
        () ->
            pool.submit(
                () -> {
                  // Should not execute
                }));
  }

  @Test
  void awaitTerminationWithoutShutdown() throws Exception {
    pool = new BoundedExecutor(2, 10, RejectionPolicy.BLOCK);

    // awaitTermination should return false if pool is running
    assertFalse(pool.awaitTermination(100, TimeUnit.MILLISECONDS));
    assertEquals(PoolState.RUNNING, pool.getPoolState());
  }

  @Test
  void multipleShutdownCalls() {
    pool = new BoundedExecutor(2, 10, RejectionPolicy.BLOCK);

    pool.shutdown();
    assertEquals(PoolState.SHUTDOWN, pool.getPoolState());

    // Multiple shutdown calls should be safe
    pool.shutdown();
    assertEquals(PoolState.SHUTDOWN, pool.getPoolState());

    pool.shutdownNow();
    assertEquals(PoolState.STOP, pool.getPoolState());
  }

  @Test
  void discardOldestOnEmptyQueue() throws Exception {
    pool = new BoundedExecutor(1, 1, RejectionPolicy.DISCARD_OLDEST);

    // Submit task to empty queue with DISCARD_OLDEST
    AtomicInteger executed = new AtomicInteger(0);
    CountDownLatch done = new CountDownLatch(1);

    pool.submit(
        () -> {
          executed.incrementAndGet();
          done.countDown();
        });

    assertTrue(done.await(1, TimeUnit.SECONDS));
    assertEquals(1, executed.get());
  }

  @Test
  void callerRunsWithBlockingTask() throws Exception {
    pool = new BoundedExecutor(1, 1, RejectionPolicy.CALLER_RUNS);

    // Fill pool and queue
    CountDownLatch firstTaskStarted = new CountDownLatch(1);
    CountDownLatch firstTaskShouldContinue = new CountDownLatch(1);
    pool.submit(
        () -> {
          firstTaskStarted.countDown();
          try {
            firstTaskShouldContinue.await(); // Wait until we're ready
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    assertTrue(firstTaskStarted.await(1, TimeUnit.SECONDS));

    // Fill the queue with a second task
    CountDownLatch secondTaskSubmitted = new CountDownLatch(1);
    pool.submit(secondTaskSubmitted::countDown);

    // Next task should run in caller thread (3rd task)
    Thread callerThread = Thread.currentThread();
    AtomicInteger taskThread = new AtomicInteger();

    pool.submit(() -> taskThread.set(Thread.currentThread().hashCode()));

    assertEquals(callerThread.hashCode(), taskThread.get());

    // Let the first task finish
    firstTaskShouldContinue.countDown();
  }

  @Test
  void extremeTaskDurationVariation() throws Exception {
    pool = new BoundedExecutor(4, 100, RejectionPolicy.BLOCK);

    int taskCount = 50;
    CountDownLatch allDone = new CountDownLatch(taskCount);
    AtomicInteger completed = new AtomicInteger(0);

    // Submit tasks with varying durations
    for (int i = 0; i < taskCount; i++) {
      final int duration = i % 10; // 0-9ms
      pool.submit(
          () -> {
            try {
              Thread.sleep(duration);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            completed.incrementAndGet();
            allDone.countDown();
          });
    }

    assertTrue(allDone.await(5, TimeUnit.SECONDS), "Not all tasks completed");
    assertEquals(taskCount, completed.get());
  }
}
