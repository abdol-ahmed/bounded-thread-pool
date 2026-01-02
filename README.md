# Bounded Executor

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java 17+](https://img.shields.io/badge/Java-17+-green.svg)](https://openjdk.org/)
[![CI](https://github.com/aahmedlab/bounded-executor/actions/workflows/ci.yml/badge.svg)](https://github.com/aahmedlab/bounded-executor/actions/workflows/ci.yml)
[![Qodana](https://github.com/aahmedlab/bounded-executor/actions/workflows/qodana_code_quality.yml/badge.svg)](https://github.com/aahmedlab/bounded-executor/actions/workflows/qodana_code_quality.yml)
[![Maven Central](https://img.shields.io/maven-central/v/dev.aahmedlab/bounded-executor.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.aahmedlab/bounded-executor)
[![Maven Central](https://img.shields.io/maven-central/last-update/dev.aahmedlab/bounded-executor)](https://central.sonatype.com/artifact/dev.aahmedlab/bounded-executor)
[![javadoc](https://javadoc.io/badge2/dev.aahmedlab/bounded-executor/javadoc.svg)](https://javadoc.io/doc/dev.aahmedlab/bounded-executor)
[![Release](https://img.shields.io/github/v/release/aahmedlab/bounded-executor?sort=semver)](https://github.com/aahmedlab/bounded-executor/releases)
[![Issues](https://img.shields.io/github/issues/aahmedlab/bounded-executor)](https://github.com/aahmedlab/bounded-executor/issues)
[![Stars](https://img.shields.io/github/stars/aahmedlab/bounded-executor?style=social)](https://github.com/aahmedlab/bounded-executor/stargazers)

A lightweight, thread-safe bounded executor implementation in Java with support for various rejection policies and graceful shutdown semantics.

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
- Maven 3.8.6+ (enforced by Maven Enforcer)

Note: CI runs tooling on JDK 21 for formatter/static-analysis compatibility while compiling bytecode with `--release 17`.
Note: This project enforces a JDK range via Maven Enforcer; see `pom.xml` for the current allowed range.

## Installation

### Maven Central

Add the following dependency to your project:

#### Maven
```xml
<dependency>
    <groupId>dev.aahmedlab</groupId>
    <artifactId>bounded-executor</artifactId>
    <version>1.0.4</version>
</dependency>
```

If you're looking for the latest version, check the GitHub tags and Maven Central.

## Quick Start

### Basic Example

```java
import dev.aahmedlab.concurrent.BoundedExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class Main {
   public static void main(String[] args) throws InterruptedException {
      // Create a bounded executor with 4 workers
      BoundedExecutor pool = BoundedExecutor.createFixed(4);

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

## Detailed Documentation

For comprehensive information about guarantees, overload policies, and testing, see the [detailed library documentation](docs/bounded-executor.md).

- **What It Guarantees**: Bounded queue semantics and shutdown behavior
- **Overload Policies**: Complete guide with examples for all rejection policies  
- **Verification Notes**: How to test saturation and verify behavior safely
- **Performance Testing**: Guidelines for throughput and memory testing

## Building from Source

```bash
# Clone the repository
git clone https://github.com/aahmedlab/bounded-executor.git
cd bounded-executor

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

### Troubleshooting build tool failures

```bash
# Re-run with full stack traces
mvn -B -ntp -e clean verify

# Re-run with Maven debug logging
mvn -B -ntp -X clean verify
```

If you need to temporarily bypass static analysis while debugging:

```bash
# Skip SpotBugs
mvn -B -ntp -Dspotbugs.skip=true clean verify

# Skip Spotless check
mvn -B -ntp -Dspotless.check.skip=true clean verify
```

### Local release-profile build (signing)

The `release` profile enables GPG signing via `maven-gpg-plugin`.

```bash
# Build and run release-profile checks (will attempt to sign during verify)
mvn -B -ntp -Prelease -DskipTests verify
```

## Architecture

### Core Components

- **`BoundedExecutor`**: Main bounded executor implementation
- **`BoundedBlockingQueue`**: Internal bounded queue (package-private)
- **`PoolState`**: Internal pool state enum (package-private)
- **`RejectionPolicy`**: Enum defining task rejection strategies

### Bounded Executor States

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

#### Maven Central publishing prerequisites

- **Namespace approval**: Maven Central must allow publishing for the `groupId` (`dev.aahmedlab`). If the namespace is not claimed/verified in the Sonatype Central Portal, publishing will fail with a message like `Namespace 'dev.aahmedlab' is not allowed`.
- **GPG signing**: Maven Central requires `.asc` signatures for the main jar, sources jar, javadoc jar, and pom.
- **Credentials/secrets**: The release workflow expects secrets in the GitHub Environment named `release`:
  - `CENTRAL_USERNAME`
  - `CENTRAL_PASSWORD`
  - `GPG_PRIVATE_KEY`
  - `GPG_PASSPHRASE`

#### Publishing a new version

After the tag is pushed, the GitHub Actions release workflow sets the Maven project version from the git tag:

```bash
mvn -B -ntp versions:set -DnewVersion=${GITHUB_REF#refs/tags/v} -DgenerateBackupPoms=false
mvn -B -ntp versions:commit
```

```bash
# Ensure main is up to date
git checkout main
git pull

# Create and push the tag that matches the intended version
git tag v1.0.4
git push origin v1.0.4
```

The release workflow:

- Sets the project version from the tag
- Builds with `-Prelease`
- Signs artifacts (GPG)
- Publishes to Maven Central
- Creates a GitHub Release with autogenerated release notes

Release secrets are expected to be stored in the GitHub Environment named `release`.

### Project Maintainer

- **Abdullah Ahmed** (abdol-ahmed)
- Email: abdol.ahmed@gmail.com

### Build Requirements

- **Maven**: 3.8.6+
- **Java**: 17+
- **Test Framework**: JUnit 5.10.0

## Additional Resources

- [docs/index.md](docs/index.md) - Library index and overview
- [docs/bounded-executor.md](docs/bounded-executor.md) - Detailed library documentation
- [USAGE.md](USAGE.md) - Basic usage examples
- [CONCURRENCY_DESIGN.md](CONCURRENCY_DESIGN.md) - Implementation details and design patterns
- [VERSIONING.md](VERSIONING.md) - Version management guide
