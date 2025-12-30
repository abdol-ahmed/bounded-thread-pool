# BoundedExecutor Usage Guide

## Quick Start

### Add to your project

#### Maven
```xml
<dependency>
    <groupId>dev.aahmedlab</groupId>
    <artifactId>bounded-executor</artifactId>
    <version>1.0.1</version>
</dependency>
```

## Basic Usage

### 1. Using Factory Methods (Recommended)

```java
// Create a bounded executor with default BLOCK policy
BoundedExecutor pool = BoundedExecutor.create(4, 100);

// Create a fixed-size executor with CALLER_RUNS policy
BoundedExecutor pool = BoundedExecutor.createFixed(8);

// Create an executor optimized for CPU-bound tasks
BoundedExecutor pool = BoundedExecutor.createCpuBound();

// Create an executor optimized for I/O-bound tasks
BoundedExecutor pool = BoundedExecutor.createIoBound();
```

### 2. Manual Configuration

```java
// Custom configuration with ABORT policy
BoundedExecutor pool = new BoundedExecutor(
    4,                    // pool size
    100,                  // queue capacity
    RejectionPolicy.ABORT // rejection policy
);
```

### 3. Submitting Tasks

```java
// Submit a simple task
pool.submit(() -> {
    System.out.println("Task executed");
});

// Submit a task with parameters
String message = "Hello";
pool.submit(() -> {
    System.out.println(message);
});
```

### 4. Shutdown

```java
// Graceful shutdown (waits for queued tasks)
pool.shutdown();
pool.awaitTermination(5, TimeUnit.SECONDS);

// Immediate shutdown (interrupts workers)
List<Runnable> unfinished = pool.shutdownNow();
pool.awaitTermination(5, TimeUnit.SECONDS);
```

## Rejection Policies

### BLOCK (Default)
```java
// Blocks the calling thread until space is available
BoundedExecutor pool = BoundedExecutor.create(4, 10);
```

### ABORT
```java
// Throws RejectedExecutionException when queue is full
BoundedExecutor pool = new BoundedExecutor(4, 10, RejectionPolicy.ABORT);
```

### DISCARD
```java
// Silently discards the task when queue is full
BoundedExecutor pool = new BoundedExecutor(4, 10, RejectionPolicy.DISCARD);
```

### DISCARD_OLDEST
```java
// Removes the oldest task and adds the new one
BoundedExecutor pool = new BoundedExecutor(4, 10, RejectionPolicy.DISCARD_OLDEST);
```

### CALLER_RUNS
```java
// Runs the task in the calling thread when queue is full
BoundedExecutor pool = new BoundedExecutor(4, 10, RejectionPolicy.CALLER_RUNS);
```

## Best Practices

1. **Choose appropriate pool size**:
   - CPU-bound: Number of cores
   - I/O-bound: 2x number of cores
   - Mixed: Start with cores * 1.5

2. **Set reasonable queue capacity**:
   - Too small: Frequent rejections
   - Too large: Memory pressure
   - Good starting point: poolSize * 2

3. **Always shutdown**:
   ```java
   try (BoundedExecutor pool = BoundedExecutor.createFixed(4)) {
       // Use pool
   } // Automatically calls shutdown()
   ```

4. **Handle interruptions**:
   ```java
   try {
       pool.submit(task);
   } catch (InterruptedException e) {
       Thread.currentThread().interrupt();
       // Handle interruption
   }
   ```

## Monitoring

```java
// Check pool state
System.out.println("Pool size: " + pool.getPoolSize());
System.out.println("Queue size: " + pool.getQueueSize());
System.out.println("Queue remaining capacity: " + pool.getQueueRemainingCapacity());
System.out.println("Is queue full? " + pool.isQueueFull());
System.out.println("Pool state: " + pool.getPoolState());
```

## Example: Web Server

```java
public class WebServer {
    private final BoundedExecutor requestPool;
    
    public WebServer() {
        // Optimize for I/O-bound requests
        this.requestPool = BoundedExecutor.createIoBound();
    }
    
    public void handleRequest(Request request) {
        try {
            requestPool.submit(() -> {
                processRequest(request);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Server shutdown", e);
        }
    }
    
    public void shutdown() {
        requestPool.shutdown();
        try {
            if (!requestPool.awaitTermination(30, TimeUnit.SECONDS)) {
                requestPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            requestPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
```

## Comparison with Executors

| Feature | BoundedExecutor | ThreadPoolExecutor |
|---------|-------------------|-------------------|
| Bounded Queue | ✅ Built-in | ⚠️ Optional |
| Rejection Policies | ✅ 5 policies | ✅ 4 policies |
| Memory Predictable | ✅ Yes | ⚠️ Depends |
| Simple API | ✅ Yes | ⚠️ Complex |
| JDK Version | 17+ | 5+ |
