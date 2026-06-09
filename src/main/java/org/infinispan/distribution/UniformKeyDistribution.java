package org.infinispan.distribution;

import java.util.concurrent.ThreadLocalRandom;

final class UniformKeyDistribution implements KeyDistribution {

   private final int range;

   public UniformKeyDistribution(int range) {
      this.range = range;
   }

   @Override
   public Integer get() {
      return ThreadLocalRandom.current().nextInt(range);
   }
}
