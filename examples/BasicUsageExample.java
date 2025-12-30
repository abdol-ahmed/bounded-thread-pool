package examples;

import dev.aahmedlab.concurrent.BoundedExecutor;
import dev.aahmedlab.concurrent.RejectionPolicy;

/**
 * Example demonstrating basic usage of BoundedExecutor.
 * This is not part of the API - just a demonstration.
 */
public class BasicUsageExample {
    public static void main(String[] args) {
        // Create a bounded executor using factory method
        BoundedExecutor executor = BoundedExecutor.createFixed(4);
        
        // Submit tasks
        for (int i = 0; i < 100; i++) {
            final int taskId = i;
            
            try {
                executor.submit(() -> {
                    System.out.println("Task " + taskId + " running in " + 
                        Thread.currentThread().getName());
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Task submission interrupted");
                break;
            }
        }
        
        // Shutdown the executor
        executor.shutdown();
        try {
            threadPool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
