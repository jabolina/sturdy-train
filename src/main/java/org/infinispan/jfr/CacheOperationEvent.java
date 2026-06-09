package org.infinispan.jfr;

import jdk.jfr.Category;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

@Name("org.infinispan.resiliency.CacheOperation")
@Label("Cache Operation")
@Category("Resiliency")
@Enabled(false)
@StackTrace(false)
@Threshold
public class CacheOperationEvent extends Event {

   private static final EventType EVENT_TYPE = EventType.getEventType(CacheOperationEvent.class);

   @Label("Key")
   public Integer key;

   @Label("Success")
   public boolean success;

   @Label("Waiting")
   public long wait;

   public static boolean isEventEnabled() {
      return EVENT_TYPE.isEnabled();
   }
}
