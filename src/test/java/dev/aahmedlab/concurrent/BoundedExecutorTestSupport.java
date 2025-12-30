package dev.aahmedlab.concurrent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Utility class providing common factory methods and test patterns for BoundedExecutor tests. */
public class BoundedExecutorTestSupport {

  // Factory methods for different pool configurations

  public static BoundedExecutor createBlockingPool(int poolSize, int capacity) {
    return new BoundedExecutor(poolSize, capacity, RejectionPolicy.BLOCK);
  }

  public static BoundedExecutor createAbortPool(int poolSize, int capacity) {
    return new BoundedExecutor(poolSize, capacity, RejectionPolicy.ABORT);
  }

  public static BoundedExecutor createDiscardPool(int poolSize, int capacity) {
    return new BoundedExecutor(poolSize, capacity, RejectionPolicy.DISCARD);
  }

  public static BoundedExecutor createDiscardOldestPool(int poolSize, int capacity) {
    return new BoundedExecutor(poolSize, capacity, RejectionPolicy.DISCARD_OLDEST);
  }

  public static BoundedExecutor createCallerRunsPool(int poolSize, int capacity) {
    return new BoundedExecutor(poolSize, capacity, RejectionPolicy.CALLER_RUNS);
  }

  // Common test patterns

  /**
   * Creates a pair of latches for controlling task execution. The started latch is counted down
   * when the task begins. The finish latch blocks the task until counted down.
   */
  public static TaskLatches createTaskLatches() {
    return new TaskLatches(new CountDownLatch(1), new CountDownLatch(1));
  }

  /**
   * Creates a task that waits on finishLatch before completing, and counts down startedLatch when
   * it begins.
   */
  public static Runnable createBlockingTask(TaskLatches latches, Runnable onCompletion) {
    return () -> {
      latches.started.countDown();
      try {
        latches.finish.await();
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
      if (onCompletion != null) {
        onCompletion.run();
      }
    };
  }

  /**
   * Ensures pool shutdown and waits for termination. Returns true if all workers terminated within
   * timeout.
   */
  public static boolean shutdownAndAwait(BoundedExecutor pool, long timeout, TimeUnit unit)
      throws InterruptedException {
    pool.shutdown();
    return pool.awaitTermination(timeout, unit);
  }

  /** Helper class for managing task execution latches. */
  public static class TaskLatches {
    public final CountDownLatch started;
    public final CountDownLatch finish;

    public TaskLatches(CountDownLatch started, CountDownLatch finish) {
      this.started = started;
      this.finish = finish;
    }
  }
}
