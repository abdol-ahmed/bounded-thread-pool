package dev.aahmedlab.concurrent;

/**
 * Policy for handling task submissions when the queue is full.
 *
 * @since 1.0.0
 */
public enum RejectionPolicy {
  /**
   * Blocks the calling thread until space becomes available.
   *
   * @since 1.0.0
   */
  BLOCK,

  /**
   * Throws RejectedExecutionException.
   *
   * @since 1.0.0
   */
  ABORT,

  /**
   * Runs the task in the calling thread.
   *
   * @since 1.0.0
   */
  CALLER_RUNS,

  /**
   * Silently discards the task.
   *
   * @since 1.0.0
   */
  DISCARD,

  /**
   * Discards the oldest task and adds the new one.
   *
   * @since 1.0.0
   */
  DISCARD_OLDEST
}
