package local.tv.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable presentation values keep DiffUtil independent from mutable provider objects. */
final class NativeCard {
  enum Kind {
    APP,
    WATCH,
    POSTER,
    ACTION,
    RECOMMENDATION,
    INPUT
  }

  final String id, title, subtitle, art, description;
  final Kind kind;
  final Library.App app;
  final JSONObject item;
  final boolean enabled;
  final int progress;

  NativeCard(
      String id,
      String title,
      String subtitle,
      String art,
      String description,
      Kind kind,
      Library.App app,
      JSONObject item,
      boolean enabled,
      int progress) {
    this.id = id;
    this.title = title;
    this.subtitle = subtitle;
    this.art = art;
    this.description = description;
    this.kind = kind;
    this.app = app;
    this.item = item;
    this.enabled = enabled;
    this.progress = progress;
  }

  static NativeCard app(Library.App app) {
    return new NativeCard(
        "app:" + app.id,
        app.label,
        app.launch == null ? "Not installed" : "",
        "",
        "",
        Kind.APP,
        app,
        null,
        app.launch != null,
        -1);
  }

  static NativeCard program(Library.Program p, boolean watch) {
    List<String> subtitle = new ArrayList<>();
    if (watch && p.season != null && !p.season.isEmpty()) subtitle.add("Season " + p.season);
    if (watch && p.episode != null && !p.episode.isEmpty()) subtitle.add("Episode " + p.episode);
    if (p.owner != null) subtitle.add(owner(p.owner));
    int progress = -1;
    if (watch && p.duration > 0 && p.position >= 0 && p.position <= p.duration) {
      progress = (int) ((p.position / (double) p.duration) * 100);
      subtitle.add((long) Math.ceil((p.duration - p.position) / 60000d) + " min left");
    }
    if (watch && !p.resumable) subtitle.add("Open app");
    return new NativeCard(
        p.id,
        p.title,
        String.join(" · ", subtitle),
        p.art,
        p.description,
        watch ? Kind.WATCH : Kind.POSTER,
        null,
        null,
        true,
        progress);
  }

  static NativeCard recommendation(Library.Program p) {
    NativeCard card = program(p, false);
    return new NativeCard(
        card.id,
        card.title,
        card.subtitle,
        card.art,
        card.description,
        Kind.RECOMMENDATION,
        null,
        null,
        card.enabled,
        -1);
  }

  static NativeCard input(TvInputs.Entry input) {
    return new NativeCard(
        "input:" + input.id,
        input.label,
        input.portLabel.isEmpty() ? input.status : input.portLabel + " · " + input.status,
        "",
        "",
        Kind.INPUT,
        null,
        null,
        true,
        -1);
  }

  static NativeCard control(String id, String title, String subtitle) {
    return new NativeCard(
        "control:" + id, title, subtitle, "", "", Kind.INPUT, null, null, true, -1);
  }

  static NativeCard title(JSONObject item) {
    return new NativeCard(
        item.optString("key"),
        item.optString("title"),
        metadata(item),
        firstArt(item, "poster", "art"),
        item.optString("overview"),
        Kind.POSTER,
        null,
        item,
        true,
        -1);
  }

  static String firstArt(JSONObject item, String preferred, String fallback) {
    String value = item.optString(preferred);
    return value.isEmpty() ? item.optString(fallback) : value;
  }

  static String owner(String owner) {
    for (int i = 0; i < Policy.PACKAGES.length; i++)
      if (Policy.PACKAGES[i].equals(owner)) return Policy.LABELS[i];
    return owner;
  }

  static String metadata(JSONObject item) {
    List<String> values = new ArrayList<>();
    String year = item.optString("year");
    if (!year.isEmpty()) values.add(year);
    JSONArray genres = item.optJSONArray("genres");
    if (genres != null)
      for (int i = 0; i < Math.min(2, genres.length()); i++)
        if (!genres.optString(i).isEmpty()) values.add(genres.optString(i));
    return String.join(" · ", values);
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof NativeCard)) return false;
    NativeCard c = (NativeCard) other;
    return id.equals(c.id)
        && Objects.equals(title, c.title)
        && Objects.equals(subtitle, c.subtitle)
        && Objects.equals(art, c.art)
        && Objects.equals(description, c.description)
        && enabled == c.enabled
        && progress == c.progress
        && kind == c.kind
        && (app == null
            ? c.app == null
            : c.app != null && app.banner == c.app.banner && app.icon == c.app.icon);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
