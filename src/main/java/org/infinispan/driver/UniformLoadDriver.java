package org.infinispan.driver;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
   private final long intervalNs;
   private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

   private final CountDownLatch latch = new CountDownLatch(1);

   private volatile boolean running = true;

   private final LongAdder operations = new LongAdder();
   private final LongAdder errors = new LongAdder();

   public UniformLoadDriver(RemoteCache<Integer, String> cache, KeyDistribution distribution, int valueSize, double rate) {
      this.distribution = distribution;
      this.cache = cache;
      this.intervalNs = Math.round(TimeUnit.SECONDS.toNanos(1) / rate);
      this.payload = "u".repeat(valueSize);
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
      }

      executor.shutdown();
      latch.countDown();
   }

   private boolean submit(Integer key) {
      try {
         cache.put(key, payload);
         return true;
      } catch (Exception e) {
         LOG.debug("Failed submitting write", e);
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
