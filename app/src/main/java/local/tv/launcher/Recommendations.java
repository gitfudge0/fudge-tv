package local.tv.launcher;

import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import java.util.*;

/** Reads only records actually exposed to this UID. Home status grants no assumed access. */
final class Recommendations {
  private static final int MAX_CHANNELS = 64, MAX_VISIBLE_ROWS = 12, MAX_SCAN = 5000;

  private Recommendations() {}

  interface Source {
    boolean available();

    Cursor query(Uri uri, CancellationSignal signal);
  }

  static void load(Context context, Library.Snapshot out, CancellationSignal signal) {
    load(
        context,
        out,
        signal,
        new Source() {
          public boolean available() {
            return context.getPackageManager().resolveContentProvider(TvContract.AUTHORITY, 0)
                != null;
          }

          public Cursor query(Uri uri, CancellationSignal cancellation) {
            return context.getContentResolver().query(uri, null, null, null, null, cancellation);
          }
        });
  }

  static void load(
      Context context, Library.Snapshot out, CancellationSignal signal, Source source) {
    SharedPreferences prefs = context.getSharedPreferences("recommendations", Context.MODE_PRIVATE);
    if (!prefs.getBoolean("enabled", true)) {
      out.channelCapability = out.watchNextCapability = "disabled";
      return;
    }
    try {
      if (!source.available()) {
        out.channelStatus =
            out.programStatus = "This device does not expose a TV recommendation provider.";
        return;
      }
    } catch (SecurityException denied) {
      out.channelCapability = out.watchNextCapability = "restricted";
      out.channelStatus =
          out.programStatus = "TV provider discovery is restricted for this launcher.";
      return;
    } catch (RuntimeException unavailable) {
      out.channelStatus =
          out.programStatus = "The TV recommendation provider is temporarily unavailable.";
      return;
    }
    out.providerAvailable = true;
    Set<String> owners = new HashSet<>();
    for (Library.App app : out.allApps) owners.add(app.id);
    int limit = RecommendationPolicy.limit(prefs.getInt("maxItems", 20));
    readChannels(context, out, signal, owners, limit, prefs, source);
    if (prefs.getBoolean("watchNext", true))
      readWatchNext(context, out, signal, owners, limit, source);
    else out.watchNextCapability = "disabled";
  }

  private static Cursor query(Source source, Uri uri, CancellationSignal signal) {
    if (signal != null) signal.throwIfCanceled();
    // Null projection tolerates OEM schemas lacking optional columns. Cursor windows remain
    // bounded.
    return source.query(uri, signal);
  }

