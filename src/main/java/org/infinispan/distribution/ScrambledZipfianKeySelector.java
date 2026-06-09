package org.infinispan.distribution;

final class ScrambledZipfianKeySelector implements KeyDistribution {

   public ScrambledZipfianKeySelector(int numKeys) {

   }

   @Override
   public Integer get() {
      return 0;
   }
}
