package local.tv.launcher;

import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Public Android searchable providers only; no access to Google's private search catalog. */
final class AppSearch implements AutoCloseable {
  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  // A provider ignoring cancellation can occupy a slot, but cannot create unbounded threads or
  // queue work indefinitely, nor block the separate Cinemeta executor.
  private final ThreadPoolExecutor providers =
      new ThreadPoolExecutor(
          0, 6, 10, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> new Thread(r, "app-search"));
  private final ExecutorService cancellation =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(32),
          new ThreadPoolExecutor.DiscardPolicy());
  private final ThreadPoolExecutor discovery =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(1),
          new ThreadPoolExecutor.DiscardOldestPolicy());
  private Request current;

  static final class Request {
    final List<Library.Program> items = new ArrayList<>();
    final List<CancellationSignal> signals = new ArrayList<>();
    final List<Future<?>> futures = new ArrayList<>();
    volatile boolean canceled;

    synchronized List<Library.Program> snapshot() {
      return new ArrayList<>(items);
    }
  }

  AppSearch(Context context) {
    this.context = context.getApplicationContext();
  }

  void cancel() {
    Request old = current;
    current = null;
    if (old == null) return;
    old.canceled = true;
    // CancellationSignal.cancel may call an unresponsive remote provider. Never call on UI.
    synchronized (old) {
      for (Future<?> future : old.futures) future.cancel(true);
      for (CancellationSignal signal : old.signals) {
        try {
          cancellation.execute(signal::cancel);
        } catch (RejectedExecutionException ignored) {
        }
      }
    }
  }

  void search(
      String query, List<Library.App> apps, BiConsumer<List<Library.Program>, Boolean> callback) {
    cancel();
    Request request = new Request();
    current = request;
    // Publish whatever completed at the deadline. Late results are ignored.
    main.postDelayed(
        () -> {
          if (current != request || request.canceled) return;
          callback.accept(request.snapshot(), true);
          cancel();
        },
        1800);
    discovery.execute(
        () -> {
          PackageManager pm = context.getPackageManager();
          SearchManager manager = (SearchManager) context.getSystemService(Context.SEARCH_SERVICE);
          List<SearchableInfo> global = Collections.emptyList();
          try {
            if (manager != null && manager.getSearchablesInGlobalSearch() != null)
              global = manager.getSearchablesInGlobalSearch();
          } catch (RuntimeException ignored) {
          }
          int count = 0;
          for (Library.App app : apps) {
            if (request.canceled || count++ >= 32) break;
            try {
              Set<ComponentName> seen = new HashSet<>();
              List<ResolveInfo> activities =
                  pm.queryIntentActivities(new Intent(Intent.ACTION_SEARCH).setPackage(app.id), 0);
              // Searchable metadata may live on the TV launch activity without an ACTION_SEARCH
              // filter.
              if (app.launch != null && app.launch.getComponent() != null) {
                ResolveInfo launch = new ResolveInfo();
                launch.activityInfo = pm.getActivityInfo(app.launch.getComponent(), 0);
                activities = new ArrayList<>(activities);
                activities.add(launch);
              }
              activities = new ArrayList<>(activities);
              for (SearchableInfo searchable : global)
                if (app.id.equals(searchable.getSearchActivity().getPackageName())) {
                  ResolveInfo extra = new ResolveInfo();
                  extra.activityInfo = pm.getActivityInfo(searchable.getSearchActivity(), 0);
                  activities.add(extra);
                }
              int activityCount = 0;
              for (ResolveInfo info : activities) {
                if (request.canceled || activityCount++ >= 8) break;
                ActivityInfo activity = info.activityInfo;
                if (!accessible(context, activity, app.id)) continue;
                ComponentName component = new ComponentName(app.id, activity.name);
                if (!seen.add(component)) continue;
                SearchableInfo searchable =
                    manager == null ? null : manager.getSearchableInfo(component);
                boolean declaresSearch = false;
                for (ResolveInfo match :
                    pm.queryIntentActivities(
                        new Intent(Intent.ACTION_SEARCH).setPackage(app.id), 0))
                  if (match.activityInfo != null && activity.name.equals(match.activityInfo.name))
                    declaresSearch = true;
                if (searchable == null && !declaresSearch) continue;
                Library.Program handoff = new Library.Program();
                handoff.id = "appsearch:handoff:" + component.flattenToString();
                handoff.owner = app.id;
                handoff.title = "Search in " + app.label;
                handoff.description = query;
                handoff.searchHandoff = true;
                handoff.launch =
                    new Intent(Intent.ACTION_SEARCH)
                        .setComponent(component)
                        .putExtra(SearchManager.QUERY, query);
                synchronized (request) {
                  if (!request.canceled) request.items.add(handoff);
                }
                if (searchable != null && searchable.getSuggestAuthority() != null) {
                  CancellationSignal signal = new CancellationSignal();
                  synchronized (request) {
                    request.signals.add(signal);
                  }
                  try {
                    Future<?> future =
                        providers.submit(() -> read(request, app, searchable, query, signal));
                    synchronized (request) {
                      request.futures.add(future);
                    }
                  } catch (RejectedExecutionException ignored) {
                  }
                }
              }
            } catch (RuntimeException | PackageManager.NameNotFoundException ignored) {
            }
          }
          main.post(
              () -> {
                if (current == request && !request.canceled)
                  callback.accept(request.snapshot(), false);
              });
        });
  }

  private void read(
      Request request,
      Library.App app,
      SearchableInfo info,
      String query,
      CancellationSignal signal) {
    try {
      ProviderInfo provider =
          context.getPackageManager().resolveContentProvider(info.getSuggestAuthority(), 0);
      if (provider == null
          || !provider.exported
          || !provider.enabled
          || !app.id.equals(provider.packageName)) return;
      Uri uri =
          suggestionUri(
              info.getSuggestAuthority(), info.getSuggestPath(), info.getSuggestSelection(), query);
      String[] args = info.getSuggestSelection() == null ? null : new String[] {query};
      try (Cursor cursor =
          context
              .getContentResolver()
              .query(uri, null, info.getSuggestSelection(), args, null, signal)) {
        int scanned = 0;
        while (!request.canceled && cursor != null && scanned++ < 20 && cursor.moveToNext()) {
          try {
            Library.Program result =
                suggestion(
                    context,
                    app.id,
                    info.getSearchActivity(),
                    info.getSuggestIntentAction(),
                    info.getSuggestIntentData(),
                    cursor);
            if (result == null) continue;
            synchronized (request) {
              if (!request.canceled) request.items.add(result);
            }
          } catch (RuntimeException malformed) {
          }
        }
      }
    } catch (RuntimeException deniedOrUnavailable) {
    }
  }

  static Uri suggestionUri(String authority, String path, String selection, String query) {
    Uri.Builder builder = new Uri.Builder().scheme("content").authority(authority);
    if (path != null) builder.appendEncodedPath(path);
    builder.appendPath(SearchManager.SUGGEST_URI_PATH_QUERY);
    if (selection == null) builder.appendPath(query);
    return builder.appendQueryParameter("limit", "20").build();
  }

  static Library.Program suggestion(
      Context context,
      String owner,
      ComponentName component,
      String defaultAction,
      String defaultData,
      Cursor cursor) {
    String title = text(cursor, SearchManager.SUGGEST_COLUMN_TEXT_1);
    if (!RecommendationPolicy.validTitle(title) || !owner.equals(component.getPackageName()))
      return null;
    String action = text(cursor, SearchManager.SUGGEST_COLUMN_INTENT_ACTION);
    if (action == null) action = defaultAction == null ? Intent.ACTION_SEARCH : defaultAction;
    if (!Intent.ACTION_VIEW.equals(action) && !Intent.ACTION_SEARCH.equals(action)) return null;
    String data = text(cursor, SearchManager.SUGGEST_COLUMN_INTENT_DATA);
    if (data == null) data = defaultData;
    String dataId = text(cursor, SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID);
    if (data != null && dataId != null) data += "/" + Uri.encode(dataId);
    Intent launch = new Intent(action).setComponent(component).setPackage(owner);
    if (data != null) {
      Uri uri = Uri.parse(data);
      // SearchableInfo explicitly names the owner activity. Content URIs are legitimate here;
      // do not relax the stricter TV recommendation URI policy.
      if (!"content".equals(uri.getScheme()) && !Policy.linkSchemeAllowed(uri.getScheme()))
        return null;
      launch.setData(uri);
    }
    String query = text(cursor, SearchManager.SUGGEST_COLUMN_QUERY);
    if (query != null) launch.putExtra(SearchManager.QUERY, query);
    if (Intent.ACTION_VIEW.equals(action) && data == null) return null;
    if (Intent.ACTION_SEARCH.equals(action) && query == null) return null;
    try {
      if (!accessible(context, context.getPackageManager().getActivityInfo(component, 0), owner))
        return null;
    } catch (PackageManager.NameNotFoundException unavailable) {
      return null;
    }
    Library.Program result = new Library.Program();
    result.id = "appsearch:result:" + owner + ":" + launch.toUri(0);
    result.owner = owner;
    result.title = title.trim();
    result.description = text(cursor, SearchManager.SUGGEST_COLUMN_TEXT_2);
    result.art = RecommendationPolicy.artwork(text(cursor, SearchManager.SUGGEST_COLUMN_ICON_1));
    result.launch = launch;
    return result;
  }

  static boolean accessible(Context c, ActivityInfo activity, String owner) {
    return activity != null
        && owner.equals(activity.packageName)
        && activity.enabled
        && activity.exported
        && (activity.permission == null
            || c.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED);
  }

  private static String text(Cursor c, String column) {
    int i = c.getColumnIndex(column);
    return i < 0 || c.isNull(i) ? null : c.getString(i);
  }

  public void close() {
    cancel();
    discovery.shutdownNow();
    providers.shutdownNow();
    cancellation.shutdownNow();
    main.removeCallbacksAndMessages(null);
  }
}
