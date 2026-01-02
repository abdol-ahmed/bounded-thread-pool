# Bounded Executor

A lightweight, thread-safe bounded executor implementation in Java with support for various rejection policies and graceful shutdown semantics.

## What It Guarantees

### Bounded Queue Guarantees
- **Fixed Capacity**: The executor enforces a strict maximum queue size to prevent memory exhaustion
- **Memory Predictability**: Bounded capacity ensures predictable memory usage under load
- **Backpressure**: When the queue is full, the executor applies the configured rejection policy instead of growing indefinitely
- **Thread Safety**: All queue operations are thread-safe using `ReentrantLock` and `Condition` variables

### Shutdown Semantics Guarantees

#### Graceful Shutdown (`shutdown()`)
- **Completes Queued Tasks**: All tasks already in the queue will be executed before termination
- **Rejects New Tasks**: No new tasks are accepted after shutdown initiation
- **No Thread Interruption**: Workers finish their current tasks and exit naturally
- **Clean Termination**: Pool transitions through SHUTDOWN → TERMINATED states

#### Immediate Shutdown (`shutdownNow()`)
- **Interrupts Workers**: All worker threads are interrupted immediately
- **Returns Unexecuted Tasks**: Returns a list of tasks that were still queued
- **Forceful Termination**: Pool transitions through STOP → TERMINATED states
- **Race Condition Handling**: Tasks taken by workers before observing STOP state may be dropped (documented behavior)

### State Guarantees
- **Atomic State Transitions**: State changes are atomic and visible across all threads
- **Volatile State Reads**: Pool state can be read without locks for performance
- **Consistent Behavior**: All operations respect the current pool state

## Overload Policies + Examples

### Available Rejection Policies

#### BLOCK (Default Policy)
**Behavior**: Blocks the calling thread until space becomes available in the queue.

**Use Case**: Rate limiting and backpressure control.

```java
BoundedExecutor pool = BoundedExecutor.create(4, 10, RejectionPolicy.BLOCK);

// This will block if the queue is full
pool.submit(() -> {
    System.out.println("Task executed when space available");
});
```

**Example**: Rate limiting API calls
```java
// Limits concurrent API calls to prevent overwhelming external service
BoundedExecutor apiPool = BoundedExecutor.create(2, 5, RejectionPolicy.BLOCK);

for (Request request : requests) {
    try {
        apiPool.submit(() -> callExternalApi(request));
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
    }
}
```

#### ABORT
**Behavior**: Throws `RejectedExecutionException` immediately when the queue is full.

**Use Case**: Fail-fast scenarios where you want immediate feedback on overload.

```java
BoundedExecutor pool = BoundedExecutor.create(4, 10, RejectionPolicy.ABORT);

try {
    pool.submit(() -> System.out.println("Task executed"));
} catch (RejectedExecutionException e) {
    System.err.println("Queue full, task rejected: " + e.getMessage());
}
```

**Example**: Critical task processing
```java
BoundedExecutor criticalPool = BoundedExecutor.create(4, 10, RejectionPolicy.ABORT);

for (CriticalTask task : criticalTasks) {
    try {
        criticalPool.submit(() -> processCriticalTask(task));
    } catch (RejectedExecutionException e) {
        // Handle overload immediately
        alertSystem("Critical task queue full!");
        break;
    }
}
```

#### DISCARD
**Behavior**: Silently discards the task when the queue is full.

**Use Case**: Lossy data pipelines where occasional task loss is acceptable.

```java
BoundedExecutor pool = BoundedExecutor.create(4, 10, RejectionPolicy.DISCARD);

// Task may be silently discarded if queue is full
pool.submit(() -> System.out.println("Best-effort task execution"));
```

**Example**: Non-critical logging or metrics
```java
BoundedExecutor metricsPool = BoundedExecutor.create(2, 50, RejectionPolicy.DISCARD);

// Log metrics without blocking main application flow
metricsPool.submit(() -> {
    try {
        recordMetric(metricData);
    } catch (Exception e) {
        // Silently fail - metrics are non-critical
    }
});
```

