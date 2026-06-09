package org.infinispan.jfr;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.TreeMap;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

public class JfrReport {

   private static final String EVENT_NAME = "org.infinispan.resiliency.CacheOperation";

   public static void main(String[] args) throws Exception {
      if (args.length < 2) {
         System.err.println("Usage: JfrReport <recording.jfr> <output.html>");
         System.exit(1);
      }

      Path jfrFile = Path.of(args[0]);
      Path outputFile = Path.of(args[1]);

      if (!Files.exists(jfrFile)) {
         System.err.println("JFR file not found: " + jfrFile);
         System.exit(1);
      }

      List<OpRecord> operations = parseRecording(jfrFile);
      if (operations.isEmpty()) {
         System.err.println("No CacheOperation events found in " + jfrFile);
         System.exit(1);
      }

      writeReport(operations, outputFile);
      System.out.println("Report written to: " + outputFile.toAbsolutePath());
   }

   private static List<OpRecord> parseRecording(Path jfrFile) throws IOException {
      List<OpRecord> operations = new ArrayList<>();
      try (RecordingFile rf = new RecordingFile(jfrFile)) {
         while (rf.hasMoreEvents()) {
            RecordedEvent event = rf.readEvent();
            if (!EVENT_NAME.equals(event.getEventType().getName()))
               continue;

            Instant start = event.getStartTime();
            long serviceNanos = event.getDuration().toNanos();
            boolean success = event.getBoolean("success");
            long waitNanos = event.hasField("wait") ? event.getLong("wait") : 0;
            operations.add(new OpRecord(start, serviceNanos, waitNanos, success));
         }
      }
      return operations;
   }

