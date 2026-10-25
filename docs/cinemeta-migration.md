# Cinemeta provider migration

## Inspected provider contract

Verified against the official `https://v3-cinemeta.strem.io/manifest.json` (version 3.0.14) on 2026-10-25, with live movie and series catalog/search/detail responses. `movie` and `series` are supported; IDs use the IMDb `tt` prefix. `top` is **Popular**, accepting `search`, `genre`, `skip`; `imdbRating` is **Featured**, accepting `genre`, `skip`. `year` is **New**, requiring a year via `genre`. Last/calendar-video catalogs require explicit series IDs; these are not personalized recommendation sources. The public edge required an explicit client User-Agent for catalog requests in verification, so the existing transport sends `FudgeTV/2.0`.

## Feature migration map

| Existing boundary/feature | Replacement and behavior |
| --- | --- |
| `CatalogClient` authenticated v3 API, generated credential | Public Cinemeta HTTPS requests using existing `SecureHttp`, no keys or fallback provider |
| Numeric `movie`/`tv` identity | Canonical `movie:tt…` / `series:tt…`, validated at bridge, lookup and Stremio boundaries |
| Weekly trending discovery rail | Interleaved Popular movie and series catalogs, 24 items; UI explicitly says Popular, not trending or personalized |
| Hard-coded featured IDs + metadata calls | Interleaved official Featured catalogs, four gallery entries, no per-card detail requests |
| Multi-search including person known-for | Movie and series `top/search={encodedQuery}.json`, merged and deduplicated; person-specific discovery is unavailable |
| Genre-name search | Verified manifest names mapped to `top/genre={encodedGenre}.json`; optional genre failure preserves successful title results |
| Search presentation | Existing modal/cards/remote navigation retained; launchable TV apps searched locally immediately, remote titles debounced 320ms |
| Details + credits | `/meta/{movie|series}/{imdbId}.json`; optional missing values remain empty/absent |
| Regional JustWatch offers/settings | No Cinemeta equivalent. Removed availability claims/region control; existing unrelated saved preferences remain untouched |
| Provider image path concatenation | Complete verified HTTPS image URLs, strict host/path checks; existing native memory/disk image pipeline |
| Continue Watching metadata enrichment | Existing conservative title matcher uses Cinemeta, `series` for episodic records; only a unique exact-title match with a sparse synopsis triggers a cached detail lookup by IMDb ID. Original provider identity/resume intent remains authoritative |
| Generic title app chooser | Reusable `StremioIntegration`, `ACTION_VIEW`, dynamically discovered registered `stremio` handlers; exact detail links, `autoPlay=false`, graceful missing-handler message |
| Metadata cache | New `cinemeta_metadata_v1` private cache namespace, existing SHA-256 filenames, one-hour freshness, stale offline fallback and 8MiB oldest-entry eviction budget |
| Build configuration / assets / attribution | No credential file, token, generated field or legacy provider logo; Cinemeta notices replace obsolete attribution |

## Metadata mapping

`id` (or existing `imdb_id`) → string ID, IMDb ID and canonical key; `type` must match the requested resource. `name` → title; `description` → overview; `poster` / `background` → validated poster/backdrop; `releaseInfo` / `year` / `released` → year/range and release date. `imdbRating` → optional finite 0–10 rating. Runtime strings are retained and integer minutes mapped when explicitly formatted in minutes. `genres` or `genre`, cast names, director and writer lists are retained without inventing roles or photos.

Series `videos` preserve valid matching `tt…:season:episode` identities, season/episode numbers, titles, descriptions, release dates and validated thumbnails; seasons derive from those episodes. No arbitrary episode-count truncation is applied. Response bounds are 1MiB per catalog and 5MiB per detail (long-running real series exceed 1MiB), within the existing transport limit. The existing details screen has no episode-list UI; full normalized episodes stay in the native cached details, while the bridge omits unused episode arrays and catalog summaries strip videos before normalization. Valid responses above the explicit 5MiB limit fail gracefully and can use cached data.

Observed art hosts: `images.metahub.space`, `episodes.metahub.space`, `m.media-amazon.com`. The official add-on returns HTTP307 for non-search catalogs to `https://cinemeta-catalogs.strem.io/{catalog}/catalog/{type}/{catalog}…`; the transport permits this one verified HTTPS handoff only when the exact original catalog path is preserved. All other API redirects, repeated hops and weather redirects are refused. Some episode-thumbnail URLs redirect to the retired image provider and are deliberately unavailable: the image loader rejects that hop and uses its existing graceful image failure state. Generic provider artwork remains supported for unrelated apps, but exact retired-provider domains/subdomains are rejected at every image redirect hop so no legacy artwork can reintroduce those network calls.

## Persisted identity and user-data preservation

Inspection found no launcher favorites, watchlist, continue-progress database or saved metadata references. `titles` is transient; the former numeric catalog IDs lived only in disposable `catalog_metadata` cached envelopes. User history uses app-package keys; recommendation visibility/order uses provider/channel identity; watch progress and resume links come from the Android TV provider. These remain unchanged.

The old metadata directory is preserved untouched and never read as Cinemeta data; it is neither renamed nor rewritten. Its numeric IDs have no trustworthy IMDb cross-reference in the old normalized model, and Cinemeta has no supported numeric-ID lookup resource, so no speculative title match or unauthenticated legacy API fallback is used. Existing user-owned provider records are never discarded. If a future importer handles numeric-ID-only user records, it must retain unresolved records until a verified IMDb cross-reference or explicit user selection is available. Existing IMDb IDs already work directly. The retired secret preference is removed; unrelated old region preference is left in place but unused.

## Reliability and validation boundaries

Networking/parsing remains on the existing bounded executor. Requests deduplicate via the compatible cache, no authentication refresh/dependency is introduced. Source failures preserve independent successful catalogs/search results, mark partial output, and avoid caching incomplete envelopes. Fully unavailable providers return stale cached data or an honest error. Local app search does not depend on network completion. Search edits and modal close invalidate stale results and cancel native work; requests and lifecycle generations suppress late delivery. Transport checks interruption between bounded reads; an in-flight socket can take up to its existing timeout to finish. D-pad keys survive asynchronous result replacement and a removed focused result falls back to the search field.

JVM tests cover fresh-cache request suppression, stale offline fallback, error states, legacy cache isolation, partial-source failures, disk budgets, URL/ID/type boundaries, query escaping/endpoints, genre aliases, exact Stremio links, sparse/rich metadata mapping, episode identity, deduplication and heavy catalog summary reduction. An executable Node harness covers offline app results, remote debounce/stale suppression, deduplication, result focus restoration/removal and query/modal cancellation. A separate executable enrichment harness verifies unique sparse-title followup, no repeated lookup loop, failure retention, wrong identity/title/key suppression and episodic ambiguity. Device navigation, missing-handler/offline behavior, build/lint and full test evidence must be reported from the final validated revision; this document does not itself claim those checks passed.
