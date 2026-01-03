package dev.aahmedlab.concurrent;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public interface Future<T> {
  T get() throws InterruptedException, ExecutionException;

  T get(long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException;

  boolean cancel(boolean mayInterruptIfRunning);

  boolean isCancelled();

  boolean isDone();
}
