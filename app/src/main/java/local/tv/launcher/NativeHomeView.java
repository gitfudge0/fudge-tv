package local.tv.launcher;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Framework-native TV presentation: no browser, bridge, or web runtime. */
// Constructed programmatically with its image loader and event owner; never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
final class NativeHomeView extends FrameLayout {
  interface Listener {
    void onSearch(String query);

    void onCancelSearch();

    void onDetails(JSONObject item);

    void onCancelDetails();

    void onOpenTitle(JSONObject item);

    void onResumeProgram(String id);

    void onLaunchApp(String id);

    void onControls();

    void onAllApps();

    void onRefresh();
  }

  private final NativeUi ui;
  private final NativeImages images;
  private final Listener listener;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ImageView backdrop;
  private final RecyclerView homeRows, searchRows;
  private final NativeRowAdapter homeAdapter, searchAdapter;
  private final LinearLayout hero, nav, searchPage, actions, indicators;
  private final TextView heroTitle, heroMeta, heroDescription, searchStatus;
  private final Button openTitle, moreInfo, homeButton, searchButton, settingsButton;
  private final EditText searchInput;
  private Library.Snapshot snapshot = new Library.Snapshot();
  private List<JSONObject> catalog = Collections.emptyList(),
      featured = Collections.emptyList(),
      searchItems = Collections.emptyList();
  private int heroIndex;
  private String catalogMessage = "The catalog is loading. Your streaming apps remain available.";
  private String searchMessage = "", query = "", submittedQuery = "";
  private boolean active, searchVisible, searchPending, released, catalogStale;
  private Dialog dialog;
  private String dialogKind = "";
  private View dialogReturnFocus;
  private JSONObject detailItem;
  private boolean startupFocusPending = true;
  private long carouselStarted;
  private final Runnable progressTick =
      new Runnable() {
        @Override
        public void run() {
          if (!carouselEligible()) return;
          View dot = indicators.getChildAt(heroIndex);
          if (dot instanceof NativeCarouselDot)
            ((NativeCarouselDot) dot)
                .setProgress((android.os.SystemClock.uptimeMillis() - carouselStarted) / 8000f);
          main.postDelayed(this, 100);
        }
      };
  private final Runnable carousel =
      new Runnable() {
        @Override
        public void run() {
          if (!carouselEligible()) return;
          heroIndex = (heroIndex + 1) % featured.size();
          renderHero();
          scheduleCarousel();
        }
      };
  private final Runnable submitSearch = this::submitPendingSearch;

  private void submitPendingSearch() {
    if (active && searchVisible && searchPending && !query.isEmpty()) {
      submittedQuery = query;
      listener.onSearch(query);
    }
  }

