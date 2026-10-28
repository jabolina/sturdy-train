package org.infinispan.driver;

import java.util.concurrent.TimeUnit;

import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.distribution.KeyDistribution;

public interface LoadDriver extends Runnable {

   long operations();

   long errors();

   boolean stop(long timeout, TimeUnit unit) throws InterruptedException;

   static Builder builder() {
      return new Builder();
   }

   class Builder {
      private RemoteCache<Integer, String> cache;
      private KeyDistribution distribution;
      private int valueSize;
      private int concurrency;
      private double rate;
      private double readRatio;

      public Builder withCache(RemoteCache<Integer, String> cache) {
         this.cache = cache;
         return this;
      }

      public Builder withKeyDistribution(KeyDistribution distribution) {
         this.distribution = distribution;
         return this;
      }

      public Builder withValueSize(int valueSize) {
         this.valueSize = valueSize;
         return this;
      }

      public Builder withConcurrency(int concurrency) {
         this.concurrency = concurrency;
         return this;
      }

      public Builder withRequestRate(double rate) {
         this.rate = rate;
         return this;
      }

      public Builder withReadRatio(double readRatio) {
         this.readRatio = readRatio;
         return this;
      }

      public LoadDriver throughput() {
         return new DefaultLoadDriver(cache, distribution, valueSize, concurrency, readRatio);
      }

      public LoadDriver uniform(boolean throttling) {
         return new UniformLoadDriver(cache, distribution, valueSize, rate, readRatio, throttling);
      }
   }
}