#### DISCARD_OLDEST
**Behavior**: Removes the oldest task from the queue and adds the new task.

**Use Case**: Caching scenarios where newer data is more valuable than older data.

```java
BoundedExecutor pool = BoundedExecutor.create(4, 10, RejectionPolicy.DISCARD_OLDEST);

// Oldest task is discarded if queue is full
pool.submit(() -> System.out.println("Newer task prioritized"));
```

**Example**: Real-time data processing
```java
BoundedExecutor dataProcessor = BoundedExecutor.create(4, 100, RejectionPolicy.DISCARD_OLDEST);

// Process latest sensor readings, dropping older ones
for (SensorReading reading : sensorReadings) {
    dataProcessor.submit(() -> processLatestReading(reading));
}
```

#### CALLER_RUNS
**Behavior**: Executes the task in the calling thread when the queue is full.

**Use Case**: Responsive UIs where you want to maintain progress but prefer backpressure.

```java
BoundedExecutor pool = BoundedExecutor.create(4, 10, RejectionPolicy.CALLER_RUNS);

// Task runs in caller thread if queue is full
pool.submit(() -> System.out.println("Task executed in: " + Thread.currentThread().getName()));
```

**Example**: Web server request handling
```java
public class WebServer {
    private final BoundedExecutor requestPool = BoundedExecutor.createFixed(8);
    
    public void handleRequest(Request request) {
        try {
            requestPool.submit(() -> processRequest(request));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Server shutdown", e);
        }
        // If queue is full, request runs in caller thread (I/O thread)
        // This provides backpressure while maintaining responsiveness
    }
}
```

### Policy Selection Guidelines

| Scenario | Recommended Policy | Reason |
|----------|-------------------|--------|
| Rate limiting | BLOCK | Provides natural backpressure |
| Critical tasks | ABORT | Immediate feedback on overload |
| Non-critical data | DISCARD | No impact on main flow |
| Real-time updates | DISCARD_OLDEST | Prioritizes fresh data |
| Responsive UI | CALLER_RUNS | Maintains user experience |

## Verification Notes

### How to Test Saturation Safely

#### 1. Basic Saturation Testing
```java
@Test
public void testQueueSaturation() throws InterruptedException {
    int poolSize = 2;
    int queueCapacity = 5;
    BoundedExecutor pool = new BoundedExecutor(poolSize, queueCapacity, RejectionPolicy.BLOCK);
    
    // Fill the queue with long-running tasks
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch blockLatch = new CountDownLatch(1);
    
    // Submit tasks that will block workers
    for (int i = 0; i < poolSize + queueCapacity; i++) {
        pool.submit(() -> {
            startLatch.await(); // Wait for all tasks to be queued
            blockLatch.await(); // Block until we're ready
        });
    }
    
    startLatch.countDown(); // Release all tasks
    
    // Try to submit one more - should block
    AtomicBoolean submitted = new AtomicBoolean(false);
    Thread submitter = new Thread(() -> {
        try {
            pool.submit(() -> submitted.set(true));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    });
    
    submitter.start();
    Thread.sleep(100); // Give time for blocking
    assertFalse(submitted.get(), "Task should not execute while queue is full");
    
    // Release one task
    blockLatch.countDown();
    Thread.sleep(100);
    
    // Now the extra task should execute
    assertTrue(submitted.get(), "Task should execute after space available");
    
    pool.shutdown();
}
```

#### 2. Rejection Policy Testing
```java
@Test
public void testAbortPolicy() {
    BoundedExecutor pool = new BoundedExecutor(1, 1, RejectionPolicy.ABORT);
    
    // Submit blocking task
    CountDownLatch latch = new CountDownLatch(1);
    pool.submit(() -> {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    });
    
    // Fill queue
    pool.submit(() -> {});
    
    // This should throw RejectedExecutionException
    assertThrows(RejectedExecutionException.class, () -> {
        pool.submit(() -> {});
    });
    
    latch.countDown();
    pool.shutdown();
}
```

