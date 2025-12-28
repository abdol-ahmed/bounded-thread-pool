package io.github.abdol_ahmed.btp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BasicExecutionTest {

  private BoundedThreadPool pool;

  @AfterEach
  void tearDown() throws InterruptedException {
    if (pool != null) {
      BoundedThreadPoolTestSupport.shutdownAndAwait(pool, 2, TimeUnit.SECONDS);
    }
  }

  @Test
  void executesAllSubmittedTasks() throws Exception {
    pool = BoundedThreadPoolTestSupport.createBlockingPool(4, 1000);

    int n = 100;
    CountDownLatch done = new CountDownLatch(n);
    AtomicInteger counter = new AtomicInteger();

    for (int i = 0; i < n; i++) {
      pool.submit(
          () -> {
            counter.incrementAndGet();
            done.countDown();
          });
    }

    assertTrue(done.await(2, TimeUnit.SECONDS), "tasks did not finish in time");
    assertEquals(n, counter.get());
  }

  @Test
  void usesAtMostPoolSizeThreads() throws Exception {
    int poolSize = 3;
    pool = BoundedThreadPoolTestSupport.createBlockingPool(poolSize, 1000);

    int n = 50;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(n);

    Set<String> threadNames = ConcurrentHashMap.newKeySet();

    for (int i = 0; i < n; i++) {
      pool.submit(
          () -> {
            try {
              start.await(); // make tasks overlap
              threadNames.add(Thread.currentThread().getName());
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            } finally {
              done.countDown();
            }
          });
    }

    start.countDown();
    assertTrue(done.await(2, TimeUnit.SECONDS), "tasks did not finish in time");
    assertTrue(threadNames.size() <= poolSize, "Used more threads than poolSize: " + threadNames);
  }
}
