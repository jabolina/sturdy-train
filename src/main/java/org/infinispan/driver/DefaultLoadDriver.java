package org.infinispan.driver;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.distribution.KeyDistribution;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.jfr.CacheOperationEvent;

import jdk.internal.vm.annotation.Contended;

final class DefaultLoadDriver implements LoadDriver {
   private static final Logger LOG = LogManager.getLogger(DefaultLoadDriver.class);

   private static final int FREE = 0;
   private static final int OCCUPIED = 1;

   // In case everything is occupied.
   private static final long MAX_IDLE_SPINS = 100L;

   private final KeyDistribution distribution;
   private final RemoteCache<Integer, String> cache;
   private final String payload;

   private final int mask;
   private final int depth;

   private final TicketState[] states;

   private final CountDownLatch latch = new CountDownLatch(1);

   private volatile int running = 1;
   private int cursor = 0;
   private long operations;
   private long errors;

   public DefaultLoadDriver(RemoteCache<Integer, String> cache, KeyDistribution distribution, int valueSize, int concurrency) {
      this.distribution = distribution;
      this.cache = cache;

      int powerOfTwo = 1;
      while (powerOfTwo < concurrency) {
         powerOfTwo <<= 1;
      }

      this.depth = powerOfTwo;
      this.mask = powerOfTwo - 1;

      this.payload = "-".repeat(valueSize);

      this.states = new TicketState[this.depth];

      for (int i = 0; i < this.depth; i++) {
         this.states[i] = new TicketState();
      }
   }

   @Override
   public long operations() {
      return operations;
   }

   @Override
   public long errors() {
      return errors;
   }

   @Override
   public boolean stop(long timeout, TimeUnit unit) throws InterruptedException {
      running = 0;
      return latch.await(timeout, unit);
   }

   @Override
   public void run() {
      while (running != 0 && !Thread.currentThread().isInterrupted()) {
         int ticket = acquireFreeTicket();
         if (ticket < 0)
            break;

         Integer key = distribution.key();
         TicketState state = states[ticket];

         CompletableFuture.runAsync(() -> {
                  if (state.event != null) {
                     state.event.key = key;
                     state.event.begin();
                  }
                  cache.put(key, payload);
               }, Executors.newVirtualThreadPerTaskExecutor()).whenComplete(state.onOperationDone);
      }

      for (int i = 0; i < this.depth; i++) {
         TicketState state = states[i];
         while (state.status == OCCUPIED)
            Thread.onSpinWait();

         handleFreeTicket(state);
      }

      latch.countDown();
   }

   private int acquireFreeTicket() {
      int currentCursor = cursor;
      long idleSpin = 0;

      while (running != 0 && !Thread.currentThread().isInterrupted()) {
         TicketState state = states[currentCursor];

         if (state.status == FREE) {
            handleFreeTicket(state);

            if (CacheOperationEvent.isEventEnabled()) {
               state.event = new CacheOperationEvent();
            }
            state.status = OCCUPIED;
            cursor = (currentCursor + 1) & mask;
            return currentCursor;
         }

         currentCursor = (currentCursor + 1) & mask;

         if (currentCursor == cursor) {
            if (idleSpin++ < MAX_IDLE_SPINS) {
               Thread.onSpinWait();
            } else {
               idleSpin = 0;
               LockSupport.park();
            }
         }
      }

      return -1;
   }

   private void handleFreeTicket(TicketState state) {
      if (state.isError > 0) {
         errors++;
         state.isError = 0;
      }

      operations++;
      state.consumer = Thread.currentThread();
   }

   @Contended
   private static final class TicketState {
      final BiConsumer<Object, Throwable> onOperationDone;
      volatile int status = FREE;
      int isError;
      Thread consumer;
      CacheOperationEvent event;

      private TicketState() {
         this.onOperationDone = (r, t) -> {
            if (t != null) {
               LOG.debug("Failed submitting cache write", t);
               isError = 1;
            }

            if (event != null) {
               event.success = isError == 0;
               event.end();
               event.commit();
            }

            status = FREE;
            LockSupport.unpark(consumer);
         };
      }
   }
}