   private static void writeReport(List<OpRecord> operations, Path outputFile) throws IOException {
      Instant globalStart = operations.stream()
            .map(OpRecord::start)
            .min(Instant::compareTo)
            .orElseThrow();

      long totalOps = operations.size();
      long totalErrors = operations.stream().filter(o -> !o.success).count();
      boolean hasWaitData = operations.stream().anyMatch(o -> o.waitNanos > 0);

      LongSummaryStatistics latencyStats = operations.stream()
            .mapToLong(OpRecord::correctedNanos)
            .summaryStatistics();

      long[] sorted = operations.stream().mapToLong(OpRecord::correctedNanos).sorted().toArray();
      long p50 = percentile(sorted, 50);
      long p90 = percentile(sorted, 90);
      long p99 = percentile(sorted, 99);
      long p999 = percentile(sorted, 99.9);

      int bucketMs = 1000;
      TreeMap<Long, BucketStats> buckets = new TreeMap<>();
      for (OpRecord op : operations) {
         long relativeMs = Duration.between(globalStart, op.start).toMillis();
         long bucketKey = (relativeMs / bucketMs) * bucketMs;
         buckets.computeIfAbsent(bucketKey, k -> new BucketStats()).add(op);
      }

      StringBuilder throughputData = new StringBuilder("[");
      StringBuilder latencyP50Data = new StringBuilder("[");
      StringBuilder latencyP90Data = new StringBuilder("[");
      StringBuilder latencyP99Data = new StringBuilder("[");
      StringBuilder latencyMaxData = new StringBuilder("[");
      StringBuilder svcP50Data = new StringBuilder("[");
      StringBuilder svcP99Data = new StringBuilder("[");
      StringBuilder waitP50Data = new StringBuilder("[");
      StringBuilder waitP99Data = new StringBuilder("[");
      StringBuilder errorData = new StringBuilder("[");

      for (var entry : buckets.entrySet()) {
         long t = entry.getKey();
         BucketStats stats = entry.getValue();
         double throughput = stats.count * (1000.0 / bucketMs);

         long[] correctedSorted = stats.correctedLatencies.stream().mapToLong(Long::longValue).sorted().toArray();

         throughputData.append("{x:").append(t).append(",y:").append(String.format("%.1f", throughput)).append("},");
         latencyP50Data.append("{x:").append(t).append(",y:").append(String.format("%.3f", percentile(correctedSorted, 50) / 1_000_000.0)).append("},");
         latencyP90Data.append("{x:").append(t).append(",y:").append(String.format("%.3f", percentile(correctedSorted, 90) / 1_000_000.0)).append("},");
         latencyP99Data.append("{x:").append(t).append(",y:").append(String.format("%.3f", percentile(correctedSorted, 99) / 1_000_000.0)).append("},");
         latencyMaxData.append("{x:").append(t).append(",y:").append(String.format("%.3f", correctedSorted[correctedSorted.length - 1] / 1_000_000.0)).append("},");

         if (hasWaitData) {
            long[] svcSorted = stats.serviceLatencies.stream().mapToLong(Long::longValue).sorted().toArray();
            long[] waitSorted = stats.waitLatencies.stream().mapToLong(Long::longValue).sorted().toArray();

            svcP50Data.append("{x:").append(t).append(",y:").append(String.format("%.3f", percentile(svcSorted, 50) / 1_000_000.0)).append("},");
            svcP99Data.append("{x:").append(t).append(",y:").append(String.format("%.3f", percentile(svcSorted, 99) / 1_000_000.0)).append("},");
            waitP50Data.append("{x:").append(t).append(",y:").append(String.format("%.3f", percentile(waitSorted, 50) / 1_000_000.0)).append("},");
            waitP99Data.append("{x:").append(t).append(",y:").append(String.format("%.3f", percentile(waitSorted, 99) / 1_000_000.0)).append("},");
         }

         errorData.append("{x:").append(t).append(",y:").append(stats.errors).append("},");
      }

      throughputData.append("]");
      latencyP50Data.append("]");
      latencyP90Data.append("]");
      latencyP99Data.append("]");
      latencyMaxData.append("]");
      svcP50Data.append("]");
      svcP99Data.append("]");
      waitP50Data.append("]");
      waitP99Data.append("]");
      errorData.append("]");

      long durationSec = Duration.between(globalStart,
            operations.stream().map(OpRecord::start).max(Instant::compareTo).orElseThrow()).toSeconds();

      String coNote = hasWaitData
            ? "Latency includes wait time (intended start to completion). Service time and wait time are shown separately below."
            : "This report does not correct for coordinated omission. During degradation periods, the driver's backpressure "
            + "reduces the request rate, so stalled operations are underrepresented. Percentiles may understate tail latency under load.";

      String latencyLabel = hasWaitData ? "Corrected Latency (wait + service, ms)" : "Latency (ms)";

      StringBuilder breakdownChart = new StringBuilder();
      if (hasWaitData) {
         breakdownChart.append(String.format("""

            <div class="chart-box">
              <h3>Service Time vs Wait Time (ms)</h3>
              <canvas id="breakdown" height="60"></canvas>
            </div>
            """));
      }

      StringBuilder breakdownScript = new StringBuilder();
      if (hasWaitData) {
         breakdownScript.append(String.format("""
            timeChart('breakdown', [
              {label:'service p50',data:%s,borderColor:'#3cb44b',borderWidth:1.5,pointRadius:0,fill:false,tension:0.2},
              {label:'service p99',data:%s,borderColor:'#3cb44b',borderWidth:1.5,pointRadius:0,fill:false,tension:0.2,borderDash:[6,3]},
              {label:'wait p50',data:%s,borderColor:'#911eb4',borderWidth:1.5,pointRadius:0,fill:false,tension:0.2},
              {label:'wait p99',data:%s,borderColor:'#911eb4',borderWidth:1.5,pointRadius:0,fill:false,tension:0.2,borderDash:[6,3]}
            ], 'ms');
            """, svcP50Data, svcP99Data, waitP50Data, waitP99Data));
      }

      String html = String.format("""
            <!DOCTYPE html>
            <html><head>
            <title>Resiliency Harness — JFR Report</title>
            <script src="https://cdn.jsdelivr.net/npm/chart.js@4"></script>
            <style>
              body { font-family: sans-serif; margin: 20px; background: #fafafa; }
              .chart-box { background: white; border-radius: 8px; padding: 16px; margin-bottom: 24px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
              .summary { display: flex; gap: 24px; flex-wrap: wrap; margin-bottom: 24px; }
              .stat { background: white; border-radius: 8px; padding: 16px 24px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
              .stat .label { font-size: 12px; color: #666; text-transform: uppercase; }
              .stat .value { font-size: 24px; font-weight: 600; margin-top: 4px; }
            </style>
            </head><body>
            <h2>Resiliency Harness — Client JFR Report</h2>
            <p>Recording: %s | Duration: %ds</p>
            <p style="color:#888;font-size:13px;">%s</p>

            <div class="summary">
              <div class="stat"><div class="label">Total Ops</div><div class="value">%s</div></div>
              <div class="stat"><div class="label">Errors</div><div class="value">%d</div></div>
              <div class="stat"><div class="label">Avg Latency</div><div class="value">%s</div></div>
              <div class="stat"><div class="label">p50</div><div class="value">%s</div></div>
              <div class="stat"><div class="label">p90</div><div class="value">%s</div></div>
              <div class="stat"><div class="label">p99</div><div class="value">%s</div></div>
              <div class="stat"><div class="label">p99.9</div><div class="value">%s</div></div>
              <div class="stat"><div class="label">Max</div><div class="value">%s</div></div>
            </div>

            <div class="chart-box">
              <h3>Throughput (ops/s)</h3>
              <canvas id="throughput" height="60"></canvas>
            </div>

            <div class="chart-box">
              <h3>%s</h3>
              <canvas id="latency" height="60"></canvas>
            </div>
            %s
            <div class="chart-box">
              <h3>Errors</h3>
              <canvas id="errors" height="40"></canvas>
            </div>

            <script>
            function timeChart(id, datasets, yLabel) {
              new Chart(document.getElementById(id), {
                type: 'line',
                data: { datasets },
                options: {
                  responsive: true,
                  interaction: { mode: 'index', intersect: false },
                  scales: {
                    x: { type: 'linear', title: { display: true, text: 'Time (s)' },
                         ticks: { callback: v => (v/1000).toFixed(0)+'s' } },
                    y: { title: { display: true, text: yLabel }, min: 0 }
                  },
                  plugins: { tooltip: { callbacks: {
                    title: items => (items[0].parsed.x/1000).toFixed(0)+'s'
                  }}}
                }
              });
            }

            timeChart('throughput', [
              {label:'ops/s',data:%s,borderColor:'#4363d8',backgroundColor:'#4363d880',borderWidth:2,pointRadius:0,fill:true,tension:0.2}
            ], 'ops/s');

            timeChart('latency', [
              {label:'p50',data:%s,borderColor:'#3cb44b',borderWidth:1.5,pointRadius:0,fill:false,tension:0.2},
              {label:'p90',data:%s,borderColor:'#4363d8',borderWidth:1.5,pointRadius:0,fill:false,tension:0.2},
              {label:'p99',data:%s,borderColor:'#f58231',borderWidth:1.5,pointRadius:0,fill:false,tension:0.2},
              {label:'max',data:%s,borderColor:'#e6194b',borderWidth:1.5,pointRadius:0,fill:false,tension:0.2}
            ], 'ms');
            %s
            timeChart('errors', [
              {label:'errors',data:%s,borderColor:'#e6194b',backgroundColor:'#e6194b40',borderWidth:2,pointRadius:0,fill:true,tension:0.2}
            ], 'count');
            </script>
            </body></html>
            """,
            outputFile.getFileName(), durationSec, coNote,
            fmtCount(totalOps), totalErrors,
            fmtNs(Math.round(latencyStats.getAverage())),
            fmtNs(p50), fmtNs(p90), fmtNs(p99), fmtNs(p999), fmtNs(latencyStats.getMax()),
            latencyLabel, breakdownChart,
            throughputData, latencyP50Data, latencyP90Data, latencyP99Data, latencyMaxData,
            breakdownScript, errorData);

      try (BufferedWriter writer = Files.newBufferedWriter(outputFile)) {
         writer.write(html);
      }
   }

