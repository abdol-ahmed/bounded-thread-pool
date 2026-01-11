package dev.aahmedlab.concurrent;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A bounded executor implementation with a bounded queue and configurable rejection policies.
 *
 * <p>This bounded executor provides bounded capacity to prevent resource exhaustion and offers
 * multiple strategies for handling task submission when the executor is at capacity. It supports
 * graceful shutdown, task accounting, and monitoring capabilities.
 *
 * @author Abdullah Ahmed
 * @since 1.0.0
 */
public final class BoundedExecutor {
  private static final Logger logger = LoggerFactory.getLogger(BoundedExecutor.class);

  private final BoundedBlockingQueue<Runnable> blockingQueue;
  private final RejectionPolicy rejectionPolicy;
  private final List<Thread> workerThreads;
  private final ReentrantLock poolLock = new ReentrantLock();
  private volatile PoolState poolState;

  /**
   * Creates a bounded executor with the specified parameters.
   *
   * @param poolSize the number of worker threads
   * @param capacity the maximum number of tasks that can be queued
   * @param rejectionPolicy the policy to use when the queue is full
   * @throws IllegalArgumentException if poolSize or capacity is less than or equal to 0
   * @since 1.0.0
   */
  public BoundedExecutor(int poolSize, int capacity, RejectionPolicy rejectionPolicy) {
    if (poolSize <= 0) throw new IllegalArgumentException("poolSize must be > 0");
    if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
    this.blockingQueue = new BoundedBlockingQueue<>(capacity);
    this.rejectionPolicy = rejectionPolicy;
    this.poolState = PoolState.RUNNING; // Always start as RUNNING
    workerThreads = new ArrayList<>(poolSize);

    for (int i = 0; i < poolSize; i++) {
      Thread t = new Thread(new Worker(), "btp-worker-" + i);
      t.setDaemon(true); // Make daemon to prevent JVM hangs
      t.start();
      workerThreads.add(t);
    }
  }

  /**
   * Creates a bounded executor with a BLOCK rejection policy. This is the most common configuration
   * for bounded executors.
   *
   * @param poolSize the number of worker threads
   * @param capacity the maximum number of tasks that can be queued
   * @return a new BoundedExecutor instance
   * @throws IllegalArgumentException if poolSize or capacity is less than or equal to 0
   * @since 1.0.0
   */
  public static BoundedExecutor create(int poolSize, int capacity) {
    return new BoundedExecutor(poolSize, capacity, RejectionPolicy.BLOCK);
  }

  /**
   * Creates a fixed-size executor with a bounded queue. The queue capacity is twice the pool size,
   * providing a good balance between throughput and memory usage.
   *
   * @param poolSize the number of worker threads
   * @return a new BoundedExecutor instance
   * @throws IllegalArgumentException if poolSize is less than or equal to 0
   * @since 1.0.0
   */
  public static BoundedExecutor createFixed(int poolSize) {
    return new BoundedExecutor(poolSize, poolSize * 2, RejectionPolicy.CALLER_RUNS);
  }

  /**
   * Creates an executor optimized for CPU-bound tasks. Uses the number of available processors as
   * the pool size with a small bounded queue.
   *
   * @return a new BoundedExecutor instance optimized for CPU-bound tasks
   * @since 1.0.0
   */
  public static BoundedExecutor createCpuBound() {
    int processors = Runtime.getRuntime().availableProcessors();
    return new BoundedExecutor(processors, processors, RejectionPolicy.CALLER_RUNS);
  }

  /**
   * Creates an executor optimized for I/O-bound tasks. Uses twice the number of available
   * processors as the pool size with a larger bounded queue.
   *
   * @return a new BoundedExecutor instance optimized for I/O-bound tasks
   * @since 1.0.0
   */
  public static BoundedExecutor createIoBound() {
    int processors = Runtime.getRuntime().availableProcessors();
    return new BoundedExecutor(processors * 2, processors * 10, RejectionPolicy.BLOCK);
  }