  private static void readChannels(
      Context c,
      Library.Snapshot out,
      CancellationSignal signal,
      Set<String> owners,
      int limit,
      SharedPreferences prefs,
      Source source) {
    try (Cursor cursor = query(source, TvContract.Channels.CONTENT_URI, signal)) {
      if (cursor == null) {
        out.channelStatus = "The TV provider returned no channel cursor.";
        return;
      }
      int scanned = 0;
      while (cursor.moveToNext()
          && scanned++ < MAX_SCAN
          && out.recommendationChannels.size() < MAX_CHANNELS) {
        check(signal);
        try {
          long id = number(cursor, "_id", -1);
          String owner = text(cursor, "package_name"), title = text(cursor, "display_name");
          if (id < 0
              || !owners.contains(owner)
              || !"TYPE_PREVIEW".equals(text(cursor, "type"))
              || !RecommendationPolicy.validTitle(title)) continue;
          Library.Channel row = new Library.Channel();
          row.numericId = id;
          row.owner = owner;
          row.title = title.trim();
          row.id = "channel:" + owner + ":" + id;
          row.logo = TvContract.buildChannelLogoUri(id).toString();
          row.browsable = number(cursor, "browsable", 0) != 0;
          row.systemChannelKey = text(cursor, "system_channel_key");
          out.recommendationChannels.add(row);
        } catch (OperationCanceledException canceled) {
          throw canceled;
        } catch (RuntimeException malformed) {
          /* isolate malformed records */
        }
      }
      out.channelCapability = "empty";
    } catch (OperationCanceledException canceled) {
      throw canceled;
    } catch (SecurityException denied) {
      out.channelCapability = "restricted";
      out.channelStatus = "TV provider access is restricted for this launcher.";
      return;
    } catch (RuntimeException unavailable) {
      out.channelStatus = "Preview channels are temporarily unavailable.";
      return;
    }
    Set<String> hidden = prefs.getStringSet("hidden", Collections.emptySet());
    String order = prefs.getString("order", "");
    out.recommendationChannels.sort(
        (a, b) -> {
          int rank =
              Integer.compare(
                  RecommendationPolicy.rowRank(a.id, order),
                  RecommendationPolicy.rowRank(b.id, order));
          return rank != 0 ? rank : a.id.compareTo(b.id);
        });
    boolean restricted = false, failed = false;
    for (Library.Channel row : out.recommendationChannels) {
      if (out.channels.size() >= MAX_VISIBLE_ROWS) break;
      if (!row.browsable || hidden.contains(row.id)) continue;
      try (Cursor cursor =
          query(source, TvContract.buildPreviewProgramsUriForChannel(row.numericId), signal)) {
        if (cursor == null) {
          failed = true;
          continue;
        }
        List<Library.Program> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int scanned = 0;
        while (cursor.moveToNext() && scanned++ < MAX_SCAN) {
          check(signal);
          try {
            if (number(cursor, "channel_id", -1) != row.numericId
                || number(cursor, "browsable", 1) == 0) continue;
            Library.Program p = program(c, cursor, owners, false);
            if (p == null || !row.owner.equals(p.owner) || !seen.add(contentKey(cursor, p)))
              continue;
            p.channelId = row.id;
            candidates.add(p);
            // Bound memory while retaining the highest-priority programs encountered.
            if (candidates.size() > limit * 2) {
              sortPrograms(candidates);
              candidates.subList(limit, candidates.size()).clear();
            }
          } catch (OperationCanceledException canceled) {
            throw canceled;
          } catch (RuntimeException malformed) {
            /* skip record */
          }
        }
        sortPrograms(candidates);
        if (candidates.size() > limit) candidates.subList(limit, candidates.size()).clear();
        row.programs.addAll(candidates);
        if (!row.programs.isEmpty()) out.channels.add(row);
      } catch (OperationCanceledException canceled) {
        throw canceled;
      } catch (SecurityException denied) {
        restricted = true;
      } catch (RuntimeException unavailable) {
        failed = true;
      }
    }
    out.channelCapability =
        !out.channels.isEmpty()
            ? "available"
            : restricted ? "restricted" : failed ? "unavailable" : "empty";
    out.channelStatus =
        !out.channels.isEmpty()
            ? "Published by your installed apps"
            : restricted
                ? "TV provider access is restricted for this launcher."
                : failed
                    ? "Some preview channels are temporarily unavailable."
                    : "Your apps have not shared accessible visible preview programs.";
  }

  private static void sortPrograms(List<Library.Program> programs) {
    programs.sort(
        (a, b) -> {
          int weight = Integer.compare(b.weight, a.weight);
          return weight != 0 ? weight : a.id.compareTo(b.id);
        });
  }

  private static void readWatchNext(
      Context c,
      Library.Snapshot out,
      CancellationSignal signal,
      Set<String> owners,
      int limit,
      Source source) {
    try (Cursor cursor = query(source, TvContract.WatchNextPrograms.CONTENT_URI, signal)) {
      if (cursor == null) {
        out.programStatus = "Continue Watching is temporarily unavailable.";
        return;
      }
      int scanned = 0;
      Set<String> seen = new HashSet<>();
      while (cursor.moveToNext() && scanned++ < MAX_SCAN) {
        check(signal);
        try {
          if (number(cursor, "browsable", 1) == 0
              || number(cursor, "watch_next_type", -1)
                  != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE) continue;
          Library.Program p = program(c, cursor, owners, true);
          if (p == null || !seen.add(contentKey(cursor, p))) continue;
          out.programs.add(p);
          if (out.programs.size() > limit * 2) {
            out.programs.sort((a, b) -> Long.compare(b.engagement, a.engagement));
            out.programs.subList(limit, out.programs.size()).clear();
          }
        } catch (OperationCanceledException canceled) {
          throw canceled;
        } catch (RuntimeException malformed) {
          /* skip record */
        }
      }
      out.programs.sort(
          (a, b) -> {
            int time = Long.compare(b.engagement, a.engagement);
            return time != 0 ? time : a.id.compareTo(b.id);
          });
      if (out.programs.size() > limit) out.programs.subList(limit, out.programs.size()).clear();
      out.watchNextCapability = out.programs.isEmpty() ? "empty" : "available";
      out.programStatus =
          out.programs.isEmpty()
              ? "Your apps have not shared accessible Continue Watching items."
              : "Continue Watching from your apps";
    } catch (OperationCanceledException canceled) {
      throw canceled;
    } catch (SecurityException denied) {
      out.watchNextCapability = "restricted";
      out.programStatus = "Continue Watching access is restricted by this TV provider.";
    } catch (RuntimeException unavailable) {
      out.programStatus = "Continue Watching is temporarily unavailable.";
    }
  }

