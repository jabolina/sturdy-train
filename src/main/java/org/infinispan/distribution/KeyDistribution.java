package org.infinispan.distribution;

import java.util.function.Supplier;

public interface KeyDistribution extends Supplier<Integer> {

   default Integer key() {
      return get();
   }

   static KeyDistribution uniform(int size) {
      return new UniformKeyDistribution(size);
   }

   static KeyDistribution zipfian(int size) {
      return new ScrambledZipfianKeySelector(size);
   }
}
