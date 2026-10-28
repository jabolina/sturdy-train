package org.infinispan.driver;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.distribution.KeyDistribution;
import org.infinispan.jfr.CacheOperationEvent;

final class UniformLoadDriver implements LoadDriver {
   private static final Logger LOG = LogManager.getLogger(UniformLoadDriver.class);

   private final KeyDistribution distribution;
   private final RemoteCache<Integer, String> cache;
   private final String payload;
   private final double readRatio;
   private final long intervalNs;
   private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

   // High watermark: Pause when we hit this many pending requests
   private final long maxPending;
   // Low watermark: Resume when the server has chewed through enough of the backlog
   private final long resumePending;

   private final CountDownLatch latch = new CountDownLatch(1);

   private volatile boolean running = true;

   private final boolean throttle;
   private final LongAdder operations = new LongAdder();
   private final LongAdder errors = new LongAdder();

   public UniformLoadDriver(RemoteCache<Integer, String> cache, KeyDistribution distribution, int valueSize, double rate, double readRatio, boolean throttle) {
      this.distribution = distribution;
      this.cache = cache;
      this.readRatio = readRatio;
      this.intervalNs = Math.round(TimeUnit.SECONDS.toNanos(1) / rate);
      this.throttle = throttle;
      this.payload = "u".repeat(valueSize);

      // Accumulate maximum 4 seconds worth of requests
      this.maxPending = (long) (rate * 2);
      // Resume when backlog drops to 0.5 second worth of requests
      this.resumePending = (long) (rate * 0.5);
   }

   @Override
   public long operations() {
      return operations.sum();
   }

   @Override
   public long errors() {
      return errors.sum();
   }

   @Override
   public boolean stop(long timeout, TimeUnit unit) throws InterruptedException {
      running = false;
      boolean success = latch.await(timeout, unit);
      executor.shutdownNow();
      return success && executor.awaitTermination(timeout, unit);
   }

   @Override
   public void run() {
      running = true;

      long startNanos = System.nanoTime();
      long index = 0;

      while (running && !Thread.currentThread().isInterrupted()) {
         long intendedStart = startNanos + index * intervalNs;
         schedule(intendedStart);

         Integer key = distribution.key();
         if (Thread.currentThread().isInterrupted())
            break;

         executor.execute(() -> {
            long serviceStart = System.nanoTime();
            CacheOperationEvent event = null;

            if (CacheOperationEvent.isEventEnabled()) {
               event = new CacheOperationEvent();
               event.begin();
               event.key = key;
            }

            boolean success = submit(key);

            if (event != null) {
               event.success = success;
               event.wait = serviceStart - intendedStart;
               event.end();
               event.commit();
            }

            operations.increment();
            if (!success)
               errors.increment();
         });
         index++;

         if (throttle && (index & 0x3FF) == 0) {
            long completed = operations.sum() + errors.sum();
            long pending = index - completed;

            if (pending > maxPending) {
               LOG.warn("Pressure valve triggered! {} pending operations. Pausing open-loop...", pending);
               long pauseStart = System.nanoTime();

               // Wait until the server processes enough of the backlog to hit our low watermark
               while (pending > resumePending && running && !Thread.currentThread().isInterrupted()) {
                  // Park for 100ms so we don't burn CPU while waiting for the server
                  LockSupport.parkNanos(100_000_000);

                  // Recalculate pending
                  completed = operations.sum() + errors.sum();
                  pending = index - completed;
               }

               long pauseDuration = System.nanoTime() - pauseStart;

               // CRITICAL: Shift the baseline time forward to prevent a catch-up burst
               startNanos += pauseDuration;
               LOG.info("Server recovered (Pending: {}). Resuming open-loop...", pending);
            }
         }
      }

      executor.shutdown();
      latch.countDown();
   }

   private boolean submit(Integer key) {
      try {
         if (ThreadLocalRandom.current().nextDouble() < readRatio) {
            cache.get(key);
         } else {
            cache.put(key, payload);
         }
         return true;
      } catch (Exception e) {
         LOG.debug("Failed submitting operation", e);
         return false;
      }
   }

   private void schedule(long start) {
      while (!Thread.currentThread().isInterrupted()) {
         long now = System.nanoTime();
         if (now >= start)
            break;

         long wait = start - now;
         if (wait > 100_000) {
            LockSupport.parkNanos(wait - 50_000);
         } else {
            Thread.onSpinWait();
         }
      }
   }
}