  NativeHomeView(Context context, NativeImages images, Listener listener) {
    super(context);
    setId(R.id.native_home);
    this.ui = new NativeUi(context);
    this.images = images;
    this.listener = listener;
    setBackgroundColor(NativeUi.BACKGROUND);
    setClipChildren(true);
    setClipToPadding(false);
    backdrop = new ImageView(context);
    backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
    addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
    View shade = new View(context);
    shade.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] {0xe807090b, 0x55090a0c, 0x99090a0c}));
    addView(shade, new FrameLayout.LayoutParams(-1, -1));
    View fade = new View(context);
    fade.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {0x33090a0c, 0x22090a0c, NativeUi.BACKGROUND}));
    addView(fade, new FrameLayout.LayoutParams(-1, -1));

    hero = new LinearLayout(context);
    hero.setOrientation(LinearLayout.VERTICAL);
    hero.setPadding(
        ui.px(115),
        Math.max(
            0,
            Math.round(context.getResources().getDisplayMetrics().heightPixels * .45f)
                - ui.px(100)),
        ui.px(115),
        ui.px(36));
    hero.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
    TextView eyebrow = ui.text("—  THE FEATURE PRESENTATION", 17, 0xffe4d7bf);
    eyebrow.setLetterSpacing(.14f);
    hero.addView(eyebrow);
    heroTitle = ui.text("Find your next story.", 48, Color.WHITE);
    heroTitle.setId(R.id.hero_title);
    heroTitle.setTypeface(ui.font, Typeface.BOLD);
    heroTitle.setMaxLines(2);
    heroTitle.setEllipsize(TextUtils.TruncateAt.END);
    addHero(heroTitle, -2, 25);
    heroMeta = ui.text("Live discovery by Cinemeta", 22, 0xffc5c3bf);
    addHero(heroMeta, -2, 9);
    heroDescription = ui.text(catalogMessage, 24, 0xffd5d1cb);
    heroDescription.setId(R.id.hero_description);
    heroDescription.setMaxLines(2);
    heroDescription.setEllipsize(TextUtils.TruncateAt.END);
    heroDescription.setLineSpacing(0, 1.15f);
    addHero(heroDescription, ui.px(60), 23);
    actions = new LinearLayout(context);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    openTitle =
        ui.button(
            "Settings",
            () -> {
              JSONObject item = currentHero();
              if (item == null) showSettings();
              else listener.onOpenTitle(item);
            });
    moreInfo = ui.button("ⓘ  More info", () -> openDetails(currentHero()));
    openTitle.setId(R.id.hero_open);
    moreInfo.setId(R.id.hero_more);
    actions.addView(openTitle);
    LinearLayout.LayoutParams infoLayout = new LinearLayout.LayoutParams(-2, -2);
    infoLayout.leftMargin = ui.px(11);
    actions.addView(moreInfo, infoLayout);
    addHero(actions, -2, 27);
    indicators = new LinearLayout(context);
    indicators.setId(R.id.feature_selectors);
    indicators.setGravity(Gravity.CENTER_VERTICAL);
    addHero(indicators, ui.px(26), 28);
    homeRows = verticalList();
    homeRows.setId(R.id.home_rows);
    homeAdapter = new NativeRowAdapter(ui, images, hero, this::activateCard);
    homeRows.setAdapter(homeAdapter);
    // A real clipped viewport keeps previously scrolled text clear of the fixed navigation.
    // Compensating hero padding above preserves all initial design-coordinate positions.
    homeRows.setClipChildren(true);
    homeRows.setClipToPadding(true);
    FrameLayout.LayoutParams homeLayout = new FrameLayout.LayoutParams(-1, -1);
    homeLayout.topMargin = ui.px(100);
    addView(homeRows, homeLayout);

    searchPage = new LinearLayout(context);
    searchPage.setOrientation(LinearLayout.VERTICAL);
    searchPage.setPadding(0, ui.px(165), 0, 0);
    searchPage.setVisibility(GONE);
    TextView searchHeading = ui.text("Search", 48, Color.WHITE);
    searchHeading.setPadding(ui.px(115), 0, 0, ui.px(18));
    searchPage.addView(searchHeading);
    searchInput = new EditText(context);
    searchInput.setId(R.id.search_input);
    searchInput.setSingleLine(true);
    searchInput.setTypeface(ui.font);
    searchInput.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.px(30));
    searchInput.setTextColor(Color.WHITE);
    searchInput.setHintTextColor(NativeUi.MUTED);
    searchInput.setHint("Apps, movies, series…");
    searchInput.setContentDescription("Search apps and titles");
    searchInput.setBackground(ui.surface(0x18ffffff, 12));
    searchInput.setPadding(ui.px(20), ui.px(17), ui.px(20), ui.px(17));
    LinearLayout.LayoutParams inputLayout = new LinearLayout.LayoutParams(ui.px(1100), ui.px(78));
    inputLayout.leftMargin = ui.px(115);
    searchPage.addView(searchInput, inputLayout);
    searchStatus = ui.text("Enter a name to search your apps and Cinemeta.", 22, NativeUi.MUTED);
    searchStatus.setId(R.id.search_status);
    searchStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    searchStatus.setPadding(ui.px(115), ui.px(20), 0, ui.px(20));
    searchPage.addView(searchStatus);
    searchRows = verticalList();
    searchRows.setId(R.id.search_rows);
    searchAdapter = new NativeRowAdapter(ui, images, null, this::activateCard);
    searchRows.setAdapter(searchAdapter);
    searchPage.addView(searchRows, new LinearLayout.LayoutParams(-1, 0, 1));
    addView(searchPage, new FrameLayout.LayoutParams(-1, -1));
    searchInput.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {
            queryChanged(s.toString());
          }

          @Override
          public void afterTextChanged(Editable e) {}
        });
    searchInput.setOnKeyListener(
        (v, keyCode, event) -> {
          if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.getAction() == KeyEvent.ACTION_DOWN) {
            hideKeyboard();
            focusSearchNavigation();
            return true;
          }
          if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.getAction() == KeyEvent.ACTION_DOWN) {
            hideKeyboard();
            return false;
          }
          return false;
        });

    nav = new LinearLayout(context);
    nav.setOrientation(LinearLayout.HORIZONTAL);
    nav.setPadding(ui.px(5), ui.px(5), ui.px(5), ui.px(5));
    GradientDrawable capsule = ui.surface(0x88151619, 30);
    capsule.setStroke(ui.px(1), 0x1cffffff);
    nav.setBackground(capsule);
    homeButton = ui.navButton(0, "Home", this::showHome);
    searchButton = ui.navButton(1, "Search", this::showSearch);
    settingsButton = ui.navButton(2, "Settings", this::showSettings);
    for (Button button : new Button[] {homeButton, searchButton, settingsButton}) {
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.px(44), ui.px(44));
      if (nav.getChildCount() > 0) lp.leftMargin = ui.px(6);
      nav.addView(button, lp);
      button.setPadding(0, 0, 0, 0);
      button.setOnKeyListener(
          (v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                && event.getAction() == KeyEvent.ACTION_DOWN) {
              if (searchVisible) searchInput.requestFocus();
              else {
                homeRows.scrollToPosition(0);
                openTitle.requestFocus();
              }
              return true;
            }
            return false;
          });
    }
    homeButton.setId(R.id.nav_home);
    searchButton.setId(R.id.nav_search);
    settingsButton.setId(R.id.nav_settings);
    FrameLayout.LayoutParams navLayout = new FrameLayout.LayoutParams(-2, -2);
    navLayout.leftMargin = ui.px(80);
    navLayout.topMargin = ui.px(26);
    addView(nav, navLayout);
    nav.setElevation(ui.px(15));
    openTitle.setOnKeyListener((v, code, event) -> heroKey(code, event));
    moreInfo.setOnKeyListener((v, code, event) -> heroKey(code, event));
    renderHome();
    renderHero();
    updateNavigationSelection();
    getViewTreeObserver()
        .addOnGlobalLayoutListener(
            new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
              @Override
              public void onGlobalLayout() {
                tryStartupFocus();
                if (!startupFocusPending) getViewTreeObserver().removeOnGlobalLayoutListener(this);
              }
            });
    getViewTreeObserver()
        .addOnGlobalFocusChangeListener(
            (oldFocus, newFocus) -> {
              if (!searchVisible
                  && newFocus != null
                  && !homeAdapter.hasPendingFocus()
                  && (isDescendant(nav, newFocus) || isDescendant(actions, newFocus)))
                homeRows.scrollToPosition(0);
              scheduleCarousel();
            });
  }

  private void tryStartupFocus() {
    if (!startupFocusPending || released || !active || !hasWindowFocus()) return;
    if (!openTitle.isAttachedToWindow() || !openTitle.isLaidOut() || !openTitle.isShown()) return;
    // Attachment alone does not guarantee window focus or that touch-mode focus is accepted.
    if (openTitle.requestFocus()) startupFocusPending = false;
  }

  @Override
  public void onWindowFocusChanged(boolean focused) {
    super.onWindowFocusChanged(focused);
    if (focused) {
      tryStartupFocus();
      homeAdapter.onWindowFocusAvailable();
      searchAdapter.onWindowFocusAvailable();
    }
  }

  private void updateNavigationSelection() {
    boolean settings = "settings".equals(dialogKind) || "apps".equals(dialogKind);
    homeButton.setSelected(!searchVisible && !settings);
    searchButton.setSelected(searchVisible && !settings);
    settingsButton.setSelected(settings);
    for (Button button : new Button[] {homeButton, searchButton, settingsButton})
      button.setStateDescription(
          button.isSelected() ? getContext().getString(R.string.current_page) : null);
  }

  private void focusSearchNavigation() {
    searchButton.requestFocus();
  }

  private RecyclerView verticalList() {
    RecyclerView list = new RecyclerView(getContext());
    list.setLayoutManager(new LinearLayoutManager(getContext()));
    list.setItemAnimator(null);
    list.setPreserveFocusAfterLayout(true);
    list.setClipChildren(false);
    list.setClipToPadding(false);
    list.setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
    list.setOverScrollMode(OVER_SCROLL_NEVER);
    return list;
  }

  private void addHero(View view, int height, int top) {
    LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(ui.px(1100), height);
    layout.topMargin = ui.px(top);
    hero.addView(view, layout);
  }

  private boolean heroKey(int code, KeyEvent event) {
    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
    if (code == KeyEvent.KEYCODE_DPAD_UP) {
      homeButton.requestFocus();
      return true;
    }
    if (code == KeyEvent.KEYCODE_DPAD_DOWN && indicators.getChildCount() > 0) {
      indicators.getChildAt(heroIndex).requestFocus();
      return true;
    }
    return false;
  }

  private JSONObject currentHero() {
    return featured.isEmpty() ? null : featured.get(Math.min(heroIndex, featured.size() - 1));
  }

  private void renderHero() {
    JSONObject item = currentHero();
    heroTitle.setText(item == null ? "Find your next story." : item.optString("title"));
    heroMeta.setText(item == null ? "Live discovery by Cinemeta" : NativeCard.metadata(item));
    heroDescription.setText(item == null ? catalogMessage : item.optString("overview"));
    openTitle.setText(item == null ? "Settings" : "▶  Open in Stremio");
    moreInfo.setVisibility(item == null ? GONE : VISIBLE);
    if (item != null)
      images.load(
          backdrop,
          NativeCard.firstArt(item, "art", "poster"),
          getResources().getDisplayMetrics().widthPixels,
          getResources().getDisplayMetrics().heightPixels);
    int focused = -1;
    for (int i = 0; i < indicators.getChildCount(); i++)
      if (indicators.getChildAt(i).hasFocus()) focused = i;
    indicators.removeAllViews();
    for (int i = 0; i < featured.size(); i++) {
      final int index = i;
      NativeCarouselDot dot = new NativeCarouselDot(getContext(), i == heroIndex);
      dot.setOnClickListener(v -> selectHero(index));
      dot.setContentDescription("Feature " + featured.get(i).optString("title"));
      dot.setSelected(i == heroIndex);
      LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(ui.px(i == heroIndex ? 42 : 12), ui.px(12));
      lp.rightMargin = ui.px(10);
      indicators.addView(dot, lp);
      dot.setOnKeyListener(
          (v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
              openTitle.requestFocus();
              return true;
            }
            int step =
                code == KeyEvent.KEYCODE_DPAD_RIGHT
                    ? 1
                    : code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
            if (step != 0) {
              if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) step = -step;
              selectHero(Math.max(0, Math.min(featured.size() - 1, index + step)));
              indicators.getChildAt(heroIndex).requestFocus();
              return true;
            }
            return false;
          });
    }
    if (!featured.isEmpty()) {
      TextView count =
          ui.text(
              String.format(Locale.ROOT, "%02d / %02d", heroIndex + 1, featured.size()),
              17,
              0xffa4a4a8);
      count.setPadding(ui.px(12), 0, 0, 0);
      indicators.addView(count);
    }
    if (focused >= 0 && !featured.isEmpty())
      indicators.getChildAt(Math.min(focused, indicators.getChildCount() - 1)).requestFocus();
  }

  private void selectHero(int index) {
    heroIndex = index;
    renderHero();
    scheduleCarousel();
  }

  private boolean carouselEligible() {
    View focused = findFocus();
    return active
        && !released
        && !searchVisible
        && dialog == null
        && featured.size() > 1
        && !(focused != null && isDescendant(indicators, focused));
  }

  private static boolean isDescendant(View ancestor, View view) {
    for (android.view.ViewParent p = view.getParent(); p != null; p = p.getParent())
      if (p == ancestor) return true;
    return false;
  }

  private void scheduleCarousel() {
    main.removeCallbacks(carousel);
    main.removeCallbacks(progressTick);
    carouselStarted = android.os.SystemClock.uptimeMillis();
    View dot = indicators.getChildAt(heroIndex);
    if (dot instanceof NativeCarouselDot) ((NativeCarouselDot) dot).setProgress(0);
    if (carouselEligible()) {
      main.postDelayed(carousel, 8000);
      main.postDelayed(progressTick, 100);
    }
  }

  void renderSnapshot(Library.Snapshot snapshot) {
    this.snapshot = snapshot;
    renderHome();
    if (searchVisible) renderSearchRows();
  }

  void renderCatalog(JSONObject result) {
    catalog = items(result.optJSONArray("items"));
    featured = items(result.optJSONArray("featured"));
    if (featured.isEmpty())
      featured = new ArrayList<>(catalog.subList(0, Math.min(4, catalog.size())));
    if (featured.size() > 4) featured = new ArrayList<>(featured.subList(0, 4));
    catalogStale = result.optBoolean("stale");
    catalogMessage =
        result.optString(
            "message", "The catalog is unavailable. Your streaming apps remain available.");
    heroIndex = Math.min(heroIndex, Math.max(0, featured.size() - 1));
    renderHero();
    renderHome();
    scheduleCarousel();
  }

  void renderSearch(JSONObject result) {
    searchPending = false;
    searchItems = items(result.optJSONArray("items"));
    searchMessage = result.optString("message");
    renderSearchRows();
  }

  private static List<JSONObject> items(JSONArray array) {
    LinkedHashMap<String, JSONObject> unique = new LinkedHashMap<>();
    if (array != null)
      for (int i = 0; i < array.length(); i++) {
        JSONObject item = array.optJSONObject(i);
        if (item != null && !item.optString("key").isEmpty())
          unique.put(item.optString("key"), item);
      }
    return new ArrayList<>(unique.values());
  }

  private void renderHome() {
    List<NativeRowAdapter.Row> rows = new ArrayList<>();
    rows.add(
        new NativeRowAdapter.Row("hero", "", "", NativeCard.Kind.ACTION, Collections.emptyList()));
    List<NativeCard> watch = new ArrayList<>();
    for (Library.Program program : snapshot.programs) watch.add(NativeCard.program(program, true));
    rows.add(
        new NativeRowAdapter.Row(
            "watch",
            "Continue Watching",
            watch.isEmpty() ? snapshot.programStatus : "",
            NativeCard.Kind.WATCH,
            watch));
    List<NativeCard> apps = new ArrayList<>();
    for (Library.App app : snapshot.apps) apps.add(NativeCard.app(app));
    rows.add(
        new NativeRowAdapter.Row(
            "apps", "Your apps", "Most recently opened", NativeCard.Kind.APP, apps));
    for (Library.Channel channel : snapshot.channels) {
      List<NativeCard> cards = new ArrayList<>();
      for (Library.Program p : channel.programs) cards.add(NativeCard.program(p, false));
      if (!cards.isEmpty())
        rows.add(
            new NativeRowAdapter.Row(
                "channel:" + channel.id,
                channel.title,
                NativeCard.owner(channel.owner) + " · Shared by this app",
                NativeCard.Kind.POSTER,
                cards));
    }
    List<NativeCard> discovery = new ArrayList<>();
    for (JSONObject item : catalog) discovery.add(NativeCard.title(item));
    rows.add(
        new NativeRowAdapter.Row(
            "discovery",
            "Discover with Cinemeta",
            catalogStale
                ? "Previously fetched Cinemeta catalog"
                : discovery.isEmpty()
                    ? catalogMessage
                    : "Live discovery from Cinemeta. Explore popular movies and series.",
            NativeCard.Kind.POSTER,
            discovery));
    homeAdapter.submitList(rows);
  }

  private void activateCard(NativeCard card) {
    hideKeyboard();
    if (card.app != null) listener.onLaunchApp(card.app.id);
    else if (card.item != null) openDetails(card.item);
    else listener.onResumeProgram(card.id);
  }

  private void queryChanged(String value) {
    searchAdapter.cancelPendingFocus();
    main.removeCallbacks(submitSearch);
    listener.onCancelSearch();
    query = value.trim();
    submittedQuery = "";
    searchItems = Collections.emptyList();
    searchMessage = "";
    searchPending = !query.isEmpty();
    renderSearchRows();
    if (active && searchVisible && searchPending) main.postDelayed(submitSearch, 320);
  }

  private void renderSearchRows() {
    List<NativeRowAdapter.Row> rows = new ArrayList<>();
    List<NativeCard> apps = new ArrayList<>();
    if (!query.isEmpty())
      for (Library.App app : snapshot.allApps)
        if (app.launch != null
            && app.label.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
          apps.add(NativeCard.app(app));
    if (!apps.isEmpty())
      rows.add(
          new NativeRowAdapter.Row(
              "search-apps", "Apps", "Installed TV apps", NativeCard.Kind.APP, apps));
    List<NativeCard> titles = new ArrayList<>();
    for (JSONObject item : searchItems) titles.add(NativeCard.title(item));
    if (!titles.isEmpty())
      rows.add(
          new NativeRowAdapter.Row(
              "search-titles", "Movies and series", "Cinemeta", NativeCard.Kind.POSTER, titles));
    searchStatus.setText(
        query.isEmpty()
            ? "Enter a name to search your apps and Cinemeta."
            : searchPending
                ? "Searching Cinemeta…"
                : !searchMessage.isEmpty()
                    ? searchMessage
                    : rows.isEmpty() ? "No apps or titles found." : "");
    searchAdapter.submitList(rows);
  }

  void showHome() {
    cancelRowFocus();
    startupFocusPending = false;
    closeDialog(false);
    hideKeyboard();
    main.removeCallbacks(submitSearch);
    listener.onCancelSearch();
    submittedQuery = "";
    searchVisible = false;
    updateNavigationSelection();
    searchPage.setVisibility(GONE);
    homeRows.setVisibility(VISIBLE);
    homeRows.scrollToPosition(0);
    homeButton.requestFocus();
    scheduleCarousel();
  }

  void showSearch() {
    cancelRowFocus();
    startupFocusPending = false;
    closeDialog(false);
    searchVisible = true;
    updateNavigationSelection();
    homeRows.setVisibility(GONE);
    searchPage.setVisibility(VISIBLE);
    searchInput.requestFocus();
    ((InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
        .showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
    if (searchPending && active) main.postDelayed(submitSearch, 320);
    scheduleCarousel();
  }

  boolean isSearchVisible() {
    return searchVisible;
  }

  void setActive(boolean active) {
    this.active = active;
    if (active) tryStartupFocus();
    homeAdapter.setActive(active);
    searchAdapter.setActive(active);
    main.removeCallbacks(submitSearch);
    if (active && searchVisible && searchPending && submittedQuery.isEmpty())
      main.postDelayed(submitSearch, 320);
    scheduleCarousel();
  }

  void release() {
    released = true;
    main.removeCallbacksAndMessages(null);
    closeDialog(false);
    homeRows.setAdapter(null);
    searchRows.setAdapter(null);
    images.clear(backdrop);
  }

  private void hideKeyboard() {
    ((InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
  }

  private TextView detailType, detailTitle, detailMeta, detailDescription, detailCast;
  private ImageView detailArt;

  private void openDetails(JSONObject item) {
    if (item == null) return;
    showDetails(item);
    listener.onDetails(item);
  }

  void showDetails(JSONObject item) {
    detailItem = item;
    if (!"details".equals(dialogKind) || dialog == null) {
      LinearLayout content = dialogContent("details");
      detailArt = new ImageView(getContext());
      detailArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
      content.addView(detailArt, new LinearLayout.LayoutParams(-1, ui.px(330)));
      detailType = ui.text("", 17, 0xffe4d7bf);
      detailType.setPadding(0, ui.px(20), 0, 0);
      content.addView(detailType);
      detailTitle = ui.text("", 48, Color.WHITE);
      detailTitle.setPadding(0, ui.px(20), 0, ui.px(15));
      content.addView(detailTitle);
      detailMeta = ui.text("", 22, NativeUi.MUTED);
      content.addView(detailMeta);
      detailDescription = ui.text("", 24, 0xffb4b5bb);
      detailDescription.setPadding(0, ui.px(23), 0, ui.px(23));
      content.addView(detailDescription);
      detailCast = ui.text("", 22, NativeUi.MUTED);
      detailCast.setPadding(0, 0, 0, ui.px(15));
      content.addView(detailCast);
      TextView note =
          ui.text(
              "Open this title in Stremio to see details and available streams.",
              18,
              NativeUi.MUTED);
      content.addView(note);
      Button open = ui.button("Open in Stremio", () -> listener.onOpenTitle(detailItem));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.topMargin = ui.px(25);
      content.addView(open, lp);
      showDialog(open);
    }
    detailType.setText(
        getContext()
            .getString(
                R.string.media_type_source,
                getContext()
                    .getString(
                        "series".equals(item.optString("mediaType"))
                            ? R.string.media_series
                            : R.string.media_film)));
    detailTitle.setText(item.optString("title"));
    List<String> metadata = new ArrayList<>();
    String titleMetadata = NativeCard.metadata(item);
    if (!titleMetadata.isEmpty()) metadata.add(titleMetadata);
    String runtime = item.optString("runtime").trim();
    if (!runtime.isEmpty())
      metadata.add(
          runtime.matches("\\d+(?:\\.\\d+)?")
              ? getContext().getString(R.string.runtime_minutes, runtime)
              : runtime);
    String rating = item.optString("rating");
    if (!rating.isEmpty()) metadata.add(getContext().getString(R.string.imdb_rating, rating));
    detailMeta.setText(String.join(" · ", metadata));
    detailDescription.setText(
        item.optString("overview").isEmpty()
            ? "No description is currently available."
            : item.optString("overview"));
    List<String> cast = new ArrayList<>();
    JSONArray people = item.optJSONArray("cast");
    if (people != null)
      for (int i = 0; i < people.length() && cast.size() < 6; i++) {
        JSONObject person = people.optJSONObject(i);
        String name = person == null ? people.optString(i) : person.optString("name");
        if (!name.isEmpty()) cast.add(name);
      }
    detailCast.setVisibility(cast.isEmpty() ? GONE : VISIBLE);
    detailCast.setText(
        cast.isEmpty()
            ? ""
            : getContext().getString(R.string.starring_names, String.join(", ", cast)));
    images.load(detailArt, NativeCard.firstArt(item, "art", "poster"), ui.px(780), ui.px(330));
    dialog.setTitle(item.optString("title"));
  }

  void showSettings() {
    startupFocusPending = false;
    hideKeyboard();
    LinearLayout content = dialogContent("settings");
    content.addView(ui.text("MAKE YOURSELF AT HOME", 17, 0xffe4d7bf));
    TextView heading = ui.text("Your evening. Elevated.", 48, Color.WHITE);
    heading.setPadding(0, ui.px(20), 0, ui.px(20));
    content.addView(heading);
    TextView description =
        ui.text(
            "Continue Watching shows real history shared by your apps. Your apps are ordered by"
                + " most recently opened. Shared app recommendations appear below them. Discovery"
                + " and search use live Cinemeta metadata; discovery titles open in Stremio.",
            24,
            NativeUi.MUTED);
    description.setPadding(0, 0, 0, ui.px(25));
    content.addView(description);
    Button apps = ui.button("Your apps", this::showApps);
    content.addView(apps);
    addDialogAction(content, "TV controls & data access", listener::onControls);
    addDialogAction(
        content,
        "Refresh",
        () -> {
          listener.onRefresh();
        });
    TextView attribution =
        ui.text(
            "Movie and series metadata provided by Cinemeta.\n"
                + "Fudge Launcher 2.0 · Artwork and metadata belong to their respective owners.",
            18,
            NativeUi.MUTED);
    attribution.setPadding(0, ui.px(30), 0, 0);
    content.addView(attribution);
    showDialog(apps);
  }

  private void showApps() {
    LinearLayout content = dialogContent("apps");
    content.addView(ui.text("Your apps", 48, Color.WHITE));
    content.addView(
        ui.text(
            snapshot.usageEnabled
                ? "Most recently opened, including launches outside this home."
                : "Most recently opened from this home.",
            24,
            NativeUi.MUTED));
    View first = null;
    for (Library.App app : snapshot.apps) {
      Button button =
          ui.button(
              app.label + (app.launch == null ? " · Not installed" : ""),
              () -> listener.onLaunchApp(app.id));
      button.setEnabled(app.launch != null);
      addDialogButton(content, button);
      if (first == null && button.isEnabled()) first = button;
    }
    Button all = ui.button("All installed TV apps", listener::onAllApps);
    addDialogButton(content, all);
    showDialog(first == null ? all : first);
  }

  private void addDialogAction(LinearLayout content, String title, Runnable action) {
    addDialogButton(content, ui.button(title, action));
  }

  private void addDialogButton(LinearLayout content, Button button) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.topMargin = ui.px(16);
    content.addView(button, lp);
  }

  private LinearLayout dialogContent(String kind) {
    cancelRowFocus();
    View restore = dialog == null ? findFocus() : dialogReturnFocus;
    closeDialog(false);
    dialogReturnFocus = restore;
    dialogKind = kind;
    updateNavigationSelection();
    dialog = new Dialog(getContext());
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    ScrollView scroll = new ScrollView(getContext());
    scroll.setFillViewport(false);
    LinearLayout content = new LinearLayout(getContext());
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(ui.px(35), ui.px(30), ui.px(35), ui.px(35));
    content.setBackground(ui.surface(0xff17191d, 22));
    Button close = ui.button("✕", () -> closeDialog(true));
    close.setContentDescription("Close");
    close.setPadding(0, 0, 0, 0);
    LinearLayout.LayoutParams closeLayout = new LinearLayout.LayoutParams(ui.px(50), ui.px(50));
    closeLayout.gravity = Gravity.END;
    content.addView(close, closeLayout);
    scroll.addView(content);
    dialog.setContentView(scroll);
    dialog.setOnKeyListener(
        (d, code, event) -> {
          if (code == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            if ("apps".equals(dialogKind)) showSettings();
            else closeDialog(true);
            return true;
          }
          return false;
        });
    dialog.setOnCancelListener(d -> closeDialog(true));
    scheduleCarousel();
    return content;
  }

  private void showDialog(View first) {
    dialog.show();
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawableResource(android.R.color.transparent);
      window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      window.setDimAmount(.7f);
      window.setLayout(
          ui.px(850), Math.round(getResources().getDisplayMetrics().heightPixels * .9f));
    }
    first.requestFocus();
    scheduleCarousel();
  }

  private void closeDialog(boolean restore) {
    if (dialog == null) return;
    if ("details".equals(dialogKind)) {
      listener.onCancelDetails();
      if (detailArt != null) images.clear(detailArt);
    }
    Dialog closing = dialog;
    dialog = null;
    dialogKind = "";
    updateNavigationSelection();
    closing.setOnCancelListener(null);
    closing.dismiss();
    if (restore
        && dialogReturnFocus != null
        && dialogReturnFocus.isAttachedToWindow()
        && dialogReturnFocus.isFocusable()) dialogReturnFocus.requestFocus();
    else if (restore) {
      if (searchVisible) searchInput.requestFocus();
      else openTitle.requestFocus();
    }
    dialogReturnFocus = null;
    scheduleCarousel();
  }

  boolean handleBack() {
    cancelRowFocus();
    hideKeyboard();
    if (dialog != null) {
      closeDialog(true);
      return true;
    }
    if (searchVisible && !searchButton.hasFocus()) {
      searchButton.requestFocus();
      return true;
    }
    if (!searchVisible && !homeButton.hasFocus()) {
      homeRows.scrollToPosition(0);
      homeButton.requestFocus();
      return true;
    }
    return false;
  }

  private void cancelRowFocus() {
    homeAdapter.cancelPendingFocus();
    searchAdapter.cancelPendingFocus();
  }

  private boolean focusHomeHeader() {
    View target = featured.isEmpty() ? openTitle : indicators.getChildAt(heroIndex);
    return target != null && target.requestFocus();
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    if (event.getAction() == KeyEvent.ACTION_DOWN) {
      startupFocusPending = false;
      scheduleCarousel();
      int key = event.getKeyCode();
      if (dialog == null
          && (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN)) {
        int direction = key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
        View focused = findFocus();
        if (searchVisible) {
          if (searchAdapter.hasPendingFocus()
              && searchAdapter.moveVertical(direction, () -> searchInput.requestFocus()))
            return true;
          if (direction > 0 && focused == searchInput) {
            hideKeyboard();
            searchAdapter.focusFirst(() -> searchInput.requestFocus());
            return true;
          }
          if (searchAdapter.hasPendingFocus()
              || focused != null && isDescendant(searchRows, focused))
            if (searchAdapter.moveVertical(direction, () -> searchInput.requestFocus()))
              return true;
        } else {
          if (homeAdapter.hasPendingFocus()
              && homeAdapter.moveVertical(direction, this::focusHomeHeader)) return true;
          if (direction > 0
              && focused != null
              && (isDescendant(indicators, focused)
                  || featured.isEmpty() && isDescendant(actions, focused))) {
            homeAdapter.focusFirst(this::focusHomeHeader);
            return true;
          }
          if (homeAdapter.hasPendingFocus()
              || focused != null && isDescendant(homeRows, focused) && !isDescendant(hero, focused))
            if (homeAdapter.moveVertical(direction, this::focusHomeHeader)) return true;
        }
      } else cancelRowFocus();
    }
    return super.dispatchKeyEvent(event);
  }

  @Override
  public boolean dispatchTouchEvent(android.view.MotionEvent event) {
    if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
      startupFocusPending = false;
      cancelRowFocus();
    }
    return super.dispatchTouchEvent(event);
  }
}
