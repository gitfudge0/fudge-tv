package local.tv.launcher;

import android.graphics.Color;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Only visible vertical rows and their visible cards have native view instances. */
final class NativeRowAdapter extends ListAdapter<NativeRowAdapter.Row, NativeRowAdapter.Holder> {
  static final class Row {
    final String id, title, subtitle, stateKey;
    final NativeCard.Kind kind;
    final List<NativeCard> cards;

    Row(String id, String title, String subtitle, NativeCard.Kind kind, List<NativeCard> cards) {
      this(id, title, subtitle, kind, cards, id);
    }

    Row(
        String id,
        String title,
        String subtitle,
        NativeCard.Kind kind,
        List<NativeCard> cards,
        String stateKey) {
      this.stateKey = stateKey;
      this.id = id;
      this.title = title;
      this.subtitle = subtitle;
      this.kind = kind;
      this.cards = Collections.unmodifiableList(new ArrayList<>(cards));
    }
  }

  private final NativeUi ui;
  private final NativeImages images;
  private final Consumer<NativeCard> click;
  private final View header;
  private final RecyclerView.RecycledViewPool sharedPool = new RecyclerView.RecycledViewPool();
  private boolean active = true;
  private int initialTopInset;

  void setInitialTopInset(int inset) {
    initialTopInset = inset;
  }

  private String selectedApp;
  private List<Row> requestedRows = Collections.emptyList();
  private BiConsumer<String, NativeCard> focusedCard = (row, card) -> {};

  void setOnFocusedCard(BiConsumer<String, NativeCard> listener) {
    focusedCard = listener;
  }

  void setSelectedApp(String id) {
    selectedApp = id;
    if (vertical != null)
      vertical.post(
          () -> {
            if (vertical == null) return;
            for (int i = 0; i < vertical.getChildCount(); i++) {
              RecyclerView.ViewHolder visible = vertical.getChildViewHolder(vertical.getChildAt(i));
              if (visible instanceof Holder) {
                Holder holder = (Holder) visible;
                if (holder.cards != null)
                  holder.cards.setContextSelection(
                      "apps".equals(holder.rowId) ? selectedApp : null);
              }
            }
          });
  }

  @Override
  public void submitList(List<Row> rows) {
    requestedRows = rows == null ? Collections.emptyList() : rows;
    super.submitList(rows);
  }

  private RecyclerView vertical;
  private long focusGeneration;
  private PendingFocus pendingFocus;
  private final RecyclerView.OnScrollListener scrollFocus =
      new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(@NonNull RecyclerView recycler, int dx, int dy) {
          // Reduced-motion scrollBy can offset attached children without requesting layout or
          // changing IDLE state. Retry after that actual scroll completes, not after a timer.
          if (pendingFocus != null && recycler.getScrollState() == RecyclerView.SCROLL_STATE_IDLE)
            recycler.post(NativeRowAdapter.this::applyPendingFocus);
        }

