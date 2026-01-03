package dev.aahmedlab.concurrent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CallableTaskTest {

  private BoundedExecutor pool;

  @BeforeEach
  void setUp() {
    pool = new BoundedExecutor(2, 5, RejectionPolicy.BLOCK);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    if (pool != null) {
      pool.shutdown();
      pool.awaitTermination(1, TimeUnit.SECONDS);
    }
  }

  @Test
  void submitCallableReturnsResult() throws Exception {
    // Submit a Callable that returns a value
    Future<Integer> future =
        pool.submit(
            () -> {
              Thread.sleep(100); // simulate work
              return 42;
            });

    // get() should block until result is ready
    Integer result = future.get();

    assertEquals(42, result);
    assertTrue(future.isDone());
  }

  @Test
  void submitCallableHandlesException() throws Exception {
    // Submit a Callable that throws
    Future<Integer> future =
        pool.submit(
            () -> {
              throw new RuntimeException("Task failed!");
            });

    // get() should throw ExecutionException wrapping the original exception
    ExecutionException thrown = assertThrows(ExecutionException.class, future::get);

    assertEquals("Task failed!", thrown.getCause().getMessage());
    assertTrue(future.isDone());
  }

  @Test
  void submitCallableWithNullThrowsNullPointerException() {
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () -> pool.submit((Callable<?>) null));
    assertEquals("task", thrown.getMessage());
  }

  @Test
  void submitCallableWithCheckedException() throws Exception {
    // Submit a Callable that throws a checked exception
    Future<String> future =
        pool.submit(
            () -> {
              throw new IOException("IO error occurred");
            });

    // get() should throw ExecutionException wrapping the IOException
    ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
    assertInstanceOf(IOException.class, thrown.getCause());
    assertEquals("IO error occurred", thrown.getCause().getMessage());
    assertTrue(future.isDone());
  }

  @Test
  void submitCallableReturnsNull() throws Exception {
    // Submit a Callable that returns null
    Future<String> future = pool.submit(() -> null);

    assertNull(future.get());
    assertTrue(future.isDone());
  }

  @Test
  void submitCallableWithComplexType() throws Exception {
    // Submit a Callable that returns a complex object
    Future<String[]> future =
        pool.submit(
            () -> new String[] {"hello", "world", "test"});

    String[] result = future.get();
    assertArrayEquals(new String[] {"hello", "world", "test"}, result);
    assertTrue(future.isDone());
  }

  @Test
  void submitCallableWithTimeout() throws Exception {
    // Submit a Callable that takes a short time
    Future<String> future =
        pool.submit(
            () -> {
              Thread.sleep(100);
              return "done";
            });

    // Note: CompletableFuture doesn't implement timed get() yet
    // This test will be updated when timeout is implemented
    // For now, just verify the task completes normally
    assertEquals("done", future.get());
    assertTrue(future.isDone());
  }

  @Test
  void submitCallableCancellation() throws Exception {
    // Submit a Callable that runs for a long time
    Future<String> future =
        pool.submit(
            () -> {
              Thread.sleep(1000);
              return "should complete";
            });

    // Note: CompletableFuture doesn't implement cancellation yet
    // This test will be updated when cancellation is implemented
    assertFalse(future.cancel(true));
    assertFalse(future.isCancelled());

    // The task should still complete normally
    assertEquals("should complete", future.get());
    assertTrue(future.isDone());
  }

  @Test
  void submitMultipleCallablesConcurrently() throws Exception {
    int numTasks = 5;
    AtomicInteger counter = new AtomicInteger(0);
    @SuppressWarnings("unchecked")
    Future<Integer>[] futures = new Future[numTasks];

    // Submit multiple Callables that increment a counter
    for (int i = 0; i < numTasks; i++) {
      futures[i] =
          pool.submit(
              () -> {
                Thread.sleep(50);
                return counter.incrementAndGet();
              });
    }

    // All futures should complete with unique values
    for (int i = 0; i < numTasks; i++) {
      int result = futures[i].get();
      assertTrue(result > 0 && result <= numTasks);
    }

    assertEquals(numTasks, counter.get());
  }

  @Test
  void submitCallableWithGenericTypes() throws Exception {
    // Test with different generic types
    Future<Double> doubleFuture = pool.submit(() -> 3.14159);
    Future<Boolean> booleanFuture = pool.submit(() -> true);
    Future<Long> longFuture = pool.submit(System::currentTimeMillis);

    assertEquals(3.14159, doubleFuture.get(), 0.00001);
    assertTrue(booleanFuture.get());
    assertNotNull(longFuture.get());
  }

  @Test
  void submitCallableAfterShutdown() {
    pool.shutdown();

    // Submitting after shutdown should throw RejectedExecutionException
    assertThrows(
        RejectedExecutionException.class,
        () -> pool.submit(() -> "should not execute"));
  }

  @Test
  void submitCallableBlocksWhenQueueFull() throws Exception {
    // Create a pool that will fill up quickly
    BoundedExecutor smallPool = new BoundedExecutor(1, 1, RejectionPolicy.BLOCK);

    try {
      // Submit a long-running task
      smallPool.submit(
          () -> {
            Thread.sleep(200);
            return null;
          });

      // Submit another task - this should block until the first completes
      Future<String> blockedTask =
          smallPool.submit(
              () -> "completed");

      // The blocked task should eventually complete
      String result = blockedTask.get();
      assertEquals("completed", result);
      assertTrue(blockedTask.isDone());
    } finally {
      smallPool.shutdown();
      smallPool.awaitTermination(1, TimeUnit.SECONDS);
    }
  }
}
