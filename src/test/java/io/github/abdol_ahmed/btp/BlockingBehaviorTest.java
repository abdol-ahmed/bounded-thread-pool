package io.github.abdol_ahmed.btp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BlockingBehaviorTest {

  private BoundedThreadPool pool;

  @AfterEach
  void tearDown() throws InterruptedException {
    if (pool != null) {
      BoundedThreadPoolTestSupport.shutdownAndAwait(pool, 2, TimeUnit.SECONDS);
    }
  }

  @Test
  void submitBlocksWhenQueueFullAndUnblocksWhenSpaceAvailable() throws Exception {
    pool = BoundedThreadPoolTestSupport.createBlockingPool(1, 1);
    var latches = BoundedThreadPoolTestSupport.createTaskLatches();

    // Submit blocking task
    pool.submit(BoundedThreadPoolTestSupport.createBlockingTask(latches, null));

    // Fill the queue
    pool.submit(() -> {});

    ExecutorService es = Executors.newSingleThreadExecutor();
    Future<?> blockedSubmit =
        es.submit(
            () -> {
              try {
                pool.submit(() -> {}); // should block until space is available
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            });

    assertTrue(latches.started.await(1, TimeUnit.SECONDS));

    // Should still be blocked
    assertThrows(TimeoutException.class, () -> blockedSubmit.get(200, TimeUnit.MILLISECONDS));

    // Free the worker
    latches.finish.countDown();

    // Now blocked submit should complete
    blockedSubmit.get(2, TimeUnit.SECONDS);

    es.shutdownNow();
  }

  @Test
  void blockedSubmitIsInterruptible() throws Exception {
    pool = BoundedThreadPoolTestSupport.createBlockingPool(1, 1);
    var latches = BoundedThreadPoolTestSupport.createTaskLatches();

    // Submit blocking task
    pool.submit(BoundedThreadPoolTestSupport.createBlockingTask(latches, null));

    // Fill capacity
    pool.submit(() -> {});

    Thread t =
        new Thread(
            () -> {
              try {
                pool.submit(() -> {});
                fail("Expected InterruptedException");
              } catch (InterruptedException expected) {
                // Expected
              }
            });

    assertTrue(latches.started.await(1, TimeUnit.SECONDS));
    t.start();
    Thread.sleep(100);
    t.interrupt();
    t.join(1000);

    latches.finish.countDown();
  }
}
