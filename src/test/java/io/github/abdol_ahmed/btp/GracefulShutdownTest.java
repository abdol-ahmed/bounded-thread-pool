package io.github.abdol_ahmed.btp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class GracefulShutdownTest {

  @Test
  void testShutdownDrainsAllTasks() throws InterruptedException {
    final int POOL_SIZE = 2;
    final int TASK_COUNT = 100;
    final AtomicInteger completedTasks = new AtomicInteger(0);
    final CountDownLatch allTasksDone = new CountDownLatch(TASK_COUNT);

    BoundedThreadPool pool = new BoundedThreadPool(POOL_SIZE, TASK_COUNT, RejectionPolicy.BLOCK);

    // Submit tasks that take a short time
    for (int i = 0; i < TASK_COUNT; i++) {
      final int taskId = i;
      pool.submit(
          () -> {
            try {
              // Simulate work
              Thread.sleep(10);
              completedTasks.incrementAndGet();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              allTasksDone.countDown();
            }
          });
    }

    // Give some time for tasks to start
    Thread.sleep(50);

    // Shutdown gracefully
    pool.shutdown();

    // Wait for all tasks to complete (with timeout)
    assertTrue(allTasksDone.await(5, TimeUnit.SECONDS), "Not all tasks completed after shutdown");

    // Verify all tasks were executed
    assertEquals(TASK_COUNT, completedTasks.get(), "Some tasks were lost during shutdown");
  }

  @Test
  void testShutdownNowInterruptsTasks() throws InterruptedException {
    final int POOL_SIZE = 2;
    final int TASK_COUNT = 50;
    final AtomicInteger completedTasks = new AtomicInteger(0);
    final AtomicInteger interruptedTasks = new AtomicInteger(0);

    BoundedThreadPool pool = new BoundedThreadPool(POOL_SIZE, TASK_COUNT, RejectionPolicy.BLOCK);

    // Submit long-running tasks
    for (int i = 0; i < TASK_COUNT; i++) {
      pool.submit(
          () -> {
            try {
              Thread.sleep(1000); // Long sleep
              completedTasks.incrementAndGet();
            } catch (InterruptedException e) {
              interruptedTasks.incrementAndGet();
              Thread.currentThread().interrupt();
            }
          });
    }

    // Give some time for tasks to start
    Thread.sleep(100);

    // Force shutdown
    var unexecuted = pool.shutdownNow();

    // Wait a bit for interruption to take effect
    Thread.sleep(200);

    // Some tasks might have completed, but many should be interrupted
    assertTrue(interruptedTasks.get() > 0, "No tasks were interrupted by shutdownNow");

    // Some tasks should be returned unexecuted
    assertTrue(unexecuted.size() > 0, "No unexecuted tasks returned by shutdownNow");
  }
}