  /**
   * Submits a task for execution in the bounded executor.
   *
   * @param task the task to execute
   * @throws NullPointerException if task is null
   * @throws RejectedExecutionException if the executor is shut down or the queue is full (for ABORT
   *     policy)
   * @throws InterruptedException if the thread is interrupted while waiting (for BLOCK policy)
   * @since 1.0.0
   */
  public void submit(Runnable task) throws InterruptedException {
    if (task == null) throw new NullPointerException("task");

    if (rejectionPolicy == RejectionPolicy.BLOCK) {
      if (poolState != PoolState.RUNNING) {
        throw new RejectedExecutionException("Executor is shut down");
      }
      try {
        blockingQueue.put(task);

      } catch (IllegalStateException stateException) {
        throw new RejectedExecutionException("Executor is shut down", stateException);
      } catch (InterruptedException interruptedExc) {
        throw interruptedExc;
      }
      return;
    }

    boolean shouldRunInCaller = false;

    poolLock.lock();
    try {
      if (poolState != PoolState.RUNNING) {
        throw new RejectedExecutionException("Executor is shut down");
      }

      switch (rejectionPolicy) {
        case ABORT -> {
          if (!blockingQueue.tryPut(task)) {
            throw new RejectedExecutionException("Queue is full");
          }
        }
        case DISCARD -> blockingQueue.tryPut(task);
        case DISCARD_OLDEST -> {
          if (!blockingQueue.tryPut(task)) {
            blockingQueue.discardOldestAndPut(task);
          }
        }
        case CALLER_RUNS -> {
          if (!blockingQueue.tryPut(task)) {
            shouldRunInCaller = true;
          }
        }
        case BLOCK -> throw new AssertionError("BLOCK policy should be handled before switch");
        default -> throw new AssertionError("Unhandled rejection policy: " + rejectionPolicy);
      }
    } finally {
      poolLock.unlock();
    }

    // Run rejected task in caller thread for CALLER_RUNS policy
    if (shouldRunInCaller) {
      task.run();
    }
  }

  public <T> Future<T> submit(Callable<T> task) throws InterruptedException {
    if (task == null) throw new NullPointerException("task");

    // Create the future
    CompletableFuture<T> future = new CompletableFuture<>(); // ← create it NOW

    // Wrap the task to capture its result
    Runnable wrapper =
        () -> {
          try {
            T result = task.call();
            future.setResult(result); // ← worker stores result in the future
          } catch (Exception e) {
            future.setException(e); // ← or store the exception
          }
        };

    // Submit the wrapper to the queue (reuse your existing submit(Runnable))
    submit(wrapper);

    return future; // ← return immediately to caller
  }

  /**
   * Initiates a graceful shutdown of the bounded executor.
   *
   * <p>This method:
   *
   * <ul>
   *   <li>Sets the pool state to SHUTDOWN
   *   <li>Closes the task queue (new submissions will be rejected)
   *   <li>Allows currently executing tasks to complete
   *   <li>Processes all tasks already in the queue
   * </ul>
   *
   * @since 1.0.0
   */
  public void shutdown() {
    poolLock.lock();
    try {
      poolState = PoolState.SHUTDOWN;
      blockingQueue.close();
    } finally {
      poolLock.unlock();
    }
  }

  /**
   * Initiates an immediate shutdown of the bounded executor.
   *
   * <p>This method:
   *
   * <ul>
   *   <li>Sets the pool state to STOP
   *   <li>Closes the task queue (new submissions will be rejected)
   *   <li>Interrupts all worker threads
   *   <li>Returns the list of tasks that were still queued in the queue
   * </ul>
   *
   * <p><b>Note:</b> Tasks that were already taken from the queue by workers before they observed
   * the STOP state may be dropped and will not be included in the returned list. This is a
   * documented behavior due to the inherent race between draining the queue and workers taking
   * tasks.
   *
   * @return list of tasks that never began execution
   * @since 1.0.0
   */
  public List<Runnable> shutdownNow() {
    poolLock.lock();
    try {
      poolState = PoolState.STOP;
      blockingQueue.close();

      for (Thread workerThread : workerThreads) {
        workerThread.interrupt();
      }

    } finally {
      poolLock.unlock();
    }

    return blockingQueue.drain();
  }

