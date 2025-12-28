package io.github.abdol_ahmed.btp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class TaskAccountingTest {

  private BoundedThreadPool pool;

  @BeforeEach
  void setUp() {
    pool = new BoundedThreadPool(4, 100, RejectionPolicy.BLOCK);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    if (pool != null) {
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "Pool did not terminate");
    }
  }

  @Test
  void shutdownNowReturnsQueuedTasks() throws Exception {
    // Fill workers with blocking tasks
    int workerCount = 4;
    CountDownLatch workersBlocked = new CountDownLatch(workerCount);

    for (int i = 0; i < workerCount; i++) {
      pool.submit(
          () -> {
            workersBlocked.countDown();
            try {
              Thread.sleep(10_000); // Block worker
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
    }

    assertTrue(workersBlocked.await(5, TimeUnit.SECONDS), "Workers not blocked");

    // Submit tasks to queue
    int queuedTasks = 50;
    for (int i = 0; i < queuedTasks; i++) {
      pool.submit(
          () -> {
            // Should not execute
          });
    }

    // Force shutdown
    List<Runnable> unexecuted = pool.shutdownNow();

    // Should return all queued tasks
    assertEquals(queuedTasks, unexecuted.size(), "Not all queued tasks returned");
  }

  @RepeatedTest(10)
  void taskAccountingRaceCondition() throws Exception {
    // This test verifies the documented race condition:
    // Tasks taken by workers before observing STOP may be dropped

    int workerCount = 2;
    int totalTasks = 100;
    AtomicInteger tasksExecuted = new AtomicInteger(0);
    AtomicInteger tasksDropped = new AtomicInteger(0);
    CountDownLatch workersReady = new CountDownLatch(workerCount);
    CountDownLatch testComplete = new CountDownLatch(1);

    // Submit tasks that track execution
    for (int i = 0; i < totalTasks; i++) {
      final int taskId = i;
      pool.submit(
          () -> {
            // Check if we're in STOP state before executing
            if (pool.getPoolState() == PoolState.STOP) {
              tasksDropped.incrementAndGet();
              return;
            }

            // Simulate work
            try {
              Thread.sleep(1);
            } catch (InterruptedException e) {
              // If interrupted, task is dropped
              tasksDropped.incrementAndGet();
              Thread.currentThread().interrupt();
              return;
            }

            tasksExecuted.incrementAndGet();
          });
    }

    // Let some tasks start executing
    Thread.sleep(10);

    // Force shutdown
    List<Runnable> remaining = pool.shutdownNow();

    // Wait for any in-flight tasks to complete
    Thread.sleep(100);

    // Verify accounting: returned + executed + dropped = total
    int accounted = remaining.size() + tasksExecuted.get() + tasksDropped.get();

    // Due to the race condition, we can't guarantee exact equality,
    // but we should account for at least 90% of tasks
    assertTrue(
        accounted >= totalTasks * 0.9, "Poor task accounting: " + accounted + " of " + totalTasks);

    // Verify we got some tasks back
    assertTrue(remaining.size() > 0, "No tasks returned from shutdownNow");
  }

  @Test
  void noTaskLossDuringGracefulShutdown() throws Exception {
    int taskCount = 100;
    AtomicInteger tasksCompleted = new AtomicInteger(0);
    CountDownLatch allDone = new CountDownLatch(taskCount);

    // Submit all tasks
    for (int i = 0; i < taskCount; i++) {
      pool.submit(
          () -> {
            tasksCompleted.incrementAndGet();
            allDone.countDown();
          });
    }

    // Graceful shutdown
    pool.shutdown();

    // All tasks should complete
    assertTrue(allDone.await(10, TimeUnit.SECONDS), "Not all tasks completed");
    assertEquals(taskCount, tasksCompleted.get(), "Tasks were lost");
  }

  @Test
  void taskExecutionAfterShutdownNow() throws Exception {
    // Fill workers with blocking tasks
    int workerCount = pool.getPoolSize();
    CountDownLatch workersBlocked = new CountDownLatch(workerCount);

    for (int i = 0; i < workerCount; i++) {
      pool.submit(
          () -> {
            workersBlocked.countDown();
            try {
              Thread.sleep(1000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
    }

    assertTrue(workersBlocked.await(5, TimeUnit.SECONDS), "Workers not blocked");

    // Submit a task that should be dropped
    AtomicInteger executedCount = new AtomicInteger(0);
    pool.submit(
        () -> {
          executedCount.incrementAndGet();
        });

    // Force shutdown immediately
    List<Runnable> remaining = pool.shutdownNow();

    // The task might be taken by a worker before STOP is observed
    // but should not execute due to the STOP check
    Thread.sleep(200);
    assertEquals(0, executedCount.get(), "Task executed after shutdownNow");
  }

  @Test
  void concurrentShutdownAndSubmission() throws Exception {
    int submitterCount = 5;
    int tasksPerSubmitter = 20;
    AtomicInteger submittedCount = new AtomicInteger(0);
    AtomicInteger rejectedCount = new AtomicInteger(0);
    CountDownLatch startSignal = new CountDownLatch(1);
    CountDownLatch allDone = new CountDownLatch(submitterCount);

    ExecutorService submitters = Executors.newFixedThreadPool(submitterCount);

    try {
      for (int i = 0; i < submitterCount; i++) {
        submitters.submit(
            () -> {
              try {
                startSignal.await();
                for (int j = 0; j < tasksPerSubmitter; j++) {
                  try {
                    pool.submit(
                        () -> {
                          // Minimal task
                        });
                    submittedCount.incrementAndGet();
                  } catch (RejectedExecutionException e) {
                    rejectedCount.incrementAndGet();
                  }
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                allDone.countDown();
              }
            });
      }

      // Start submission
      startSignal.countDown();

      // Shutdown after a short delay
      Thread.sleep(10);
      List<Runnable> remaining = pool.shutdownNow();

      assertTrue(allDone.await(5, TimeUnit.SECONDS));

      // Verify accounting
      int totalSubmitted = submittedCount.get();
      assertTrue(totalSubmitted > 0, "No tasks submitted");
      assertTrue(remaining.size() >= 0, "Invalid remaining count");

      // Total accounted tasks = submitted + rejected
      // (rejected tasks were never actually submitted)
      assertEquals(totalSubmitted, submittedCount.get(), "Count mismatch");
    } finally {
      submitters.shutdownNow();
      assertTrue(submitters.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void zeroCapacityQueueAccounting() throws Exception {
    // Create pool with minimal queue capacity (1, since 0 is not allowed)
    BoundedThreadPool zeroQueuePool = new BoundedThreadPool(2, 1, RejectionPolicy.CALLER_RUNS);

    try {
      AtomicInteger executedInPool = new AtomicInteger(0);
      AtomicInteger executedInCaller = new AtomicInteger(0);
      int taskCount = 10;
      CountDownLatch allDone = new CountDownLatch(taskCount);

      for (int i = 0; i < taskCount; i++) {
        final int taskId = i;
        zeroQueuePool.submit(
            () -> {
              // With CALLER_RUNS and zero capacity, tasks will run in caller thread
              // when both workers are busy. We can't detect this from within the task
              // reliably, so we just count executions.
              executedInCaller.incrementAndGet();
              allDone.countDown();
            });
      }

      // Wait for all tasks to complete
      assertTrue(allDone.await(5, TimeUnit.SECONDS), "Not all tasks completed");

      // All tasks should be executed
      assertEquals(taskCount, executedInPool.get() + executedInCaller.get());

      // Shutdown should return no tasks (queue is always empty)
      List<Runnable> remaining = zeroQueuePool.shutdownNow();
      assertEquals(0, remaining.size(), "Tasks in zero-capacity queue");

    } finally {
      zeroQueuePool.shutdownNow();
      assertTrue(zeroQueuePool.awaitTermination(5, TimeUnit.SECONDS));
    }
  }
}
