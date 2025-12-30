package dev.aahmedlab.concurrent;

/**
 * Internal state of the bounded executor. This enum is package-private and not part of the public
 * API. Use the public boolean methods (isRunning(), isShutdown(), isTerminated()) to check pool
 * state.
 */
enum PoolState {
  RUNNING,
  SHUTDOWN,
  STOP,
  TERMINATED
}
