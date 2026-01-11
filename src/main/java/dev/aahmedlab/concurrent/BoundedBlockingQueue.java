package dev.aahmedlab.concurrent;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Internal implementation of a bounded blocking queue. This class is package-private and not part
 * of the public API.
 *
 * @param <T> the type of elements held in this queue
 */
final class BoundedBlockingQueue<T> {
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition notEmpty = lock.newCondition();
  private final Condition notFull = lock.newCondition();
  private final int capacity;
  private final Deque<T> queue;
  private boolean closed;

  public BoundedBlockingQueue(int capacity) {
    if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
    this.capacity = capacity;
    this.queue = new ArrayDeque<>(capacity);
  }

  public void close() {
    lock.lock();
    try {
      closed = true;
      notEmpty.signalAll();
      notFull.signalAll();
    } finally {
      lock.unlock();
    }
  }

  public List<T> drain() {
    lock.lock();
    try {
      List<T> drained = new ArrayList<>(queue);
      queue.clear();
      notFull.signalAll();
      return drained;
    } finally {
      lock.unlock();
    }
  }

  public T poll() {
    lock.lock();
    try {
      if (queue.isEmpty()) {
        return null;
      }
      T item = queue.removeFirst();
      notFull.signalAll(); // signal that space is available
      return item;
    } finally {
      lock.unlock();
    }
  }

  public T take() throws InterruptedException {
    lock.lock();
    try {
      while (queue.isEmpty()) {
        if (closed) {
          return null;
        }
        notEmpty.await();
      }
      T item = queue.removeFirst();
      notFull.signalAll();
      return item;
    } finally {
      lock.unlock();
    }
  }

  @SuppressFBWarnings(
      value = "CWO_CLOSED_WITHOUT_OPENED",
      justification = "Lock is properly acquired before the try block and released in finally")
  public boolean tryPut(T task) {
    if (task == null) throw new NullPointerException("task");
    lock.lock();
    try {
      if (closed) return false;
      if (queue.size() == capacity) return false;
      queue.addLast(task);
      notEmpty.signal();
      return true;
    } finally {
      lock.unlock();
    }
  }

  public void discardOldestAndPut(T task) {
    if (task == null) throw new NullPointerException("task");
    lock.lock();
    try {
      if (closed) {
        throw new IllegalStateException("queue is closed");
      }
      // Only remove and add if queue is full, otherwise just add
      if (queue.size() == capacity && !queue.isEmpty()) {
        queue.removeFirst();
      }
      queue.addLast(task);
      notEmpty.signal();

    } finally {
      lock.unlock();
    }
  }

  public void put(T task) throws InterruptedException {
    if (task == null) throw new NullPointerException("task");
    lock.lock();
    try {
      while (queue.size() == capacity) {
        if (closed) {
          throw new IllegalStateException("queue is closed");
        }
        notFull.await();
      }
      if (closed) {
        throw new IllegalStateException("queue is closed");
      }
      queue.addLast(task);
      notEmpty.signal();
    } finally {
      lock.unlock();
    }
  }

  // Optional helper for tests/metrics later
  public int size() {
    lock.lock();
    try {
      return queue.size();
    } finally {
      lock.unlock();
    }
  }

  public boolean isEmpty() {
    lock.lock();
    try {
      return queue.isEmpty();
    } finally {
      lock.unlock();
    }
  }

  public boolean isFull() {
    lock.lock();
    try {
      return queue.size() == capacity;
    } finally {
      lock.unlock();
    }
  }

  public int remainingCapacity() {
    lock.lock();
    try {
      return capacity - queue.size();
    } finally {
      lock.unlock();
    }
  }
}
