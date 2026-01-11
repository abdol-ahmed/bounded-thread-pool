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
        assertThrows(NullPointerException.class, () -> pool.submit((Callable<?>) null));
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
    Future<String[]> future = pool.submit(() -> new String[] {"hello", "world", "test"});

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
    assertThrows(RejectedExecutionException.class, () -> pool.submit(() -> "should not execute"));
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
      Future<String> blockedTask = smallPool.submit(() -> "completed");

      // The blocked task should eventually complete
      String result = blockedTask.get();
      assertEquals("completed", result);
      assertTrue(blockedTask.isDone());
    } finally {
      smallPool.shutdown();
      smallPool.awaitTermination(1, TimeUnit.SECONDS);
    }
  }

  @Test
  void getWithTimeoutWaitsAndReturns() throws Exception {
    BoundedExecutor pool = new BoundedExecutor(1, 5, RejectionPolicy.BLOCK);

    // Submit a task that takes 200ms
    Future<String> future =
        pool.submit(
            () -> {
              Thread.sleep(200);
              return "Done!";
            });

    // get() with 1 second timeout should succeed
    String result = future.get(1, TimeUnit.SECONDS);

    assertEquals("Done!", result);

    pool.shutdown();
    pool.awaitTermination(1, TimeUnit.SECONDS);
  }

  @Test
  void getWithTimeoutThrowsWhenExpires() throws Exception {
    BoundedExecutor pool = new BoundedExecutor(1, 5, RejectionPolicy.BLOCK);

    CountDownLatch taskStarted = new CountDownLatch(1);

    // Submit a task that takes a long time
    Future<String> future =
        pool.submit(
            () -> {
              taskStarted.countDown();
              Thread.sleep(5000); // 5 seconds
              return "Done!";
            });

    // Wait for task to start
    assertTrue(taskStarted.await(1, TimeUnit.SECONDS));

    // get() with 100ms timeout should throw TimeoutException
    assertThrows(TimeoutException.class, () -> future.get(100, TimeUnit.MILLISECONDS));

    // Future should still eventually complete (task keeps running)
    assertFalse(future.isDone()); // not done yet

    pool.shutdownNow(); // interrupt the long task
    pool.awaitTermination(1, TimeUnit.SECONDS);
  }

  @Test
  void submitCallableWithCustomException() throws Exception {
    // Custom exception for testing
    class CustomException extends Exception {
      public CustomException(String message) {
        super(message);
      }
    }

    Future<String> future =
        pool.submit(
            () -> {
              throw new CustomException("Custom error");
            });

    ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
    assertInstanceOf(CustomException.class, thrown.getCause());
    assertEquals("Custom error", thrown.getCause().getMessage());
    assertTrue(future.isDone());
  }

  @Test
  void submitCallableWithNestedClass() throws Exception {
    // Test with nested class return type
    record NestedResult(String value, int number) {

      @Override
      public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof NestedResult other)) return false;
        return value.equals(other.value) && number == other.number;
      }
    }

    Future<NestedResult> future = pool.submit(() -> new NestedResult("test", 42));

    NestedResult result = future.get();
    assertEquals(new NestedResult("test", 42), result);
    assertTrue(future.isDone());
  }

  @Test
  void submitCallableWithLargeDataStructure() throws Exception {
    // Test with large data structure
    Future<int[]> future =
        pool.submit(
            () -> {
              int[] largeArray = new int[10000];
              for (int i = 0; i < largeArray.length; i++) {
                largeArray[i] = i * 2;
              }
              return largeArray;
            });

    int[] result = future.get();
    assertEquals(10000, result.length);
    assertEquals(0, result[0]);
    assertEquals(19998, result[9999]);
    assertTrue(future.isDone());
  }

  @Test
  void submitCallableWithRecursiveCall() throws Exception {
    // Test recursive computation
    Future<Integer> future = pool.submit(() -> fibonacci(10));

    Integer result = future.get();
    assertEquals(55, result); // fibonacci(10) = 55
    assertTrue(future.isDone());
  }

  private int fibonacci(int n) {
    if (n <= 1) return n;
    return fibonacci(n - 1) + fibonacci(n - 2);
  }

  @Test
  void submitCallableThreadSafetyWithSharedResource() throws Exception {
    // Test thread safety with shared mutable resource
    AtomicInteger sharedCounter = new AtomicInteger(0);
    int numTasks = 10;
    @SuppressWarnings("unchecked")
    Future<Integer>[] futures = new Future[numTasks];

    // Submit multiple tasks that increment shared counter
    for (int i = 0; i < numTasks; i++) {
      futures[i] =
          pool.submit(
              () -> {
                int current = sharedCounter.incrementAndGet();
                Thread.sleep(10); // simulate work
                return current;
              });
    }

    // Verify all tasks completed and counter has correct value
    for (int i = 0; i < numTasks; i++) {
      int result = futures[i].get();
      assertTrue(result > 0 && result <= numTasks);
    }

    assertEquals(numTasks, sharedCounter.get());
  }

  @Test
  void submitCallableWithAbortPolicy() throws Exception {
    // Test with ABORT rejection policy
    BoundedExecutor abortPool = new BoundedExecutor(1, 1, RejectionPolicy.ABORT);

    try {
      // Fill the pool - first task gets executed immediately, second fills the queue
      abortPool.submit(
          () -> {
            Thread.sleep(200);
            return "first";
          });

      abortPool.submit(
          () -> {
            Thread.sleep(200);
            return "second";
          });

      // Next submission should be rejected
      assertThrows(RejectedExecutionException.class, () -> abortPool.submit(() -> "rejected"));
    } finally {
      abortPool.shutdown();
      abortPool.awaitTermination(1, TimeUnit.SECONDS);
    }
  }

  //  @Test
  //  void submitCallableWithCallerRunsPolicy() throws Exception {
  //    // Test with CALLER_RUNS rejection policy
  //    BoundedExecutor callerRunsPool = new BoundedExecutor(1, 1, RejectionPolicy.CALLER_RUNS);
  //    Thread callerThread = Thread.currentThread();
  //
  //    try {
  //      // Fill the pool
  //      callerRunsPool.submit(
  //          () -> {
  //            Thread.sleep(100);
  //            return "first";
  //          });
  //
  //      // Next submission should run in caller thread
  //      Future<String> future =
  //          callerRunsPool.submit(
  //              () -> {
  //                assertEquals(callerThread, Thread.currentThread());
  //                return "caller-runs";
  //              });
  //
  //      assertEquals("caller-runs", future.get());
  //      assertTrue(future.isDone());
  //    } finally {
  //      callerRunsPool.shutdown();
  //      callerRunsPool.awaitTermination(1, TimeUnit.SECONDS);
  //    }
  //  }

  @Test
  void submitCallablePerformanceUnderLoad() throws Exception {
    // Performance test with many concurrent tasks
    int numTasks = 50;
    @SuppressWarnings("unchecked")
    Future<Long>[] futures = new Future[numTasks];
    long startTime = System.currentTimeMillis();

    // Submit many CPU-bound tasks
    for (int i = 0; i < numTasks; i++) {
      final int taskId = i;
      futures[i] =
          pool.submit(
              () -> {
                long sum = 0;
                for (int j = 0; j < 1000; j++) {
                  sum += j * taskId;
                }
                return sum;
              });
    }

    // Wait for all tasks to complete
    for (int i = 0; i < numTasks; i++) {
      Long result = futures[i].get();
      assertNotNull(result);
      assertTrue(result >= 0);
    }

    long totalTime = System.currentTimeMillis() - startTime;
    assertTrue(totalTime < 5000, "Tasks should complete in reasonable time");
  }

  @Test
  void submitCallableWithVoidReturn() throws Exception {
    // Test callable that returns Void
    Future<Void> future =
        pool.submit(
            () -> {
              Thread.sleep(50);
              return null;
            });

    Void result = future.get();
    assertNull(result);
    assertTrue(future.isDone());
  }

  @Test
  void submitCallableWithMethodReference() throws Exception {
    // Test callable using method reference
    Future<String> future = pool.submit(this::generateTestString);

    String result = future.get();
    assertEquals("test-string", result);
    assertTrue(future.isDone());
  }

  private String generateTestString() {
    return "test-string";
  }
}
