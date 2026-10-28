package org.infinispan;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ClientIntelligence;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.commons.configuration.StringConfiguration;
import org.infinispan.commons.util.Version;
import org.infinispan.distribution.KeyDistribution;
import org.infinispan.driver.LoadDriver;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "driver", version = "0.0.1", mixinStandardHelpOptions = true)
public class Main implements Runnable {

   private static final Logger LOG = LogManager.getLogger(Main.class);

   @Option(names = "--servers",
         description = ";-separated Hot Rod server endpoints. (default: ${DEFAULT-VALUE})",
         defaultValue = "localhost:11222")
   private String servers;

   @Option(names = "--configuration", description = "The cache configuration in a XML file", required = true)
   private File cacheConfiguration;

   @Option(names = "--keys", description = "Bounded key set size. (default: ${DEFAULT-VALUE})", defaultValue = "5000")
   private int keys;

   @Option(names = "--value-size",
         description = "The size of the String payload to submit. (default: ${DEFAULT-VALUE})",
         defaultValue = "512")
   private int valueSize;

   @Option(names = "--threads", description = "Number of writer threads. (default: ${DEFAULT-VALUE})", defaultValue = "128")
   private int threads;

   @Option(names = "--user", description = "Server username. (default: ${DEFAULT-VALUE})", defaultValue = "admin")
   private String user;

   @Option(names = "--password", description = "Server password. (default: ${DEFAULT-VALUE})", defaultValue = "password")
   private String password;

   @Option(names = "--rate", description = "Rate of requests per second. (default: ${DEFAULT-VALUE})", defaultValue = "-1")
   private double rate;

   @Option(names = "--distribution", converter = Distribution.Converter.class,
         description = "Key distribution pattern: uniform or zipfian. (default: ${DEFAULT-VALUE})", defaultValue = "uniform")
   private Distribution distribution;

   @Option(names = "--read-ratio",
         description = "Fraction of operations that are reads, 0.0 to 1.0. (default: ${DEFAULT-VALUE})",
         defaultValue = "0.0")
   private double readRatio;

   @Option(names = "--duration", description = "Duration of the execution, e.g., PT1M, PT30S. (default is unbounded)")
   private Duration duration;

   @Option(names = "--throttle", description = "Whether to throttle the load driver", defaultValue = "false")
   private boolean throttling;

   enum Distribution {
      UNIFORM,
      ZIPFIAN;

      static class Converter implements CommandLine.ITypeConverter<Distribution> {
         @Override
         public Distribution convert(String value) {
            return Distribution.valueOf(value.toUpperCase());
         }
      }
   }

   @Override
   public void run() {
      LOG.info("Utilising version: {}", Version.printVersion());
      LOG.info("Connecting to servers {} with configuration {}", servers, cacheConfiguration);

      try (RemoteCacheManager rcm = createCacheManager(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
         RemoteCache<Integer, String> cache = createCache(rcm);
         populateCache(cache);
         LoadDriver driver = loadDriver(cache);

         final long startTime = System.currentTimeMillis();
         AtomicBoolean running = new AtomicBoolean(true);
         Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Stopping driver and writing summary");
            running.set(false);
            handleStop(driver, startTime);
         }));

         if (rate <= 0) {
            LOG.info("Starting the driver with {} threads and {} keys", threads, keys);
         } else {
            LOG.info("Starting the drive at fixed rate of {}/s and {} keys", rate, keys);
         }
         Future<?> future = executor.submit(driver);

         long previousOps = 0;
         while (running.get() && !elapsed(startTime)) {
            LockSupport.parkNanos(5_000_000_000L);

            long elapsed = System.currentTimeMillis() - startTime;
            long totalOps = driver.operations();
            long errors = driver.errors();
            long windowOps = totalOps - previousOps;
            previousOps = totalOps;

            LOG.info("[{}] ops={} | success={} | errors={} | ops/s={}", formatTime(elapsed), totalOps, totalOps - errors, errors, windowOps / 5);
         }

         if (elapsed(startTime)) {
            handleStop(driver, startTime);
         }

         future.get(120, TimeUnit.SECONDS);
      } catch (Exception e) {
         LOG.error("Exception when running the driver", e);
      }
   }

   private void handleStop(LoadDriver driver, long startTime) {
      try {
         driver.stop(120, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         LOG.error("Interrupted while stopping driver.");
      }

      long elapsed = System.currentTimeMillis() - startTime;
      printSummary(driver, elapsed);
   }

   private RemoteCacheManager createCacheManager() {
      ConfigurationBuilder builder = new ConfigurationBuilder();
      builder.addServers(servers);
//      builder.maxRetries(0);
//      builder.clientIntelligence(ClientIntelligence.BASIC);
      builder.security().authentication()
            .username(user)
            .password(password);
      return new RemoteCacheManager(builder.build());
   }

   private RemoteCache<Integer, String> createCache(RemoteCacheManager rcm) {
      try {
         String configuration = Files.readString(cacheConfiguration.toPath());
         LOG.info("Creating cache '{}' with {}", cacheConfiguration.getName(), configuration);
         return rcm.administration()
               .getOrCreateCache(cacheConfiguration.getName(), new StringConfiguration(configuration));
      } catch (IOException e) {
         LOG.error("Failed creating cache with {}", cacheConfiguration, e);
         throw new RuntimeException(e);
      }
   }

   private LoadDriver loadDriver(RemoteCache<Integer, String> cache) {
      if (rate > 0) {
         return LoadDriver.builder()
               .withCache(cache)
               .withRequestRate(rate)
               .withKeyDistribution(createKeyDistribution())
               .withValueSize(valueSize)
               .withReadRatio(readRatio)
               .uniform(throttling);
      }

      return LoadDriver.builder()
            .withCache(cache)
            .withConcurrency(threads)
            .withKeyDistribution(createKeyDistribution())
            .withValueSize(valueSize)
            .withReadRatio(readRatio)
            .throughput();
   }

   private KeyDistribution createKeyDistribution() {
      return switch (distribution) {
         case UNIFORM -> KeyDistribution.uniform(keys);
         case ZIPFIAN -> KeyDistribution.zipfian(keys);
      };
   }

   private static String formatTime(long elapsedMs) {
      long seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMs);
      return String.format("%ds", seconds);
   }

   private static void printSummary(LoadDriver driver, long elapsedMs) {
      long total = driver.operations();
      double elapsedSec = elapsedMs / 1000.0;
      StringBuilder sb = new StringBuilder(System.lineSeparator());
      sb.append("=== Summary ===").append(System.lineSeparator());
      sb.append("Duration:  ").append(elapsedSec).append('s').append(System.lineSeparator());
      sb.append("Total ops: ").append(total).append(System.lineSeparator());
      sb.append("Errors:    ").append(driver.errors()).append(System.lineSeparator());
      sb.append("Avg ops/s: ").append(total / elapsedSec).append(System.lineSeparator());
      LOG.info(sb);
   }

   private void populateCache(RemoteCache<Integer, String> cache) {
      cache.clear();
      String payload = "p".repeat(valueSize);
      for (int i = 0; i < keys; i++) {
         cache.put(i, payload);
      }
   }

   private boolean elapsed(long startMs) {
      if (duration == null)
         return false;

      long nowMs = System.currentTimeMillis();
      long totalMs = duration.toMillis();
      return (nowMs - startMs) >= totalMs;
   }

   public static void main(String[] args) {
      CommandLine cli = new CommandLine(new Main());
      System.exit(cli.execute(args));
   }
}