  static Library.Program program(Context c, Cursor cursor, Set<String> owners, boolean watchNext) {
    String owner = text(cursor, "package_name"), title = text(cursor, "title");
    long id = number(cursor, "_id", -1);
    if (id < 0 || !owners.contains(owner) || !RecommendationPolicy.validTitle(title)) return null;
    Intent link = validatedLink(c, text(cursor, "intent_uri"), owner);
    Intent fallback = c.getPackageManager().getLeanbackLaunchIntentForPackage(owner);
    if (link == null && fallback == null) return null;
    Library.Program p = new Library.Program();
    p.id = (watchNext ? "watchnext:" : "preview:") + owner + ":" + id;
    p.owner = owner;
    p.title = title.trim();
    p.description = text(cursor, "short_description");
    p.art = RecommendationPolicy.artwork(text(cursor, "poster_art_uri"));
    boolean thumbnail = p.art == null;
    if (thumbnail) p.art = RecommendationPolicy.artwork(text(cursor, "thumbnail_uri"));
    p.season = text(cursor, "season_display_number");
    p.episode = text(cursor, "episode_display_number");
    p.launch = link == null ? fallback : link;
    p.contentType = (int) number(cursor, "type", -1);
    p.aspectRatio =
        (int) number(cursor, thumbnail ? "thumbnail_aspect_ratio" : "poster_art_aspect_ratio", -1);
    p.weight = (int) number(cursor, "weight", 0);
    p.updated = number(cursor, "last_updated_time_utc_millis", 0);
    p.engagement = Math.max(0, number(cursor, "last_engagement_time_utc_millis", 0));
    p.duration = Math.max(0, number(cursor, "duration_millis", 0));
    long position = number(cursor, "last_playback_position_millis", -1);
    p.position =
        watchNext && RecommendationPolicy.validProgress(position, p.duration) ? position : -1;
    p.portrait = p.aspectRatio == TvContract.PreviewPrograms.ASPECT_RATIO_2_3;
    p.resumable = watchNext && link != null;
    return p;
  }

  private static String contentKey(Cursor cursor, Library.Program program) {
    String link = text(cursor, "intent_uri");
    return link == null || link.trim().isEmpty() ? program.id : program.owner + ":" + link;
  }

  static Intent sanitizedLink(String raw, String owner) {
    if (raw == null || raw.length() > 16384 || owner == null) return null;
    try {
      Intent parsed = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME);
      String component =
          parsed.getComponent() == null ? null : parsed.getComponent().getPackageName();
      if (!RecommendationPolicy.ownerMatches(owner, parsed.getPackage(), component)
          || parsed.getSelector() != null
          || (parsed.getAction() != null && !Intent.ACTION_VIEW.equals(parsed.getAction())))
        return null;
      Uri data = parsed.getData();
      if (data == null || !Policy.linkSchemeAllowed(data.getScheme())) return null;
      return new Intent(Intent.ACTION_VIEW, data)
          .setPackage(owner)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    } catch (Exception malformed) {
      return null;
    }
  }

  static Intent validatedLink(Context c, String raw, String owner) {
    if (raw == null || raw.length() > 16384 || owner == null) return null;
    try {
      if (c.getPackageManager().getLeanbackLaunchIntentForPackage(owner) == null) return null;
      Intent safe = sanitizedLink(raw, owner);
      if (safe == null) return null;
      ResolveInfo resolved =
          c.getPackageManager().resolveActivity(safe, PackageManager.MATCH_DEFAULT_ONLY);
      if (resolved == null
          || resolved.activityInfo == null
          || !resolved.activityInfo.exported
          || !resolved.activityInfo.enabled
          || !owner.equals(resolved.activityInfo.packageName)) return null;
      if (resolved.activityInfo.permission != null
          && c.checkSelfPermission(resolved.activityInfo.permission)
              != PackageManager.PERMISSION_GRANTED) return null;
      return safe.setComponent(new ComponentName(owner, resolved.activityInfo.name));
    } catch (Exception malformed) {
      return null;
    }
  }

  private static String text(Cursor cursor, String column) {
    int i = cursor.getColumnIndex(column);
    return i < 0 || cursor.isNull(i) ? null : cursor.getString(i);
  }

  private static long number(Cursor cursor, String column, long fallback) {
    try {
      int i = cursor.getColumnIndex(column);
      return i < 0 || cursor.isNull(i) ? fallback : cursor.getLong(i);
    } catch (RuntimeException malformed) {
      return fallback;
    }
  }

  private static void check(CancellationSignal signal) {
    if (signal != null) signal.throwIfCanceled();
  }
}
