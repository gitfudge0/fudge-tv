package local.tv.launcher;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Frozen, cold-cache workload; measures local source scheduling, not provider speed. */
public final class CatalogLatencyBenchmark {
  public static void main(String[] args) throws Exception {
    int delayMs = Integer.parseInt(args[0]);
    int repetitions = Integer.parseInt(args[1]);
    List<Double> samples = new ArrayList<>();
    AtomicInteger completed = new AtomicInteger();
    for (int sample = -2; sample < repetitions; sample++) {
      File directory = Files.createTempDirectory("catalog-latency-").toFile();
      CatalogClient client =
          new CatalogClient(
              new CatalogClient.Cache(directory, 8 * 1024 * 1024),
              path -> {
                Thread.sleep(delayMs);
                String type = path.contains("/movie/") ? "movie" : "series";
                completed.incrementAndGet();
                return new JSONObject()
                    .put(
                        "metas",
                        new JSONArray()
                            .put(
                                new JSONObject()
                                    .put("id", type.equals("movie") ? "tt1160419" : "tt0944947")
                                    .put("type", type)
                                    .put("name", type)));
              });
      try {
        long start = System.nanoTime();
        JSONObject result = client.home();
        double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
        if (!"live".equals(result.getString("status"))
            || result.getBoolean("partial")
            || result.getJSONArray("items").length() != 2
            || result.getJSONArray("featured").length() != 2) {
          throw new AssertionError("Incomplete home must not count as a completed sample");
        }
        if (sample >= 0) samples.add(elapsedMs);
      } finally {
        Object resource = client;
        if (resource instanceof AutoCloseable) ((AutoCloseable) resource).close();
        for (File file : directory.listFiles()) file.delete();
        directory.delete();
      }
    }
    if (completed.get() != (repetitions + 2) * 4)
      throw new AssertionError("Source work was dropped");
    System.out.println(
        "delayMs="
            + delayMs
            + " warmups=2 samples="
            + samples
            + " completedSources="
            + completed.get());
    Collections.sort(samples);
    System.out.printf(
        "median=%.2fms min=%.2fms max=%.2fms%n",
        samples.get(samples.size() / 2), samples.get(0), samples.get(samples.size() - 1));
  }
}
