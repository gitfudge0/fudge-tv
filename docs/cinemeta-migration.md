# Cinemeta catalog contracts

## Provider and native boundaries

The official public add-on is `https://v3-cinemeta.strem.io`. The earlier provider inspection recorded manifest version 3.0.14 on 2026-10-25. `movie` and `series` resources use IMDb IDs beginning with `tt`; that inspection is historical contract evidence, not a claim that a later checkout has run the live test.

`top` supplies Popular catalogs and accepts title search/genre filters. `imdbRating` supplies Featured catalogs. Home interleaves Popular movie/series results into at most 24 entries and Featured results into four gallery entries. Exact genre searches use known manifest names. Catalog matches do not establish regional availability, subscriptions, or playable streams.

`CatalogClient` normalizes provider JSON for native views; there is no web bridge. `MainActivity` coordinates native lifecycle/request lanes, and `NativeHomeView`/RecyclerView adapters render the results. Continue Watching retains provider-owned descriptions and artwork without cross-provider enrichment. Weather/geocoding and the former web presentation are removed.

| Boundary | Current contract |
| --- | --- |
| Title identity | Canonical `movie:tt…` / `series:tt…`; type and IMDb ID validated before requests/launches |
| Search | `/catalog/{movie|series}/top/search={encodedQuery}.json`; valid empty results remain valid |
| Genre query | Known manifest genre filter; an optional failure preserves successful title results |
| Details | `/meta/{movie|series}/{imdbId}.json`; missing optional values remain absent/empty |
| Installed apps | Searched locally independently of catalog completion, including offline |
| Title launch | Exact registered `stremio` detail handler, `autoPlay=false`, graceful missing-handler result |
| Metadata storage | Private `cinemeta_metadata_v1`, SHA-256 filenames, one-hour freshness, 8 MiB oldest-entry budget |
| Authentication | Public HTTPS only; no API key, credential file, login, or alternate metadata provider |

## Mapping and network bounds

`id` (or `imdb_id`) becomes the canonical ID/key; returned type must match the requested resource. Name, description, year/release range, genres, cast, directors/writers, optional finite 0–10 rating, and explicitly formatted runtime minutes are retained without invented people, roles, or photos.

Series metadata preserves valid matching `tt…:season:episode` identities and derives seasons from them. Summary normalization skips episode lists without mutating the source object. Detail responses have a 5 MiB bound; catalogs have a 1 MiB bound. Larger responses fail gracefully and can use cached data. The native detail presentation does not claim episode playback support.

Allowed catalog art hosts are `images.metahub.space`, `episodes.metahub.space`, and `m.media-amazon.com`, with strict resource-path checks. The transport permits one HTTP 307 handoff from the official add-on to `https://cinemeta-catalogs.strem.io/{catalog}/catalog/{type}/{catalog}…` only when the original catalog path is preserved exactly. Other API redirects are refused. Generic artwork from installed-app TV Provider records uses the separate image boundary; retired metadata-provider domains are rejected at every redirect hop.

The HTTPS client sends `FudgeTV/2.0`, bounds reads, preserves interruption, sets five-second socket timeouts, and checks a shared ten-second elapsed-time budget across redirect/read operations. Platform DNS and socket behavior still govern actual interruption latency; this is not a measured end-to-end provider SLA.

## Concurrency, caching, and failures

Four bounded source workers fetch the independent Home catalogs concurrently without using the request-lane executor for child work. Caller interruption cancels unfinished source futures and removes canceled queued work. Identical concurrent lookups share one in-flight fetch, while every consumer receives a separate mutable JSON tree.

`cachedHome()` performs disk-only delivery with a truthful `stale` flag. A fresh cache ends loading; a stale envelope can render immediately while `home()` refreshes it. Source failures preserve successful independent results and mark partial output. Partial envelopes never overwrite a complete cache. A nonempty provider array with no valid normalized entries is treated as invalid data; an actual empty array remains a legitimate empty result. Total provider failure returns stale cache or an explicit error.

The activity coalesces obsolete Home/Search/Details requests in independent lanes and cancels them on pause/destruction. Generations prevent late delivery. Search edits/close cancel pending remote work while local app search stays available. `CatalogClient.close()` cancels in-flight operations and releases its source executor.

## Persisted identities and verification

The earlier numeric-ID `catalog_metadata` cache remains untouched and is never read as Cinemeta metadata. There is no trustworthy automatic numeric-to-IMDb conversion, so no speculative lookup or legacy provider fallback runs. User-owned provider records, app-use history, recommendation settings, and owner-validated resume links keep their existing identities. The retired credential preference is removed; unrelated saved preferences are preserved.

JVM tests cover cache suppression/stale fallback, malformed and partial results, true empty searches, bounded storage, URL/type/ID policy, metadata/episode mapping, concurrent completion, in-flight coalescing, JSON isolation, stale-first delivery, follower cancellation, shutdown, and cancellation/recovery. The frozen `CatalogLatencyBenchmark` measures fake-source scheduling only; see README for its measured scope. Native Android fixture tests use the unminified `verification` variant, with fixture activities declared only in the target verification app; an actual optimized release launch is a separate check. The opt-in `CinemetaLiveTest` supplies separate provider-contract evidence. No historical JavaScript checks are used to validate the native interface.