        @Override
        public void onScrollStateChanged(@NonNull RecyclerView recycler, int state) {
          if (state == RecyclerView.SCROLL_STATE_IDLE)
            recycler.post(NativeRowAdapter.this::applyPendingFocus);
        }
      };
  private final android.view.ViewTreeObserver.OnGlobalLayoutListener focusLayout =
      this::applyPendingFocus;

  private static final class PendingFocus {
    final long generation;
    final String rowId, cardId, stateKey;
    final BooleanSupplier headerFocus;

    PendingFocus(
        long generation,
        String rowId,
        String cardId,
        String stateKey,
        BooleanSupplier headerFocus) {
      this.generation = generation;
      this.rowId = rowId;
      this.cardId = cardId;
      this.headerFocus = headerFocus;
      this.stateKey = stateKey;
    }
  }

  boolean hasPendingFocus() {
    return pendingFocus != null;
  }

  void onWindowFocusAvailable() {
    applyPendingFocus();
  }

  void cancelPendingFocus() {
    focusGeneration++;
    pendingFocus = null;
    if (vertical != null) vertical.stopScroll();
  }

  @Override
  public void onAttachedToRecyclerView(@NonNull RecyclerView recycler) {
    vertical = recycler;
    recycler.addOnScrollListener(scrollFocus);
    recycler.getViewTreeObserver().addOnGlobalLayoutListener(focusLayout);
  }

  @Override
  public void onDetachedFromRecyclerView(@NonNull RecyclerView recycler) {
    cancelPendingFocus();
    recycler.removeOnScrollListener(scrollFocus);
    if (recycler.getViewTreeObserver().isAlive())
      recycler.getViewTreeObserver().removeOnGlobalLayoutListener(focusLayout);
    vertical = null;
  }

  boolean moveVertical(int direction, BooleanSupplier focusHeader) {
    if (vertical == null || !active) return false;
    int from = -1;
    if (pendingFocus != null) from = rowIndex(pendingFocus.rowId);
    else {
      View focused = vertical.findFocus();
      RecyclerView.ViewHolder holder =
          focused == null ? null : vertical.findContainingViewHolder(focused);
      if (holder != null) from = holder.getBindingAdapterPosition();
    }
    if (from < 0 && pendingFocus == null) return false;
    for (int next = from + direction; next >= 0 && next < requestedRows.size(); next += direction) {
      Row row = requestedRows.get(next);
      if (firstEnabled(row) >= 0) {
        requestRow(row);
        return true;
      }
    }
    if (direction < 0) requestHeader(focusHeader);
    return true;
  }

  void focusFirst(BooleanSupplier focusHeader) {
    for (Row row : requestedRows)
      if (firstEnabled(row) >= 0) {
        requestRow(row);
        return;
      }
    cancelPendingFocus();
  }

  private int rowIndex(String id) {
    for (int i = 0; i < getItemCount(); i++) if (getItem(i).id.equals(id)) return i;
    return -1;
  }

  private static int firstEnabled(Row row) {
    for (int i = 0; i < row.cards.size(); i++) if (row.cards.get(i).enabled) return i;
    return -1;
  }

  private void requestHeader(BooleanSupplier focusHeader) {
    cancelPendingFocus();
    focusHeader.getAsBoolean();
  }

  private NativeCard preferredCard(Row row) {
    int position = firstEnabled(row);
    if (position < 0) return null;
    RowState state = rowStates.get(row.stateKey);
    if (state != null) {
      int retainedIndex = Math.min(state.focusedIndex, row.cards.size() - 1);
      if (retainedIndex >= 0 && row.cards.get(retainedIndex).enabled) position = retainedIndex;
      for (int i = 0; i < row.cards.size(); i++)
        if (row.cards.get(i).enabled && row.cards.get(i).id.equals(state.focusedId)) position = i;
    }
    return row.cards.get(position);
  }

  private void recordPendingRow(Row row) {
    if (!active) {
      cancelPendingFocus();
      return;
    }
    NativeCard card = preferredCard(row);
    if (card == null) {
      cancelPendingFocus();
      if ("recommendations".equals(row.id))
        for (Row fallback : requestedRows)
          if ("apps".equals(fallback.id)) {
            recordPendingRow(fallback);
            break;
          }
      return;
    }
    pendingFocus = new PendingFocus(++focusGeneration, row.id, card.id, row.stateKey, null);
    if (vertical != null) vertical.stopScroll();
  }

  private void requestRow(Row row) {
    recordPendingRow(row);
    if (pendingFocus == null) return;
    moveToPending(rowIndex(row.id));
    applyPendingFocus();
  }

  private int visibilityDelta(Holder holder) {
    if (holder.row == null) return holder.itemView.getTop() - vertical.getPaddingTop();
    android.graphics.Rect bounds = new android.graphics.Rect();
    holder.itemView.getDrawingRect(bounds);
    vertical.offsetDescendantRectToMyCoords(holder.itemView, bounds);
    if (holder.getBindingAdapterPosition() == 0) bounds.top += initialTopInset;
    // The section's trailing spacing is decorative. At the end of the list it cannot
    // satisfy another bottom margin; require the heading and actual card host to fit.
    android.graphics.Rect content = new android.graphics.Rect();
    holder.row.getDrawingRect(content);
    vertical.offsetDescendantRectToMyCoords(holder.row, content);
    bounds.bottom = content.bottom;
    int top = vertical.getPaddingTop() + (vertical.canScrollVertically(-1) ? ui.px(20) : 0),
        bottom =
            vertical.getHeight()
                - vertical.getPaddingBottom()
                - (vertical.canScrollVertically(1) ? ui.px(20) : 0);
    if (bounds.top < top) return bounds.top - top;
    if (bounds.bottom > bottom) return bounds.bottom - bottom;
    return 0;
  }

  private void moveToPending(int index) {
    if (vertical == null || pendingFocus == null || index < 0) return;
    if (NativeUi.reducedMotion()) {
      RecyclerView.ViewHolder target = vertical.findViewHolderForAdapterPosition(index);
      if (target instanceof Holder) vertical.scrollBy(0, visibilityDelta((Holder) target));
      else vertical.scrollToPosition(index);
      return;
    }
    long generation = pendingFocus.generation;
    LinearSmoothScroller scroller =
        new LinearSmoothScroller(vertical.getContext()) {
          @Override
          protected float calculateSpeedPerPixel(android.util.DisplayMetrics metrics) {
            return .18f / ui.scale;
          }

          @Override
          protected int calculateTimeForDeceleration(int distance) {
            return Math.min(280, super.calculateTimeForDeceleration(distance));
          }

          @Override
          protected void updateActionForInterimTarget(RecyclerView.SmoothScroller.Action action) {
            android.graphics.PointF direction = computeScrollVectorForPosition(getTargetPosition());
            if (direction == null || direction.y == 0) {
              stop();
              return;
            }
            normalize(direction);
            mTargetVector = direction;
            LinearLayoutManager layout = (LinearLayoutManager) vertical.getLayoutManager();
            int first = layout.findFirstVisibleItemPosition();
            int count = 0, height = 0;
            for (int i = 0; i < vertical.getChildCount(); i++) {
              View child = vertical.getChildAt(i);
              int position = vertical.getChildAdapterPosition(child);
              if (position >= 0) {
                height += child.getHeight();
                count++;
              }
            }
            int average = count == 0 ? ui.px(400) : height / count;
            int distance =
                Math.max(
                    vertical.getHeight(),
                    Math.abs(getTargetPosition() - first) * average + vertical.getHeight() / 2);
            // Estimate only the seek leg. Android stops it as soon as the actual target attaches,
            // then decelerates using exact geometry; long/repeated intents stay bounded in time.
            mInterimTargetDx = 0;
            mInterimTargetDy = direction.y > 0 ? distance : -distance;
            action.update(0, mInterimTargetDy, 280, mLinearInterpolator);
          }

          @Override
          public int calculateDyToMakeVisible(View view, int snapPreference) {
            RecyclerView.ViewHolder target = vertical.findContainingViewHolder(view);
            return target instanceof Holder ? -visibilityDelta((Holder) target) : 0;
          }

          @Override
          protected void onStop() {
            super.onStop();
            // onStop runs before the final deceleration ends. The idle callback below owns
            // focus transfer, and always rechecks the current stable IDs and generation.
            if (vertical != null && pendingFocus != null && pendingFocus.generation == generation)
              vertical.post(NativeRowAdapter.this::applyPendingFocus);
          }
        };
    scroller.setTargetPosition(index);
    vertical.getLayoutManager().startSmoothScroll(scroller);
  }

  private void applyPendingFocus() {
    PendingFocus pending = pendingFocus;
    if (pending == null
        || pending.generation != focusGeneration
        || vertical == null
        || !active
        || !vertical.isShown()
        || !vertical.hasWindowFocus()
        || vertical.isComputingLayout()
        || vertical.getLayoutManager().isSmoothScrolling()
        || vertical.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) return;
    int index = rowIndex(pending.rowId);
    if (index >= 0 && !getItem(index).stateKey.equals(pending.stateKey)) return;
    if (index < 0) {
      cancelPendingFocus();
      return;
    }
    RecyclerView.ViewHolder visible = vertical.findViewHolderForAdapterPosition(index);
    if (!(visible instanceof Holder)) {
      moveToPending(index);
      return;
    }
    Holder holder = (Holder) visible;
    if (!holder.itemView.isLaidOut() || !holder.itemView.isAttachedToWindow()) return;
    if (Math.abs(visibilityDelta(holder)) > 1) {
      moveToPending(index);
      return;
    }
    if (pending.headerFocus != null) {
      boolean accepted = pending.headerFocus.getAsBoolean();
      if (accepted && pendingFocus == pending) cancelPendingFocus();
      return;
    }
    if (!pending.stateKey.equals(holder.stateKey)
        || !pending.rowId.equals(holder.rowId)
        || holder.row.isComputingLayout()
        || holder.row.hasPendingAdapterUpdates()) return;
    int cardIndex = -1;
    List<NativeCard> cards = holder.cards.getCurrentList();
    for (int i = 0; i < cards.size(); i++)
      if (cards.get(i).enabled && cards.get(i).id.equals(pending.cardId)) cardIndex = i;
    if (cardIndex < 0) return;
    RecyclerView.ViewHolder target =
        holder.row.findViewHolderForItemId(Policy.stableId(pending.cardId));
    if (target == null) {
      holder.row.scrollToPosition(cardIndex);
      return;
    }
    // Prefetched RecyclerView children can retain valid bounds while isLaidOut is false
    // immediately after attachment. Actual nonzero geometry, attached/current holders and an
    // idle, fully visible host establish readiness; requestFocus owns framework acceptance.
    if (target.itemView.getWidth() > 0
        && target.itemView.getHeight() > 0
        && target.itemView.isAttachedToWindow()
        && target.itemView.requestFocus()
        && pendingFocus == pending) cancelPendingFocus();
  }

  // State belongs to a logical row, never to a pooled view holder. Removed rows are pruned.
  private final Map<String, RowState> rowStates = new HashMap<>();

  private static final class RowState {
    Parcelable layout;
    String focusedId;
    int focusedIndex;
  }

  private static final class RowView extends RecyclerView {
    String preferredFocusId;
    Runnable layoutFocus;
    java.util.function.IntPredicate horizontalStep;

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
      if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
          && hasFocus()
          && horizontalStep != null) {
        int step =
            event.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                ? 1
                : event.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
        if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) step = -step;
        if (step != 0 && horizontalStep.test(step)) return true;
      }
      return super.dispatchKeyEvent(event);
    }

    RowView(android.content.Context context) {
      super(context);
    }

    @Override
    public void addFocusables(ArrayList<View> views, int direction, int mode) {
      if (!hasFocus() && preferredFocusId != null) {
        ViewHolder preferred = findViewHolderForItemId(Policy.stableId(preferredFocusId));
        if (preferred != null && preferred.itemView.isFocusable()) {
          views.add(preferred.itemView);
          return;
        }
      }
      super.addFocusables(views, direction, mode);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
      super.onLayout(changed, left, top, right, bottom);
      if (layoutFocus != null) layoutFocus.run();
    }
  }

  NativeRowAdapter(NativeUi ui, NativeImages images, View header, Consumer<NativeCard> click) {
    super(
        new DiffUtil.ItemCallback<Row>() {
          @Override
          public boolean areItemsTheSame(@NonNull Row a, @NonNull Row b) {
            return a.id.equals(b.id);
          }

          @Override
          public boolean areContentsTheSame(@NonNull Row a, @NonNull Row b) {
            return a.title.equals(b.title)
                && Objects.equals(a.subtitle, b.subtitle)
                && a.stateKey.equals(b.stateKey)
                && Objects.equals(a.kind, b.kind)
                && a.cards.equals(b.cards);
          }
        });
    this.ui = ui;
    this.images = images;
    this.header = header;
    this.click = click;
    setHasStableIds(true);
    setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
    for (int type = 0; type < NativeCard.Kind.values().length; type++)
      sharedPool.setMaxRecycledViews(type, 12);
  }

  @Override
  public long getItemId(int position) {
    return Policy.stableId(getItem(position).id);
  }

  @Override
  public int getItemViewType(int position) {
    return getItem(position).kind.ordinal();
  }

  @NonNull
  @Override
  public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
    LinearLayout section = new LinearLayout(parent.getContext());
    section.setOrientation(LinearLayout.VERTICAL);
    section.setClipChildren(false);
    section.setClipToPadding(false);
    section.setPadding(ui.px(80), 0, ui.px(80), ui.px(28));
    section.setLayoutParams(
        new RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    TextView title = ui.text("", 31, Color.WHITE);
    title.setPadding(ui.px(6), 0, 0, ui.px(15));
    section.addView(
        title,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    TextView subtitle = ui.text("", 18, NativeUi.MUTED);
    subtitle.setPadding(ui.px(6), 0, 0, ui.px(8));
    section.addView(
        subtitle,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    RowView row = new RowView(parent.getContext());
    row.layoutFocus = this::applyPendingFocus;
    row.setLayoutManager(
        new LinearLayoutManager(parent.getContext(), RecyclerView.HORIZONTAL, false));
    row.setRecycledViewPool(sharedPool);
    row.setItemAnimator(null);
    row.setHasFixedSize(true);
    row.setClipChildren(false);
    row.setClipToPadding(false);
    row.setPadding(ui.px(6), ui.px(12), ui.px(100), ui.px(20));
    row.setOverScrollMode(View.OVER_SCROLL_NEVER);
    row.setNestedScrollingEnabled(false);
    NativeCard.Kind kind = NativeCard.Kind.values()[type];
    NativeCardAdapter cards = new NativeCardAdapter(ui, images, kind, row, click);
    row.horizontalStep = cards::moveHorizontal;
    int height = cards.contentHeight() + row.getPaddingTop() + row.getPaddingBottom();
    section.addView(
        row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
    row.setAdapter(cards);
    TextView empty = ui.text("", 22, NativeUi.MUTED);
    empty.setPadding(ui.px(6), ui.px(20), 0, ui.px(35));
    section.addView(empty);
    Holder holder = new Holder(section, title, subtitle, empty, cards, row);
    cards.setOnFocusedCard(
        id -> {
          RowState state = rowStates.get(holder.stateKey);
          if (state != null) {
            state.focusedId = id;
            for (int i = 0; i < cards.getItemCount(); i++)
              if (cards.getCurrentList().get(i).id.equals(id)) state.focusedIndex = i;
            row.preferredFocusId = id;
          }
          for (NativeCard card : cards.getCurrentList())
            if (card.id.equals(id)) {
              focusedCard.accept(holder.rowId, card);
              break;
            }
        });
    return holder;
  }

  @Override
  public void onBindViewHolder(@NonNull Holder holder, int position) {
    Row data = getItem(position);
    if (holder.cards == null) return;
    boolean changedRow = !data.stateKey.equals(holder.stateKey);
    boolean hadFocus = holder.row.hasFocus();
    saveState(holder);
    holder.rowId = data.id;
    holder.stateKey = data.stateKey;
    long generation = ++holder.generation;
    holder.row.animate().cancel();
    holder.row.setAlpha(1);
    holder.row.setTranslationY(0);
    RowState state = rowStates.computeIfAbsent(data.stateKey, id -> new RowState());
    holder.row.preferredFocusId = state.focusedId;
    holder.itemView.setPadding(
        ui.px(80), position == 0 ? initialTopInset : 0, ui.px(80), ui.px(28));
    holder.title.setText(data.title);
    holder.subtitle.setText(data.subtitle);
    holder.subtitle.setVisibility(
        data.subtitle == null || data.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
    holder.cards.setInstant(!active);
    holder.cards.setContextSelection("apps".equals(data.id) ? selectedApp : null);
    // Preserve this row's selection through its asynchronous card diff, unless newer input
    // already targets another row. The same generation/lifecycle guards own both paths.
    if (hadFocus && pendingFocus == null) {
      if (changedRow && "recommendations".equals(data.id)) {
        for (Row fallback : requestedRows)
          if ("apps".equals(fallback.id)) recordPendingRow(fallback);
      } else recordPendingRow(data);
    }
    holder.cards.submitList(
        data.cards,
        () -> {
          if (holder.generation != generation || !data.id.equals(holder.rowId)) return;
          applyPendingFocus();
          LinearLayoutManager layout = (LinearLayoutManager) holder.row.getLayoutManager();
          if (changedRow) {
            if (state.layout == null) layout.scrollToPositionWithOffset(0, 0);
            else layout.onRestoreInstanceState(state.layout);
          }
          if (changedRow
              && "recommendations".equals(data.id)
              && active
              && !NativeUi.reducedMotion()
              && !data.cards.isEmpty()) {
            holder.row.setAlpha(0);
            holder.row.setTranslationY(ui.px(6));
            holder.row.animate().alpha(1).translationY(0).setDuration(140).start();
          }
        });
    boolean explained = data.subtitle != null && !data.subtitle.isEmpty();
    holder.empty.setVisibility(data.cards.isEmpty() && !explained ? View.VISIBLE : View.GONE);
    holder.empty.setText(R.string.empty_items);
    // The horizontal RecyclerView is the cards adapter's attached host; empty rows reserve no
    // artwork space.
    ViewGroup group = (ViewGroup) holder.itemView;
    group.getChildAt(2).setVisibility(data.cards.isEmpty() ? View.GONE : View.VISIBLE);
  }

  private void saveState(Holder holder) {
    if (holder.row == null || holder.rowId == null) return;
    RowState state = rowStates.get(holder.stateKey);
    if (state != null && holder.row.getLayoutManager() != null)
      state.layout = holder.row.getLayoutManager().onSaveInstanceState();
  }

  @Override
  public void onViewDetachedFromWindow(@NonNull Holder holder) {
    saveState(holder);
  }

  @Override
  public void onViewRecycled(@NonNull Holder holder) {
    saveState(holder);
    holder.generation++;
    holder.rowId = null;
    if (holder.row != null) {
      holder.row.animate().cancel();
      holder.row.setAlpha(1);
      holder.row.setTranslationY(0);
      holder.row.preferredFocusId = null;
    }
  }

  @Override
  public void onCurrentListChanged(@NonNull List<Row> previous, @NonNull List<Row> current) {
    // Adapter positions may change during a motion; cancel its numeric destination and
    // resolve the retained logical destination again after this commit's layout.
    if (pendingFocus != null && vertical != null) {
      vertical.stopScroll();
      vertical.post(this::applyPendingFocus);
    }
    Set<String> retained = new HashSet<>();
    for (Row row : requestedRows) retained.add(row.stateKey);
    // Retain per-app recommendation positions while switching the contextual rail.
    for (Row row : requestedRows)
      if ("apps".equals(row.id))
        for (NativeCard card : row.cards)
          if (card.app != null) retained.add("recommendations:" + card.app.id);
    rowStates.keySet().retainAll(retained);
    if (pendingFocus != null) {
      Row desired = null;
      for (Row row : requestedRows) if (row.id.equals(pendingFocus.rowId)) desired = row;
      if (desired == null) cancelPendingFocus();
      else if (pendingFocus.headerFocus == null) {
        boolean enabled = false;
        for (NativeCard card : desired.cards)
          if (card.enabled && card.id.equals(pendingFocus.cardId)) enabled = true;
        if (!enabled || !desired.stateKey.equals(pendingFocus.stateKey)) recordPendingRow(desired);
      }
    }
  }

  void setActive(boolean active) {
    this.active = active;
    if (!active) cancelPendingFocus();
  }

  static final class Holder extends RecyclerView.ViewHolder {
    final TextView title, subtitle, empty;
    final NativeCardAdapter cards;
    final RowView row;
    String rowId, stateKey;
    long generation;

    Holder(
        View view,
        TextView title,
        TextView subtitle,
        TextView empty,
        NativeCardAdapter cards,
        RowView row) {
      super(view);
      this.title = title;
      this.subtitle = subtitle;
      this.empty = empty;
      this.cards = cards;
      this.row = row;
    }
  }
}
