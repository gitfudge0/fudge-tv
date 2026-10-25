package local.tv.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.tv.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.*;
import org.json.*;

/** Native presentation; provider reads and remote metadata stay off the UI thread. */
public class MainActivity extends Activity implements NativeHomeView.Listener {
  private final Handler main = new Handler(Looper.getMainLooper());
  private final LatestRequests libraryExecutor = new LatestRequests(1);
  private final LatestRequests network = new LatestRequests(2);
  private final Map<String, Library.Program> programs = new HashMap<>();
  private final Map<String, Library.App> apps = new HashMap<>();
  private Library.Snapshot snapshot = new Library.Snapshot();
  private NativeHomeView home;
  private NativeImages images;
  private CatalogClient catalog;
  private AppSearch appSearch;
  private final Map<String, Library.Program> searchPrograms = new HashMap<>();
  private boolean resumed;
  private int libraryGeneration, catalogGeneration, searchGeneration, detailsGeneration;
  private String pendingQuery;
  private JSONObject pendingDetail;
  private CancellationSignal libraryCancellation;
  private final RecommendationRefresh recommendationRefresh =
      new RecommendationRefresh(
          new RecommendationRefresh.Scheduler() {
            public void schedule(Runnable task, long delay) {
              main.postDelayed(task, delay);
            }

            public void cancel(Runnable task) {
              main.removeCallbacks(task);
            }
          },
          this::registerRecommendationObservers,
          this::unregisterRecommendationObservers,
          () -> {
            if (resumed) refreshLibrary();
          });
  private final ContentObserver recommendationObserver =
      new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
          scheduleRecommendationRefresh();
        }
      };
  private final BroadcastReceiver packageChanges =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          scheduleRecommendationRefresh();
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    images = new NativeImages(this);
    catalog = createCatalog();
    appSearch = new AppSearch(this);
    home = new NativeHomeView(this, images, this);
    setContentView(home);
  }

  protected CatalogClient createCatalog() {
    return new CatalogClient(this);
  }

  private void scheduleRecommendationRefresh() {
    recommendationRefresh.signal();
  }

  private void registerRecommendationObservers() {
    for (Uri uri :
        new Uri[] {
          TvContract.Channels.CONTENT_URI,
          TvContract.PreviewPrograms.CONTENT_URI,
          TvContract.WatchNextPrograms.CONTENT_URI
        }) {
      try {
        getContentResolver().registerContentObserver(uri, true, recommendationObserver);
      } catch (RuntimeException ignored) {
      }
    }
    IntentFilter filter = new IntentFilter();
    for (String action :
        new String[] {
          Intent.ACTION_PACKAGE_ADDED,
          Intent.ACTION_PACKAGE_REMOVED,
          Intent.ACTION_PACKAGE_REPLACED,
          Intent.ACTION_PACKAGE_CHANGED
        }) filter.addAction(action);
    filter.addDataScheme("package");
    try {
      if (Build.VERSION.SDK_INT >= 33)
        registerReceiver(packageChanges, filter, Context.RECEIVER_NOT_EXPORTED);
      else registerReceiver(packageChanges, filter);
    } catch (RuntimeException ignored) {
    }
  }

  private void unregisterRecommendationObservers() {
    try {
      getContentResolver().unregisterContentObserver(recommendationObserver);
    } catch (RuntimeException ignored) {
    }
    try {
      unregisterReceiver(packageChanges);
    } catch (IllegalArgumentException ignored) {
    }
  }

  private void cancelLibrary() {
    if (libraryCancellation != null) libraryCancellation.cancel();
    libraryExecutor.cancel(LatestRequests.Lane.LIBRARY);
  }

  private void refreshLibrary() {
    cancelLibrary();
    final int generation = ++libraryGeneration;
    final CancellationSignal cancellation = new CancellationSignal();
    libraryCancellation = cancellation;
    libraryExecutor.submit(
        LatestRequests.Lane.LIBRARY,
        () -> {
          Library.Snapshot next;
          try {
            next = Library.load(getApplicationContext(), cancellation);
          } catch (OperationCanceledException ignored) {
            return;
          }
          if (Thread.currentThread().isInterrupted()) return;
          main.post(
              () -> {
                if (!resumed || generation != libraryGeneration) return;
                snapshot = next;
                programs.clear();
                apps.clear();
                for (Library.Program program : next.programs) programs.put(program.id, program);
                for (Library.Channel channel : next.channels)
                  for (Library.Program program : channel.programs)
                    programs.put(program.id, program);
                for (Library.App app : next.allApps) apps.put(app.id, app);
                for (Library.App app : next.apps) apps.put(app.id, app);
                home.renderSnapshot(next);
                if (pendingQuery != null) loadSearch(pendingQuery);
              });
        });
  }

  private void loadHome() {
    final int generation = ++catalogGeneration;
    network.submit(
        LatestRequests.Lane.HOME,
        () -> {
          JSONObject cached = catalog.cachedHome();
          if (cached != null) deliverHome(cached, generation);
          if (cached == null || cached.optBoolean("stale")) {
            JSONObject result = catalog.home();
            if (!Thread.currentThread().isInterrupted()) deliverHome(result, generation);
          }
        });
  }

  private void deliverHome(JSONObject result, int generation) {
    main.post(
        () -> {
          if (resumed && generation == catalogGeneration) home.renderCatalog(result);
        });
  }

  @Override
  public void onSearch(String query) {
    pendingQuery = NetworkPolicy.query(query);
    loadSearch(pendingQuery);
  }

  private void loadSearch(String query) {
    final int generation = ++searchGeneration;
    searchPrograms.clear();
    appSearch.search(
        query,
        new ArrayList<>(snapshot.allApps),
        (results, finished) -> {
          if (!resumed || generation != searchGeneration) return;
          searchPrograms.clear();
          for (Library.Program result : results) searchPrograms.put(result.id, result);
          home.renderAppSearch(results, finished);
        });
    network.submit(
        LatestRequests.Lane.SEARCH,
        () -> {
          JSONObject result = catalog.search(query);
          if (Thread.currentThread().isInterrupted()) return;
          main.post(
              () -> {
                if (!resumed || generation != searchGeneration) return;
                home.renderSearch(result);
              });
        });
  }

  @Override
  public void onCancelSearch() {
    pendingQuery = null;
    searchGeneration++;
    appSearch.cancel();
    searchPrograms.clear();
    network.cancel(LatestRequests.Lane.SEARCH);
  }

  @Override
  public void onDetails(JSONObject item) {
    if (!validTitle(item)) return;
    pendingDetail = item;
    loadDetails(item);
  }

  private void loadDetails(JSONObject item) {
    final int generation = ++detailsGeneration;
    String type = item.optString("mediaType"), id = item.optString("imdbId");
    network.submit(
        LatestRequests.Lane.DETAILS,
        () -> {
          JSONObject result = catalog.details(type, id);
          JSONObject detail = result.optJSONObject("item");
          if (detail != null) detail.remove("episodes");
          if (Thread.currentThread().isInterrupted()) return;
          main.post(
              () -> {
                if (!resumed || generation != detailsGeneration) return;
                pendingDetail = null;
                if (validTitle(detail)) home.showDetails(detail);
                else toast(result.optString("message", "Details unavailable."));
              });
        });
  }

  @Override
  public void onCancelDetails() {
    pendingDetail = null;
    detailsGeneration++;
    network.cancel(LatestRequests.Lane.DETAILS);
  }

  private static boolean validTitle(JSONObject item) {
    if (item == null) return false;
    String type = item.optString("mediaType"), id = item.optString("imdbId");
    return NetworkPolicy.validType(type)
        && NetworkPolicy.validId(id)
        && (type + ":" + id).equals(item.optString("key"));
  }

  @Override
  public void onOpenTitle(JSONObject item) {
    if (!validTitle(item)) {
      toast("Invalid title.");
      return;
    }
    if (!StremioIntegration.open(this, item.optString("mediaType"), item.optString("imdbId")))
      toast("Install Stremio or enable its title links to open this title.");
  }

  @Override
  public void onResumeProgram(String id) {
    Library.Program program =
        searchPrograms.containsKey(id) ? searchPrograms.get(id) : programs.get(id);
    if (program != null) launchRecommendation(program);
  }

  @Override
  public void onLaunchApp(String packageName) {
    Library.App app = apps.get(packageName);
    if (app != null
        && Policy.safeUserApp(
            app.id,
            String.valueOf(app.launch == null ? null : app.launch.getComponent()),
            app.label)) launch(app.launch, app.id);
  }

  @Override
  public void onAllApps() {
    allApps();
  }

  @Override
  public void onRefresh() {
    refreshLibrary();
    loadHome();
  }

  private void launchRecommendation(Library.Program program) {
    try {
      getPackageManager().getApplicationInfo(program.owner, 0);
    } catch (PackageManager.NameNotFoundException missing) {
      toast("This app is no longer installed.");
      scheduleRecommendationRefresh();
      return;
    }
    try {
      if (program.launch == null) throw new ActivityNotFoundException();
      startActivity(new Intent(program.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      recordLaunch(program.owner);
      return;
    } catch (ActivityNotFoundException | SecurityException ignored) {
    }
    launch(getPackageManager().getLeanbackLaunchIntentForPackage(program.owner), program.owner);
    scheduleRecommendationRefresh();
  }

  private void launch(Intent intent, String owner) {
    if (intent == null) {
      toast("This app is not installed.");
      return;
    }
    try {
      startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      recordLaunch(owner);
    } catch (ActivityNotFoundException | SecurityException e) {
      toast("Unable to open this item. Open the app directly.");
    }
  }

  private void recordLaunch(String owner) {
    getSharedPreferences("history", MODE_PRIVATE)
        .edit()
        .putLong(owner, System.currentTimeMillis())
        .apply();
  }

  @Override
  public void onControls() {
    String[] labels = {
      "Inputs / Live TV",
      "Wi-Fi settings",
      "TV settings",
      "TV listings permission",
      "Usage access",
      "All installed TV apps",
      "About Fudge Launcher",
      "Recommendations"
    };
    new AlertDialog.Builder(this)
        .setTitle("TV controls")
        .setItems(
            labels,
            (dialog, which) -> {
              switch (which) {
                case 0:
                  inputs();
                  break;
                case 1:
                  openSystem(new Intent(Settings.ACTION_WIFI_SETTINGS));
                  break;
                case 2:
                  openSystem(new Intent(Settings.ACTION_SETTINGS));
                  break;
                case 3:
                  requestListings();
                  break;
                case 4:
                  usageSettings();
                  break;
                case 5:
                  allApps();
                  break;
                case 7:
                  recommendationSettings();
                  break;
                case 6:
                  new AlertDialog.Builder(this)
                      .setTitle("Fudge Launcher 2.0")
                      .setMessage(
                          "Trusted local presentation. Real shared Continue Watching and"
                              + " installed-app launches. Live discovery by Cinemeta. No"
                              + " geolocation, analytics or simulated playback. Title details open"
                              + " in Stremio.")
                      .setPositiveButton("Close", null)
                      .show();
                  break;
              }
            })
        .setNegativeButton("Close", null)
        .show();
  }

  private void recommendationSettings() {
    android.content.SharedPreferences prefs = getSharedPreferences("recommendations", MODE_PRIVATE);
    List<String> labels = new ArrayList<>();
    List<Integer> actions = new ArrayList<>();
    labels.add("Recommendations: " + (prefs.getBoolean("enabled", true) ? "On" : "Off"));
    actions.add(0);
    if (snapshot.providerAvailable) {
      if (!"unavailable".equals(snapshot.watchNextCapability)) {
        labels.add("Continue Watching: " + (prefs.getBoolean("watchNext", true) ? "On" : "Off"));
        actions.add(1);
      }
      if (!snapshot.recommendationChannels.isEmpty() || !snapshot.programs.isEmpty()) {
        labels.add("Maximum items per row: " + prefs.getInt("maxItems", 20));
        actions.add(2);
      }
      if (!snapshot.recommendationChannels.isEmpty()) {
        labels.add("Visible channels");
        actions.add(3);
      }
      if (snapshot.recommendationChannels.size() > 1) {
        labels.add("Reorder rows");
        actions.add(4);
      }
    }
    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.VERTICAL);
    int padding = Math.round(24 * getResources().getDisplayMetrics().density);
    header.setPadding(padding, padding, padding, padding / 2);
    TextView title = new TextView(this);
    title.setTextAppearance(android.R.style.TextAppearance_Material_Title);
    title.setText(R.string.recommendations_settings_title);
    title.setAccessibilityHeading(true);
    header.addView(title);
    TextView explanation = new TextView(this);
    explanation.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
    explanation.setPadding(0, padding / 2, 0, 0);
    explanation.setText(R.string.recommendations_settings_access_explanation);
    header.addView(explanation);
    new AlertDialog.Builder(this)
        .setCustomTitle(header)
        .setItems(
            labels.toArray(new String[0]),
            (dialog, selected) -> {
              int index = actions.get(selected);
              if (index < 2) {
                String key = index == 0 ? "enabled" : "watchNext";
                prefs.edit().putBoolean(key, !prefs.getBoolean(key, true)).apply();
                refreshLibrary();
                recommendationSettings();
              } else if (index == 2) {
                String[] values = {"10", "20", "30", "40"};
                new AlertDialog.Builder(this)
                    .setTitle("Maximum items per row")
                    .setItems(
                        values,
                        (d, i) -> {
                          prefs.edit().putInt("maxItems", Integer.parseInt(values[i])).apply();
                          refreshLibrary();
                        })
                    .show();
              } else if (index == 3) recommendationChannels(false);
              else recommendationChannels(true);
            })
        .setNegativeButton("Close", null)
        .show();
  }

  private void recommendationChannels(boolean reorder) {
    List<Library.Channel> channels = snapshot.recommendationChannels;
    if (channels.isEmpty()) {
      toast(snapshot.channelStatus);
      return;
    }
    android.content.SharedPreferences prefs = getSharedPreferences("recommendations", MODE_PRIVATE);
    String[] labels = new String[channels.size()];
    boolean[] checked = new boolean[channels.size()];
    Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden", Collections.emptySet()));
    for (int i = 0; i < channels.size(); i++) {
      Library.Channel row = channels.get(i);
      labels[i] = row.title + " · " + row.owner;
      checked[i] = !hidden.contains(row.id);
    }
    AlertDialog.Builder builder =
        new AlertDialog.Builder(this)
            .setTitle(reorder ? "Move a row to the top" : "Visible channels");
    if (reorder)
      builder.setItems(
          labels,
          (dialog, index) -> {
            List<String> order =
                new ArrayList<>(Arrays.asList(prefs.getString("order", "").split(",")));
            order.remove("");
            order.remove(channels.get(index).id);
            order.add(0, channels.get(index).id);
            prefs.edit().putString("order", android.text.TextUtils.join(",", order)).apply();
            refreshLibrary();
          });
    else
      builder
          .setMultiChoiceItems(
              labels,
              checked,
              (dialog, index, visible) -> {
                if (visible) hidden.remove(channels.get(index).id);
                else hidden.add(channels.get(index).id);
              })
          .setPositiveButton(
              "Save",
              (dialog, index) -> {
                prefs.edit().putStringSet("hidden", hidden).apply();
                refreshLibrary();
              });
    builder.setNegativeButton("Close", null).show();
  }

  private void allApps() {
    List<Library.App> choices = new ArrayList<>();
    List<String> labels = new ArrayList<>();
    for (Library.App a : snapshot.allApps)
      if (a.launch != null
          && Policy.safeUserApp(a.id, String.valueOf(a.launch.getComponent()), a.label)) {
        choices.add(a);
        labels.add(a.label);
      }
    new AlertDialog.Builder(this)
        .setTitle("Installed TV apps")
        .setItems(
            labels.toArray(new String[0]),
            (dialog, which) -> {
              Library.App a = choices.get(which);
              launch(a.launch, a.id);
            })
        .setNegativeButton("Close", null)
        .show();
  }

  private void requestListings() {
    if (checkSelfPermission("android.permission.READ_TV_LISTINGS")
        == PackageManager.PERMISSION_GRANTED) {
      refreshLibrary();
      toast("TV listings already allowed.");
    } else requestPermissions(new String[] {"android.permission.READ_TV_LISTINGS"}, 44);
  }

  private void usageSettings() {
    Intent intent =
        new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(Uri.parse("package:" + getPackageName()));
    if (intent.resolveActivity(getPackageManager()) == null) intent.setData(null);
    openSystem(intent);
  }

  private void inputs() {
    try {
      TvInputManager manager = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
      List<TvInputInfo> available = new ArrayList<>();
      List<String> labels = new ArrayList<>();
      if (manager != null)
        for (TvInputInfo input : manager.getTvInputList())
          if (input.isPassthroughInput() && !input.isHidden(this)) {
            available.add(input);
            labels.add(input.loadLabel(this).toString());
          }
      labels.add("Live TV / system input menu");
      new AlertDialog.Builder(this)
          .setTitle("Inputs")
          .setItems(
              labels.toArray(new String[0]),
              (dialog, which) -> {
                if (which >= available.size()) {
                  systemInputs();
                  return;
                }
                try {
                  Intent intent =
                      new Intent(
                          Intent.ACTION_VIEW,
                          TvContract.buildChannelUriForPassthroughInput(
                              available.get(which).getId()));
                  if (intent.resolveActivity(getPackageManager()) == null) systemInputs();
                  else startActivity(intent);
                } catch (SecurityException | ActivityNotFoundException e) {
                  systemInputs();
                }
              })
          .setNegativeButton("Close", null)
          .show();
    } catch (SecurityException e) {
      systemInputs();
    }
  }

  private void systemInputs() {
    Intent menu = new Intent("android.intent.action.TV_INPUTS");
    if (menu.resolveActivity(getPackageManager()) != null) openSystem(menu);
    else {
      toast("Use INPUT on the Sony remote, or Inputs in TV settings.");
      openSystem(new Intent(Settings.ACTION_SETTINGS));
    }
  }

  private void openSystem(Intent intent) {
    try {
      startActivity(intent);
    } catch (ActivityNotFoundException | SecurityException e) {
      toast("This TV does not provide that settings screen.");
    }
  }

  private void toast(String value) {
    Toast.makeText(this, value, Toast.LENGTH_LONG).show();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (Intent.ACTION_MAIN.equals(intent.getAction()) && intent.hasCategory(Intent.CATEGORY_HOME))
      home.showHome();
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    recommendationRefresh.start();
    home.setActive(true);
    refreshLibrary();
    loadHome();
    if (pendingQuery != null) loadSearch(pendingQuery);
    if (pendingDetail != null) loadDetails(pendingDetail);
  }

  @Override
  protected void onPause() {
    resumed = false;
    home.setActive(false);
    appSearch.cancel();
    network.cancelAll();
    recommendationRefresh.stop();
    cancelLibrary();
    libraryGeneration++;
    catalogGeneration++;
    searchGeneration++;
    detailsGeneration++;
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    recommendationRefresh.stop();
    cancelLibrary();
    home.release();
    libraryExecutor.close();
    network.close();
    catalog.close();
    appSearch.close();
    images.close();
    main.removeCallbacksAndMessages(null);
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    home.handleBack();
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(code, permissions, results);
    if (code == 44) refreshLibrary();
  }
}