   private static long percentile(long[] sorted, double p) {
      if (sorted.length == 0) return 0;
      int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
      return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
   }

   private static String fmtNs(long nanos) {
      if (nanos < 1_000) return nanos + "ns";
      if (nanos < 1_000_000) return String.format("%.1fus", nanos / 1_000.0);
      if (nanos < 1_000_000_000) return String.format("%.2fms", nanos / 1_000_000.0);
      return String.format("%.2fs", nanos / 1_000_000_000.0);
   }

   private static String fmtCount(long count) {
      if (count < 1_000) return Long.toString(count);
      if (count < 1_000_000) return String.format("%.1fK", count / 1_000.0);
      return String.format("%.2fM", count / 1_000_000.0);
   }

   private record OpRecord(Instant start, long serviceNanos, long waitNanos, boolean success) {
      long correctedNanos() {
         return serviceNanos + waitNanos;
      }
   }

   private static class BucketStats {
      int count;
      int errors;
      final List<Long> serviceLatencies = new ArrayList<>();
      final List<Long> correctedLatencies = new ArrayList<>();
      final List<Long> waitLatencies = new ArrayList<>();

      void add(OpRecord op) {
         count++;
         serviceLatencies.add(op.serviceNanos);
         correctedLatencies.add(op.correctedNanos());
         waitLatencies.add(op.waitNanos);
         if (!op.success) errors++;
      }
   }
}