#### 3. Shutdown Testing
```java
@Test
public void testGracefulShutdown() throws InterruptedException {
    BoundedExecutor pool = new BoundedExecutor(2, 10);
    
    AtomicInteger completedTasks = new AtomicInteger(0);
    int taskCount = 20;
    
    // Submit tasks
    for (int i = 0; i < taskCount; i++) {
        pool.submit(() -> {
            Thread.sleep(10); // Simulate work
            completedTasks.incrementAndGet();
        });
    }
    
    // Initiate shutdown
    pool.shutdown();
    
    // Wait for completion
    assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    assertEquals(taskCount, completedTasks.get(), "All tasks should complete");
}
```

#### 4. Stress Testing for Deadlock Prevention
```java
@Test
public void testNoDeadlockDuringShutdown() throws InterruptedException {
    BoundedExecutor pool = new BoundedExecutor(2, 5, RejectionPolicy.BLOCK);
    
    // Producer thread that submits tasks
    Thread producer = new Thread(() -> {
        for (int i = 0; i < 100; i++) {
            try {
                pool.submit(() -> {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    });
    
    producer.start();
    
    // Wait for some tasks to queue, then shutdown
    Thread.sleep(50);
    pool.shutdown();
    
    // Should complete without deadlock
    assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    producer.join(1000);
}
```

#### 5. Memory Usage Verification
```java
@Test
public void testMemoryBoundedness() {
    Runtime runtime = Runtime.getRuntime();
    long initialMemory = runtime.totalMemory() - runtime.freeMemory();
    
    BoundedExecutor pool = new BoundedExecutor(4, 1000, RejectionPolicy.DISCARD);
    
    // Submit many more tasks than queue capacity
    for (int i = 0; i < 10000; i++) {
        pool.submit(() -> {
            // Small task that allocates some memory
            byte[] data = new byte[1024];
        });
    }
    
    // Force garbage collection
    System.gc();
    long finalMemory = runtime.totalMemory() - runtime.freeMemory();
    long memoryIncrease = finalMemory - initialMemory;
    
    // Memory increase should be bounded (much less than 10MB for 10K tasks)
    assertTrue(memoryIncrease < 10 * 1024 * 1024, 
        "Memory usage should be bounded, was: " + memoryIncrease + " bytes");
    
    pool.shutdown();
}
```

### Testing Best Practices

1. **Use Timeouts**: Always use timeouts in tests to prevent hangs
2. **CountDownLatch**: Coordinate task execution for predictable testing
3. **Atomic Variables**: Track task completion safely across threads
4. **Memory Monitoring**: Verify bounded memory usage under load
5. **Shutdown Testing**: Test both graceful and immediate shutdown scenarios
6. **Policy Validation**: Test each rejection policy under saturation conditions
7. **Deadlock Prevention**: Test concurrent submission and shutdown scenarios

### Performance Verification

```java
@Test
public void testThroughputUnderLoad() throws InterruptedException {
    BoundedExecutor pool = BoundedExecutor.createFixed(4);
    
    int taskCount = 10000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    long startTime = System.nanoTime();
    
    for (int i = 0; i < taskCount; i++) {
        pool.submit(() -> {
            // Simulate CPU-bound work
            for (int j = 0; j < 1000; j++) {
                Math.sqrt(j);
            }
            latch.countDown();
        });
    }
    
    latch.await();
    long duration = System.nanoTime() - startTime;
    double throughput = taskCount / (duration / 1_000_000_000.0);
    
    System.out.println("Throughput: " + throughput + " tasks/second");
    assertTrue(throughput > 1000, "Should achieve reasonable throughput");
    
    pool.shutdown();
}
```

## Installation

Add to your project via Maven Central:

```xml
<dependency>
    <groupId>dev.aahmedlab</groupId>
    <artifactId>bounded-executor</artifactId>
    <version>1.0.4</version>
</dependency>
```

## Requirements

- JDK 17+
- Maven 3.8.6+ (for building from source)
