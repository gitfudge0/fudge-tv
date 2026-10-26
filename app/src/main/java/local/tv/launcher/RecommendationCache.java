package local.tv.launcher;

import android.content.*;
import java.util.*;
import org.json.*;

/** Bounded, private stale-first copy of actual publisher records. Used on the library worker. */
final class RecommendationCache {
  private static final String FILE = "recommendation_cache";
  private static final int MAX_BYTES = 2 * 1024 * 1024;

  private RecommendationCache() {}

  static Library.Snapshot read(Context c, Library.Snapshot inventory) {
    Library.Snapshot result = Library.copy(inventory);
    try {
      String raw = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("v1", "");
      if (raw.length() > MAX_BYTES) return result;
      JSONObject json = new JSONObject(raw);
      Map<String, Library.App> owners = new HashMap<>();
      for (Library.App app : inventory.allApps)
        if (app.launch != null && RecommendationPolicy.eligibleOwner(app.id))
          owners.put(app.id, app);
      JSONArray watch = json.optJSONArray("watch");
      if (watch != null)
        for (int i = 0; i < Math.min(40, watch.length()); i++) {
          Library.Program program = program(c, watch.optJSONObject(i), owners, true);
          if (program != null) result.programs.add(program);
        }
      JSONArray channels = json.optJSONArray("channels");
      if (channels != null)
        for (int i = 0; i < Math.min(12, channels.length()); i++) {
          JSONObject row = channels.optJSONObject(i);
          if (row == null || !owners.containsKey(row.optString("owner"))) continue;
          Library.Channel channel = new Library.Channel();
          channel.owner = row.getString("owner");
          channel.id = "app:" + channel.owner;
          channel.title = owners.get(channel.owner).label;
          JSONArray programs = row.getJSONArray("programs");
          for (int j = 0; j < Math.min(40, programs.length()); j++) {
            Library.Program program = program(c, programs.optJSONObject(j), owners, false);
            if (program != null && channel.owner.equals(program.owner))
              channel.programs.add(program);
          }
          if (!channel.programs.isEmpty()) result.channels.add(channel);
        }
    } catch (JSONException | RuntimeException ignored) {
      // A truncated or old cache never prevents the provider refresh.
    }
    return result;
  }

  static Library.Snapshot reconcile(Library.Snapshot fresh, Library.Snapshot stale) {
    Library.Snapshot result = Library.copy(fresh);
    if (!"available".equals(fresh.watchNextCapability)
        && !"empty".equals(fresh.watchNextCapability)
        && !"disabled".equals(fresh.watchNextCapability)) {
      result.programs.clear();
      result.programs.addAll(stale.programs);
    }
    if (!"disabled".equals(fresh.channelCapability)) {
      Map<String, Library.Channel> rows = new LinkedHashMap<>();
      for (Library.Channel row : fresh.channels) rows.put(row.owner, cloneRow(row));
      for (Library.Channel row : stale.channels) {
        for (Library.Program program : row.programs) {
          if (fresh.channelsRead && !fresh.failedChannels.contains(program.channelId)) continue;
          Library.Channel target = rows.get(row.owner);
          if (target == null) {
            target = cloneRow(row);
            target.programs.clear();
            rows.put(row.owner, target);
          }
          boolean found = false;
          for (Library.Program existing : target.programs)
            if (existing.id.equals(program.id)) found = true;
          if (!found && target.programs.size() < 40) target.programs.add(program);
        }
      }
      result.channels.clear();
      for (Library.Channel row : rows.values())
        if (!row.programs.isEmpty() && result.channels.size() < 12) result.channels.add(row);
    }
    return result;
  }

  private static Library.Channel cloneRow(Library.Channel row) {
    Library.Channel copy = new Library.Channel();
    copy.id = row.id;
    copy.owner = row.owner;
    copy.title = row.title;
    copy.numericId = row.numericId;
    copy.logo = row.logo;
    copy.systemChannelKey = row.systemChannelKey;
    copy.browsable = row.browsable;
    copy.order = row.order;
    copy.programs.addAll(row.programs);
    return copy;
  }

  static void write(Context c, Library.Snapshot snapshot) {
    try {
      JSONObject json = new JSONObject();
      JSONArray watch = new JSONArray(), channels = new JSONArray();
      for (Library.Program p : snapshot.programs) if (watch.length() < 40) watch.put(json(p));
      for (Library.Channel row : snapshot.channels) {
        if (channels.length() >= 12) break;
        JSONArray programs = new JSONArray();
        for (Library.Program p : row.programs) if (programs.length() < 40) programs.put(json(p));
        channels.put(new JSONObject().put("owner", row.owner).put("programs", programs));
      }
      String raw = json.put("watch", watch).put("channels", channels).toString();
      if (raw.length() <= MAX_BYTES)
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("v1", raw).commit();
    } catch (JSONException | RuntimeException ignored) {
      // Disk failures must not discard the visible snapshot.
    }
  }

  private static JSONObject json(Library.Program p) throws JSONException {
    return new JSONObject()
        .put("id", p.id)
        .put("owner", p.owner)
        .put("title", p.title)
        .put("description", p.description)
        .put("art", p.art)
        .put("season", p.season)
        .put("episode", p.episode)
        .put("channel", p.channelId)
        .put("content", p.contentId)
        .put("launch", p.launch == null ? null : p.launch.toUri(Intent.URI_INTENT_SCHEME))
        .put("type", p.contentType)
        .put("aspect", p.aspectRatio)
        .put("updated", p.updated)
        .put("position", p.position)
        .put("duration", p.duration)
        .put("engagement", p.engagement)
        .put("weight", p.weight)
        .put("resumable", p.resumable);
  }

  private static Library.Program program(
      Context c, JSONObject j, Map<String, Library.App> owners, boolean watch) {
    if (j == null) return null;
    String owner = j.optString("owner"), title = j.optString("title");
    if (!owners.containsKey(owner) || !RecommendationPolicy.validTitle(title)) return null;
    Intent link = Recommendations.validatedLink(c, j.optString("launch"), owner);
    Library.Program p = new Library.Program();
    p.id = j.optString("id");
    if (!p.id.startsWith((watch ? "watchnext:" : "preview:") + owner + ":")) return null;
    p.owner = owner;
    p.title = title;
    p.launch = link == null ? owners.get(owner).launch : link;
    p.target = link == null ? null : link.getDataString();
    p.description = j.optString("description", null);
    p.art = RecommendationPolicy.artwork(j.optString("art", null));
    p.season = j.optString("season", null);
    p.episode = j.optString("episode", null);
    p.channelId = j.optString("channel", null);
    if (!watch && (p.channelId == null || !p.channelId.startsWith("channel:" + owner + ":")))
      return null;
    p.contentId = j.optString("content", null);
    p.contentType = j.optInt("type", -1);
    p.aspectRatio = j.optInt("aspect", -1);
    p.portrait = p.aspectRatio == android.media.tv.TvContract.PreviewPrograms.ASPECT_RATIO_2_3;
    p.updated = j.optLong("updated");
    p.duration = Math.max(0, j.optLong("duration"));
    p.position = j.optLong("position", -1);
    if (!watch || !RecommendationPolicy.validProgress(p.position, p.duration)) p.position = -1;
    p.engagement = Math.max(0, j.optLong("engagement"));
    p.weight = j.optInt("weight");
    p.resumable = watch && link != null && j.optBoolean("resumable");
    return p;
  }
}