  /**
   * Blocks until all tasks have completed execution after a shutdown request, or the timeout
   * occurs, or the current thread is interrupted, whichever happens first.
   *
   * @param timeout the maximum time to wait
   * @param unit the time unit of the timeout argument
   * @return true if this executor terminated and false if the timeout elapsed before termination
   * @throws InterruptedException if interrupted while waiting
   * @since 1.0.0
   */
  @SuppressFBWarnings(
      value = "CWO_CLOSED_WITHOUT_OPENED",
      justification = "Lock is properly acquired before the try block and released in finally")
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
    for (Thread workerThread : workerThreads) {
      long remainingNanos = deadlineNanos - System.nanoTime();
      if (remainingNanos <= 0) {
        return false;
      }

      long millis = remainingNanos / 1_000_000L;
      int nanos = (int) (remainingNanos % 1_000_000L);

      workerThread.join(millis, nanos);
      if (workerThread.isAlive()) {
        return false;
      }
    }

    // All threads have terminated, update state
    poolLock.lock();
    try {
      poolState = PoolState.TERMINATED;
    } finally {
      poolLock.unlock();
    }

    return true;
  }

  /**
   * Returns true if this pool has been shut down.
   *
   * @return true if this pool has been shut down
   * @since 1.0.0
   */
  public boolean isShutdown() {
    poolLock.lock();
    try {
      return poolState != PoolState.RUNNING;
    } finally {
      poolLock.unlock();
    }
  }

  /**
   * Returns true if this pool has been terminated. A pool is terminated after shutdown() or
   * shutdownNow() and all worker threads have exited.
   *
   * @return true if this pool has been terminated
   * @since 1.0.0
   */
  public boolean isTerminated() {
    poolLock.lock();
    try {
      return poolState == PoolState.TERMINATED;
    } finally {
      poolLock.unlock();
    }
  }

  /**
   * Returns true if this pool is running and accepting new tasks.
   *
   * @return true if this pool is running
   * @since 1.0.0
   */
  public boolean isRunning() {
    poolLock.lock();
    try {
      return poolState == PoolState.RUNNING;
    } finally {
      poolLock.unlock();
    }
  }

  PoolState getPoolState() {
    poolLock.lock();
    try {
      return poolState;
    } finally {
      poolLock.unlock();
    }
  }

  /**
   * Returns the number of worker threads in this pool.
   *
   * @return the number of worker threads
   * @since 1.0.0
   */
  public int getPoolSize() {
    return workerThreads.size();
  }

  /**
   * Returns the current number of tasks in the queue.
   *
   * @return the number of queued tasks
   * @since 1.0.0
   */
  public int getQueueSize() {
    return blockingQueue.size();
  }

  /**
   * Returns the remaining capacity of the queue.
   *
   * @return the remaining capacity
   * @since 1.0.0
   */
  public int getQueueRemainingCapacity() {
    return blockingQueue.remainingCapacity();
  }

  /**
   * Returns true if the queue is full.
   *
   * @return true if the queue is full
   * @since 1.0.0
   */
  public boolean isQueueFull() {
    return blockingQueue.isFull();
  }

  private final class Worker implements Runnable {
    @Override
    public void run() {
      while (true) {
        if (poolState == PoolState.STOP) {
          return;
        }

        Runnable task;
        try {
          task = blockingQueue.take();
          // If take() returns null, queue is closed and empty - time to exit
          if (task == null) {
            return;
          }
        } catch (InterruptedException e) {
          // Handle interrupt from shutdownNow()
          Thread.currentThread().interrupt();
          return;
        }

        // Check if pool was stopped after taking the task.
        // If STOP, we drop the task without executing it.
        // This task won't be returned by shutdownNow() due to the race
        // between draining the queue and workers taking tasks.
        if (poolState == PoolState.STOP) {
          return;
        }

        try {
          task.run();
        } catch (Throwable t) {
          logger.error("Exception occurred while executing task", t);
        }
      }
    }
  }
}
