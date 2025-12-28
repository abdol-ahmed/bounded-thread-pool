# Bounded Thread Pool

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java 17+](https://img.shields.io/badge/Java-17+-green.svg)](https://openjdk.org/)
[![CI](https://github.com/abdol-ahmed/bounded-thread-pool/actions/workflows/ci.yml/badge.svg)](https://github.com/abdol-ahmed/bounded-thread-pool/actions/workflows/ci.yml)
[![Qodana](https://github.com/abdol-ahmed/bounded-thread-pool/actions/workflows/qodana_code_quality.yml/badge.svg)](https://github.com/abdol-ahmed/bounded-thread-pool/actions/workflows/qodana_code_quality.yml)

A lightweight, thread-safe bounded thread pool implementation in Java with support for various rejection policies and graceful shutdown semantics.

## Features

- **Bounded Capacity**: Configurable maximum queue size prevents memory exhaustion
- **Multiple Rejection Policies**: BLOCK, ABORT, DISCARD, DISCARD_OLDEST, CALLER_RUNS
- **Graceful Shutdown**: `shutdown()` completes queued tasks without interruption
- **Immediate Shutdown**: `shutdownNow()` interrupts workers and returns unexecuted tasks
- **Thread Safety**: Fully thread-safe implementation using `ReentrantLock` and `Condition`
- **Deadlock-Free**: BLOCK policy handled without holding pool lock
- **Factory Methods**: Convenient factory methods for common configurations
- **Clean API**: Minimal public surface with intuitive boolean state methods

## Requirements

- JDK 17+
- Maven 3.6.0+ (project uses Maven 3.9.x)

Note: CI runs tooling on JDK 21 for formatter/static-analysis compatibility while compiling bytecode with `--release 17`.

## Installation

### Maven Central

Add the following dependency to your project:

#### Maven
```xml
<dependency>
    <groupId>io.github.abdol-ahmed.btp</groupId>
    <artifactId>bounded-thread-pool</artifactId>
    <version>1.0.1</version>
</dependency>
```

If you're looking for the latest version, check the GitHub tags and Maven Central.

## Quick Start

### Basic Example

```java
import io.github.abdol_ahmed.btp.BoundedThreadPool;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        // Create a thread pool with 4 workers
        BoundedThreadPool pool = BoundedThreadPool.createFixed(4);
        
        // Submit tasks
        int taskCount = 10;
        CountDownLatch done = new CountDownLatch(taskCount);
        
        for (int i = 0; i < taskCount; i++) {
            final int taskId = i;
            pool.submit(() -> {
                System.out.println("Task " + taskId + " running in " + 
                    Thread.currentThread().getName());
                done.countDown();
            });
        }
        
        // Wait for completion
        if (done.await(5, TimeUnit.SECONDS)) {
            System.out.println("All tasks completed!");
        }
        
        // Shutdown
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }
}
```

## Usage Examples

### Factory Methods (Recommended)

```java
// Create a pool with default BLOCK policy
BoundedThreadPool pool = BoundedThreadPool.create(4, 100);

// Create a fixed-size pool with CALLER_RUNS policy
BoundedThreadPool pool = BoundedThreadPool.createFixed(8);

// Create a pool optimized for CPU-bound tasks
BoundedThreadPool pool = BoundedThreadPool.createCpuBound();

// Create a pool optimized for I/O-bound tasks
BoundedThreadPool pool = BoundedThreadPool.createIoBound();
```

### Submitting Tasks

```java
// Submit a simple task
pool.submit(() -> {
    System.out.println("Task executed in: " + Thread.currentThread().getName());
});

// Submit a task with parameters
String message = "Hello";
pool.submit(() -> {
    System.out.println(message);
});
```

### Rejection Policies

```java
// BLOCK: Wait for space in queue (default)
BoundedThreadPool pool1 = new BoundedThreadPool(2, 10, RejectionPolicy.BLOCK);

// ABORT: Throw RejectedExecutionException if queue is full
BoundedThreadPool pool2 = new BoundedThreadPool(2, 10, RejectionPolicy.ABORT);

// DISCARD: Silently discard task if queue is full
BoundedThreadPool pool3 = new BoundedThreadPool(2, 10, RejectionPolicy.DISCARD);

// DISCARD_OLDEST: Remove oldest task and add new one if queue is full
BoundedThreadPool pool4 = new BoundedThreadPool(2, 10, RejectionPolicy.DISCARD_OLDEST);

// CALLER_RUNS: Execute task in caller thread if queue is full
BoundedThreadPool pool5 = new BoundedThreadPool(2, 10, RejectionPolicy.CALLER_RUNS);
```

### Shutdown Patterns

```java
// Graceful shutdown - completes all queued tasks
pool.shutdown();
if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
    // Optional: force shutdown if graceful takes too long
    List<Runnable> remaining = pool.shutdownNow();
    System.out.println("Unexecuted tasks: " + remaining.size());
}

// Immediate shutdown - interrupts workers
List<Runnable> unexecuted = pool.shutdownNow();
```

### Monitoring

```java
// Check pool state
System.out.println("Pool size: " + pool.getPoolSize());
System.out.println("Queue size: " + pool.getQueueSize());
System.out.println("Queue remaining capacity: " + pool.getQueueRemainingCapacity());
System.out.println("Is queue full? " + pool.isQueueFull());
System.out.println("Is running? " + pool.isRunning());
System.out.println("Is shutdown? " + pool.isShutdown());
System.out.println("Is terminated? " + pool.isTerminated());
```

## Building from Source

```bash
# Clone the repository
git clone https://github.com/abdol-ahmed/bounded-thread-pool.git
cd bounded-thread-pool

# Build the project
mvn -B -ntp clean compile

# Run tests
mvn -B -ntp test

# Run full CI-quality build (tests + Spotless + SpotBugs)
mvn -B -ntp clean verify

# Package JAR
mvn package

# Install to local repository
mvn install
```

## Architecture

### Core Components

- **`BoundedThreadPool`**: Main thread pool implementation
- **`BoundedBlockingQueue`**: Internal bounded queue (package-private)
- **`PoolState`**: Internal pool state enum (package-private)
- **`RejectionPolicy`**: Enum defining task rejection strategies

### Thread Pool States

1. **RUNNING**: Accepts new tasks and executes them
2. **SHUTDOWN**: Rejects new tasks, completes queued tasks gracefully
3. **STOP**: Rejects new tasks, interrupts workers, doesn't start new tasks
4. **TERMINATED**: All workers have terminated

## Implementation Details

### Deadlock Prevention

The BLOCK rejection policy is handled outside the pool lock to prevent deadlocks:

```java
if (rejectionPolicy == RejectionPolicy.BLOCK) {
    if (poolState != PoolState.RUNNING) {
        throw new RejectedExecutionException("Pool is shut down");
    }
    blockingQueue.put(task); // May block, but doesn't hold poolLock
    return;
}
```

### Graceful Shutdown Mechanism

1. `shutdown()` sets state to SHUTDOWN and closes the queue
2. Workers detect queue closure via `take()` returning null
3. Workers finish current tasks and exit naturally

### Immediate Shutdown Behavior

Important: Tasks already taken by workers before they observe the STOP state may be dropped and won't be included in the returned list. This is documented behavior due to the inherent race between draining the queue and workers taking tasks.

On the other hand, tasks still queued at the time of the shutdown are returned from `shutdownNow()`.

## Testing

The project includes comprehensive tests covering:

- Basic task execution
- Thread pool sizing
- Rejection policy behavior
- Shutdown and termination
- Task accounting during shutdown
- Edge cases and error conditions
- Stress testing

Run tests with: `mvn test`

## Design Decisions

1. **Clean Public API**: Only essential methods exposed, implementation details hidden
2. **Factory Methods**: Convenient constructors for common use cases
3. **Boolean State Methods**: Intuitive state checking without exposing internal enum
4. **Volatile Pool State**: `poolState` is volatile for visibility without requiring lock acquisition
5. **Separate Locks**: Pool and queue use separate locks to minimize contention
6. **Daemon Workers**: Worker threads are daemon to prevent JVM hangs

## Limitations

1. **Task Accounting**: During `shutdownNow()`, tasks taken by workers before observing STOP may be dropped (documented behavior).

2. **No Dynamic Resizing**: Thread pool size is fixed after construction.

3. **No Priority Queue**: Tasks are executed in FIFO order only.

## Versioning

This project follows [Semantic Versioning](https://semver.org/).

- **MAJOR**: Incompatible API changes
- **MINOR**: New functionality in a backward compatible manner
- **PATCH**: Backward compatible bug fixes

Versions are published using git tags in the form `vX.Y.Z`.

## Best Practices

1. **Choose the Right Policy**:
   - Use `BLOCK` for rate limiting
   - Use `ABORT` for fail-fast scenarios
   - Use `CALLER_RUNS` for responsive UIs
   - Use `DISCARD` for lossy data pipelines

2. **Always Shutdown**:
   ```java
   try (BoundedThreadPool pool = BoundedThreadPool.createFixed(4)) {
       // Use pool
   } // Automatically calls shutdown()
   ```

3. **Monitor Pool State**:
   ```java
   if (pool.isQueueFull()) {
       // Handle backpressure
       logger.warn("Queue is full, consider throttling");
   }
   ```

4. **Handle Interruptions**:
   ```java
   try {
       pool.submit(task);
   } catch (InterruptedException e) {
       Thread.currentThread().interrupt();
       // Handle interruption gracefully
   }
   ```

## License

Apache License 2.0

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

This repository uses branch protection and CODEOWNERS:

- **Direct pushes to `main` are blocked**.
- **All changes must go through a Pull Request**.
- **PR approval by the code owner is required before merge**.

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests for new functionality
5. Ensure all tests pass
6. Submit a pull request

### Release process (maintainers)

Releases are automated via GitHub Actions and triggered by pushing a version tag:

```bash
git checkout main
git pull

git tag v1.0.2
git push origin v1.0.2
```

The release workflow:

- Builds with `-Prelease`
- Signs artifacts (GPG)
- Publishes to Maven Central
- Creates a GitHub Release with autogenerated release notes

Release secrets are expected to be stored in the GitHub Environment named `release`.

### Project Maintainer

- **Abdullah Ahmed** (abdol-ahmed)
- Email: abdol.ahmed@gmail.com

### Build Requirements

- **Maven**: 3.6.0+
- **Java**: 17+
- **Test Framework**: JUnit 5.10.0

## Additional Resources

- [USAGE.md](USAGE.md) - Detailed usage examples
- [VERSIONING.md](VERSIONING.md) - Version management guide
