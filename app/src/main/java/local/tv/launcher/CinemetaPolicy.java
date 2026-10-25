package local.tv.launcher;

import java.net.URLEncoder;

/** Verified Cinemeta manifest contracts, independent of Android and JSON. */
public final class CinemetaPolicy {
  private CinemetaPolicy() {}

  public static String encode(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    } catch (Exception invalid) {
      throw new IllegalArgumentException("Invalid query");
    }
  }

  public static String catalogPath(String type, String catalog, String extra) {
    if (!NetworkPolicy.validType(type)
        || !("top".equals(catalog) || "imdbRating".equals(catalog))) {
      throw new IllegalArgumentException("Invalid catalog");
    }
    return "/catalog/" + type + "/" + catalog + (extra.isEmpty() ? "" : "/" + extra) + ".json";
  }

  public static String metaPath(String type, String id) {
    if (!NetworkPolicy.validType(type) || !NetworkPolicy.validId(id)) {
      throw new IllegalArgumentException("Invalid title");
    }
    return "/meta/" + type + "/" + id + ".json";
  }

  public static String detailUri(String type, String id) {
    if (!NetworkPolicy.validType(type) || !NetworkPolicy.validId(id)) {
      throw new IllegalArgumentException("Invalid title");
    }
    return "stremio:///detail/"
        + type
        + "/"
        + id
        + ("movie".equals(type) ? "/" + id : "")
        + "?autoPlay=false";
  }

  public static String genre(String query, String type) {
    String[] genres = {
      "Action",
      "Adventure",
      "Animation",
      "Biography",
      "Comedy",
      "Crime",
      "Documentary",
      "Drama",
      "Family",
      "Fantasy",
      "History",
      "Horror",
      "Mystery",
      "Romance",
      "Sci-Fi",
      "Sport",
      "Thriller",
      "War",
      "Western",
      "Reality-TV",
      "Talk-Show",
      "Game-Show"
    };
    for (int index = 0; index < genres.length; index++) {
      if ((index < 19 || "series".equals(type))
          && NetworkPolicy.genreMatches(query, genres[index])) {
        return genres[index];
      }
    }
    return "";
  }
}
