package dev.aahmedlab.concurrent;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class CompletableFuture<T> implements Future<T> {
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition done = lock.newCondition();

  private T result;
  private Exception exception;
  private volatile boolean completed = false;

  @Override
  public T get() throws InterruptedException, ExecutionException {
    lock.lock();
    try {
      while (!completed) {
        done.await();
      }
      if (exception != null) {
        throw new ExecutionException(exception);
      }
      return result;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public T get(long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    lock.lock();
    try {
      long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
      while (!completed) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
          throw new TimeoutException();
        }
        if (!done.await(remainingNanos, TimeUnit.NANOSECONDS)) {
          throw new TimeoutException();
        }
      }
      if (exception != null) {
        throw new ExecutionException(exception);
      }
      return result;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    return false;
  }

  @Override
  public boolean isCancelled() {
    return false;
  }

  @Override
  public boolean isDone() {
    return completed;
  }

  // Method called by worker thread to set result
  void setResult(T result) {
    lock.lock();
    try {
      this.result = result;
      this.completed = true;
      done.signalAll();
    } finally {
      lock.unlock();
    }
  }

  // Method called by worker thread to set exception
  void setException(Exception e) {
    lock.lock();
    try {
      this.exception = e;
      this.completed = true;
      done.signalAll();
    } finally {
      lock.unlock();
    }
  }
}
