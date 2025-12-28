package io.github.abdol_ahmed.btp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("slow")
class ConcurrencyTest {

  private BoundedThreadPool pool;

  @AfterEach
  void tearDown() throws InterruptedException {
    if (pool != null) {
      BoundedThreadPoolTestSupport.shutdownAndAwait(pool, 2, TimeUnit.SECONDS);
    }
  }

  @Test
  void exactlyOnceExecutionUnderManyProducers() throws Exception {
    pool = BoundedThreadPoolTestSupport.createBlockingPool(4, 10000);

    int producers = 4;
    int perProducer = 100;
    int total = producers * perProducer;

    AtomicIntegerArray seen = new AtomicIntegerArray(total);
    CountDownLatch done = new CountDownLatch(total);

    ExecutorService submitters = Executors.newFixedThreadPool(producers);
    for (int p = 0; p < producers; p++) {
      final int base = p * perProducer;
      submitters.submit(
          () -> {
            for (int i = 0; i < perProducer; i++) {
              int id = base + i;
              try {
                pool.submit(
                    () -> {
                      int prev = seen.getAndIncrement(id);
                      if (prev != 0) fail("Task executed more than once: id=" + id);
                      done.countDown();
                    });
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Submitter thread interrupted while submitting task id=" + id);
              }
            }
          });
    }

    submitters.shutdown();
    assertTrue(submitters.awaitTermination(1, TimeUnit.SECONDS), "submitters didn't finish");
    assertTrue(done.await(2, TimeUnit.SECONDS), "tasks didn't finish");

    for (int i = 0; i < total; i++) {
      assertEquals(1, seen.get(i), "Task missing or duplicated: id=" + i);
    }
  }

  @Test
  void preservesOrderPerProducer() throws Exception {
    pool = BoundedThreadPoolTestSupport.createBlockingPool(1, 10000);

    int n = 500;
    CountDownLatch done = new CountDownLatch(n);
    AtomicInteger expected = new AtomicInteger(0);
    AtomicReference<Throwable> firstError = new AtomicReference<>();

    for (int i = 0; i < n; i++) {
      int submitted = i;
      try {
        pool.submit(
            () -> {
              try {
                int exp = expected.getAndIncrement();
                if (exp != submitted) {
                  firstError.compareAndSet(
                      null,
                      new AssertionError("Out of order. expected=" + exp + " got=" + submitted));
                }
              } finally {
                done.countDown();
              }
            });
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("Thread interrupted while submitting task " + i);
        break;
      }
    }

    assertTrue(done.await(2, TimeUnit.SECONDS), "tasks didn't finish");
    if (firstError.get() != null) throw new AssertionError(firstError.get());
  }
}
