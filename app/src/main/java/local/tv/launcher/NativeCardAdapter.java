package local.tv.launcher;

import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import java.util.function.Consumer;

/** RecyclerView owns focus and recycling; focus motion changes transforms, never row layout. */
final class NativeCardAdapter extends ListAdapter<NativeCard, NativeCardAdapter.Holder> {
  private final NativeUi ui;
  private final NativeImages images;
  private final Consumer<NativeCard> click;
  private final NativeCard.Kind kind;
  private final RecyclerView row;
  private String selectedId;
  private String contextSelection;

  void setContextSelection(String id) {
    contextSelection = id;
    updateMotion();
  }

  private int selectedPosition = -1;
  private boolean instant;
  private boolean animateRemovals = true;
  private final java.util.ArrayList<ValueAnimator> removals = new java.util.ArrayList<>();
  private final java.util.ArrayList<Removal> pendingRemovals = new java.util.ArrayList<>();
  private long listGeneration;
  private android.view.ViewTreeObserver.OnPreDrawListener pendingReveal;

  private static final class Removal {
    final android.graphics.Bitmap bitmap;
    final android.graphics.drawable.BitmapDrawable drawable;

    Removal(android.graphics.Bitmap bitmap, android.graphics.drawable.BitmapDrawable drawable) {
      this.bitmap = bitmap;
      this.drawable = drawable;
    }
  }

  private void discardPendingRemovals() {
    for (Removal removal : pendingRemovals) removal.bitmap.recycle();
    pendingRemovals.clear();
  }

  private void cancelPendingReveal() {
    if (pendingReveal != null && row.getViewTreeObserver().isAlive())
      row.getViewTreeObserver().removeOnPreDrawListener(pendingReveal);
    pendingReveal = null;
  }

  void setAnimateRemovals(boolean enabled) {
    animateRemovals = enabled;
    if (!enabled) {
      cancelRemovals();
      discardPendingRemovals();
    }
  }

  @Override
  public void submitList(List<NativeCard> cards) {
    submitList(cards, null);
  }

  @Override
  public void submitList(List<NativeCard> cards, Runnable committed) {
    long generation = ++listGeneration;
    cancelPendingReveal();
    cancelRemovals();
    discardPendingRemovals();
    if (animateRemovals && !instant && !NativeUi.reducedMotion() && row.isShown()) {
      for (int i = 0; i < row.getChildCount() && pendingRemovals.size() < 4; i++) {
        RecyclerView.ViewHolder raw = row.getChildViewHolder(row.getChildAt(i));
        if (!(raw instanceof Holder)) continue;
        Holder holder = (Holder) raw;
        if (holder.card == null || holder.itemView.hasFocus()) continue;
        boolean retained = false;
        if (cards != null)
          for (NativeCard card : cards)
            if (card.id.equals(holder.card.id)) {
              retained = true;
              break;
            }
        if (retained) continue;
        View view = holder.itemView;
        if (view.getWidth() <= 0 || view.getHeight() <= 0) continue;
        android.graphics.Bitmap snapshot;
        try {
          snapshot =
              android.graphics.Bitmap.createBitmap(
                  view.getWidth(), view.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError unavailable) {
          break; // Optional exit feedback must not compete with artwork for the bitmap budget.
        }
        view.draw(new android.graphics.Canvas(snapshot));
        android.graphics.drawable.BitmapDrawable ghost =
            new android.graphics.drawable.BitmapDrawable(row.getResources(), snapshot);
        int left = Math.round(view.getLeft() + view.getTranslationX());
        ghost.setBounds(left, view.getTop(), left + view.getWidth(), view.getBottom());
        pendingRemovals.add(new Removal(snapshot, ghost));
      }
    }
    super.submitList(
        cards,
        () -> {
          if (generation != listGeneration) return;
          if (!instant && animateRemovals && !NativeUi.reducedMotion() && row.isShown()) {
            for (Removal removal : pendingRemovals) {
              row.getOverlay().add(removal.drawable);
              ValueAnimator fade = ValueAnimator.ofInt(255, 0);
              fade.setDuration(140);
              fade.addUpdateListener(
                  a -> removal.drawable.setAlpha((Integer) a.getAnimatedValue()));
              fade.addListener(
                  new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                      row.getOverlay().remove(removal.drawable);
                      removals.remove(fade);
                      removal.bitmap.recycle();
                    }
                  });
              removals.add(fade);
              fade.start();
            }
            pendingRemovals.clear();
          } else discardPendingRemovals();
          if (committed != null) committed.run();
        });
  }

  private void cancelRemovals() {
    for (ValueAnimator animator : new java.util.ArrayList<>(removals)) animator.cancel();
  }

  @Override
  public void onDetachedFromRecyclerView(@NonNull RecyclerView recycler) {
    cancelRemovals();
    discardPendingRemovals();
    cancelPendingReveal();
  }

  private Consumer<String> focusedCard = id -> {};

  void setOnFocusedCard(Consumer<String> listener) {
    focusedCard = listener;
  }

  NativeCardAdapter(
      NativeUi ui,
      NativeImages images,
      NativeCard.Kind kind,
      RecyclerView row,
      Consumer<NativeCard> click) {
    super(
        new DiffUtil.ItemCallback<NativeCard>() {
          @Override
          public boolean areItemsTheSame(@NonNull NativeCard a, @NonNull NativeCard b) {
            return a.id.equals(b.id);
          }

          @Override
          public boolean areContentsTheSame(@NonNull NativeCard a, @NonNull NativeCard b) {
            return a.equals(b);
          }
        });
    this.ui = ui;
    this.images = images;
    this.kind = kind;
    this.row = row;
    this.click = click;
    setHasStableIds(true);
    setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
  }

  @Override
  public long getItemId(int position) {
    return Policy.stableId(getItem(position).id);
  }

  @Override
  public int getItemViewType(int position) {
    return kind.ordinal();
  }

  private boolean classic() {
    return kind == NativeCard.Kind.APP || kind == NativeCard.Kind.POSTER;
  }

  int width() {
    return ui.px(
        kind == NativeCard.Kind.APP
            ? 220
            : kind == NativeCard.Kind.POSTER ? 200 : kind == NativeCard.Kind.INPUT ? 300 : 420);
  }

  int artHeight() {
    return ui.px(
        kind == NativeCard.Kind.APP
            ? 124
            : kind == NativeCard.Kind.POSTER ? 300 : kind == NativeCard.Kind.INPUT ? 105 : 236);
  }

  float focusScale() {
    return kind == NativeCard.Kind.APP
        ? 290f / 220
        : kind == NativeCard.Kind.POSTER ? 1.25f : 1.025f;
  }

  int reservedArtHeight() {
    return classic() ? Math.round(artHeight() * focusScale()) : artHeight() + ui.px(8);
  }

  int contentHeight() {
    return classic() ? reservedArtHeight() + ui.px(50 + 25 + 8) : reservedArtHeight() + ui.px(15);
  }

  private int cardStride() {
    return width() + ui.px(classic() ? 20 : 22);
  }

  private android.graphics.drawable.GradientDrawable inputSurface() {
    android.graphics.drawable.GradientDrawable surface = ui.surface(0xff21252e, 12);
    surface.setStroke(ui.px(1), 0x16ffffff);
    return surface;
  }

  @NonNull
  @Override
  public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    FrameLayout card = new FrameLayout(parent.getContext());
    card.setFocusableInTouchMode(true);
    card.setDefaultFocusHighlightEnabled(false);
    card.setClickable(true);
    card.setClipChildren(false);
    card.setClipToPadding(false);
    card.setLayoutParams(new RecyclerView.LayoutParams(cardStride(), -1));
    ImageView art = new ImageView(parent.getContext());
    art.setScaleType(
        kind == NativeCard.Kind.APP
            ? ImageView.ScaleType.FIT_CENTER
            : ImageView.ScaleType.CENTER_CROP);
    FrameLayout artwork = new FrameLayout(parent.getContext());
    artwork.setBackground(
        kind == NativeCard.Kind.INPUT
            ? inputSurface()
            : ui.surface(classic() ? 0xff17191d : 0xff20242a, classic() ? 11 : 12));
    artwork.setClipToOutline(true);
    FrameLayout.LayoutParams imageLayout = new FrameLayout.LayoutParams(-1, -1);
    if (kind == NativeCard.Kind.INPUT) {
      imageLayout = new FrameLayout.LayoutParams(ui.px(36), ui.px(36));
      imageLayout.leftMargin = ui.px(18);
      imageLayout.gravity = Gravity.CENTER_VERTICAL;
      art.setScaleType(ImageView.ScaleType.FIT_CENTER);
    }
    artwork.addView(art, imageLayout);
    FrameLayout.LayoutParams artLayout = new FrameLayout.LayoutParams(width(), artHeight());
    artLayout.topMargin = classic() ? reservedArtHeight() - artHeight() : ui.px(8);
    card.addView(artwork, artLayout);
    artwork.setPivotX(classic() ? 0 : width() / 2f);
    artwork.setPivotY(classic() ? artHeight() : artHeight() / 2f);
    if (!classic() && kind != NativeCard.Kind.INPUT) {
      View shade = new View(parent.getContext());
      shade.setBackground(
          new android.graphics.drawable.GradientDrawable(
              android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
              new int[] {0, 0x15000000, 0xcc000000}));
      artwork.addView(shade, new FrameLayout.LayoutParams(-1, -1));
    }
    TextView title =
        ui.text(
            "",
            kind == NativeCard.Kind.APP ? 21 : classic() || kind == NativeCard.Kind.INPUT ? 22 : 25,
            Color.WHITE);
    if (!classic() && kind != NativeCard.Kind.INPUT)
      title.setTypeface(ui.font, android.graphics.Typeface.BOLD);
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    FrameLayout.LayoutParams titleLayout;
    if (classic()) {
      titleLayout = new FrameLayout.LayoutParams(width(), ui.px(28));
      titleLayout.topMargin = reservedArtHeight() + ui.px(17);
      card.addView(title, titleLayout);
    } else if (kind == NativeCard.Kind.INPUT) {
      titleLayout = new FrameLayout.LayoutParams(width() - ui.px(88), -1, Gravity.START);
      titleLayout.leftMargin = ui.px(70);
      title.setGravity(Gravity.CENTER_VERTICAL);
      artwork.addView(title, titleLayout);
    } else {
      titleLayout =
          new FrameLayout.LayoutParams(
              width() - ui.px(36), ui.px(33), Gravity.BOTTOM | Gravity.START);
      titleLayout.leftMargin = ui.px(18);
      titleLayout.bottomMargin = ui.px(48);
      artwork.addView(title, titleLayout);
    }
    TextView subtitle = ui.text("", 18, classic() ? NativeUi.MUTED : 0xffdedee1);
    subtitle.setSingleLine(true);
    subtitle.setEllipsize(TextUtils.TruncateAt.END);
    FrameLayout.LayoutParams subtitleLayout;
    if (classic()) {
      subtitleLayout = new FrameLayout.LayoutParams(width(), ui.px(25));
      subtitleLayout.topMargin = reservedArtHeight() + ui.px(50);
      card.addView(subtitle, subtitleLayout);
    } else {
      subtitleLayout =
          new FrameLayout.LayoutParams(
              width() - ui.px(36), ui.px(27), Gravity.BOTTOM | Gravity.START);
      subtitleLayout.leftMargin = ui.px(18);
      subtitleLayout.bottomMargin = ui.px(18);
      artwork.addView(subtitle, subtitleLayout);
    }
    ProgressBar progress =
        new ProgressBar(parent.getContext(), null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100);
    progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffe5c89f));
    FrameLayout.LayoutParams progressLayout =
        new FrameLayout.LayoutParams(-1, ui.px(5), Gravity.BOTTOM);
    artwork.addView(progress, progressLayout);
    TextView tag = ui.text("", 15, Color.WHITE);
    tag.setBackground(ui.surface(0x77000000, 4));
    tag.setPadding(ui.px(6), ui.px(4), ui.px(6), ui.px(4));
    FrameLayout.LayoutParams tagLayout =
        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
    tagLayout.rightMargin = ui.px(12);
    tagLayout.topMargin = ui.px(12);
    artwork.addView(tag, tagLayout);
    View marker = new View(parent.getContext());
    marker.setBackground(ui.surface(0xffdbbb91, 2));
    FrameLayout.LayoutParams markerLayout = new FrameLayout.LayoutParams(ui.px(38), ui.px(4));
    markerLayout.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
    markerLayout.bottomMargin = ui.px(2);
    card.addView(marker, markerLayout);
    marker.setVisibility(View.GONE);
    return new Holder(card, artwork, art, title, subtitle, tag, progress, marker);
  }

  @Override
  public void onBindViewHolder(@NonNull Holder holder, int position) {
    NativeCard card = getItem(position);
    if (holder.revealMotion != null) holder.revealMotion.cancel();
    boolean sameItem = holder.card != null && holder.card.id.equals(card.id);
    if (!sameItem) {
      cancelContentMotion(holder);
      images.clear(holder.art);
      holder.art.setImageDrawable(null);
    }
    holder.card = card;
    holder.tag.setVisibility(card.item == null ? View.GONE : View.VISIBLE);
    if (card.item != null)
      holder.tag.setText(
          "series".equals(card.item.optString("mediaType"))
              ? R.string.media_series
              : R.string.media_film);
    holder.title.setText(card.title);
    holder.title.setVisibility(View.VISIBLE);
    holder.subtitle.setText(card.subtitle);
    holder.subtitle.setVisibility(
        kind == NativeCard.Kind.INPUT
            ? View.GONE
            : card.subtitle == null || card.subtitle.isEmpty() ? View.INVISIBLE : View.VISIBLE);
    holder.itemView.setEnabled(card.enabled);
    holder.itemView.setFocusable(card.enabled);
    holder.itemView.setAlpha(card.enabled ? 1f : .45f);
    holder.itemView.setContentDescription(
        card.title
            + ". "
            + (card.item == null ? "" : holder.tag.getText() + ". ")
            + (card.subtitle == null ? "" : card.subtitle));
    boolean animateProgress =
        sameItem
            && holder.progress.getVisibility() == View.VISIBLE
            && card.progress >= 0
            && canAnimate(holder);
    if (holder.progressMotion != null) holder.progressMotion.cancel();
    holder.progress.setVisibility(card.progress < 0 ? View.GONE : View.VISIBLE);
    if (animateProgress && holder.progress.getProgress() != card.progress) {
      holder.progressMotion =
          ValueAnimator.ofInt(holder.progress.getProgress(), Math.max(0, card.progress));
      holder.progressMotion.setDuration(200);
      holder.progressMotion.addUpdateListener(
          a -> holder.progress.setProgress((Integer) a.getAnimatedValue()));
      holder.progressMotion.start();
    } else holder.progress.setProgress(Math.max(0, card.progress));
    if (card.app != null) images.showApp(holder.art, card.app, false);
    else if (card.kind == NativeCard.Kind.INPUT) {
      images.clear(holder.art);
      holder.art.setImageDrawable(
          new NativeNavIcon(
              card.id.equals("control:system-settings")
                  ? 2
                  : card.id.equals("control:system-home") ? 0 : 3));
      holder.artwork.setBackground(inputSurface());
    } else
      images.load(holder.art, card.art, Math.round(width() * focusScale()), reservedArtHeight());
    holder.itemView.setOnClickListener(v -> click.accept(holder.card));
    holder.itemView.setOnFocusChangeListener(
        (v, focused) -> {
          if (focused) {
            selectedId = holder.card.id;
            focusedCard.accept(selectedId);
            selectedPosition = holder.getBindingAdapterPosition();
            updateMotion();
            row.post(
                () -> {
                  if (!holder.itemView.hasFocus()) return;
                  reveal(holder);
                  centerRow();
                });
          } else {
            holder.artwork.setForeground(null);
            holder.artwork.setElevation(0);
            NativeUi.move(holder.artwork, 0, 1f, instant);
            if (!row.hasFocus()) {
              selectedId = null;
              selectedPosition = -1;
              updateMotion();
            }
          }
        });
    applyMotion(holder, true);
  }

  boolean moveHorizontal(int step) {
    View focus = row.findFocus();
    RecyclerView.ViewHolder current = focus == null ? null : row.findContainingViewHolder(focus);
    if (!(current instanceof Holder)) return false;
    int from = current.getBindingAdapterPosition();
    if (from < 0) return true;
    int next = from + step;
    while (next >= 0 && next < getItemCount() && !getItem(next).enabled) next += step;
    if (next < 0 || next >= getItemCount()) return true;
    String targetId = getItem(next).id;
    row.stopScroll();
    RecyclerView.ViewHolder target = row.findViewHolderForItemId(Policy.stableId(targetId));
    if (target == null) {
      // Fill only enough native layout space to attach the next enabled neighbor. The
      // usual reveal animation then brings that exact card fully into view.
      int physicalStep = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -step : step;
      int attachDistance =
          physicalStep > 0
              ? Math.max(
                  1, current.itemView.getRight() - row.getWidth() + row.getPaddingRight() + 1)
              : Math.max(1, row.getPaddingLeft() - current.itemView.getLeft() + 1);
      attachDistance += (Math.abs(next - from) - 1) * cardStride();
      row.scrollBy(physicalStep * attachDistance, 0);
      target = row.findViewHolderForItemId(Policy.stableId(targetId));
    }
    if (target != null) target.itemView.requestFocus();
    return true;
  }

  private void centerRow() {
    android.view.ViewParent parent = row.getParent();
    while (parent != null && !(parent instanceof RecyclerView)) parent = parent.getParent();
    if (!(parent instanceof RecyclerView)) return;
    RecyclerView vertical = (RecyclerView) parent;
    int[] location = new int[2];
    row.getLocationOnScreen(location);
    int[] viewport = new int[2];
    vertical.getLocationOnScreen(viewport);
    int top = viewport[1] + vertical.getPaddingTop(),
        bottom = viewport[1] + vertical.getHeight() - vertical.getPaddingBottom();
    if (location[1] < top + ui.px(20) || location[1] + row.getHeight() > bottom - ui.px(20)) {
      int delta = location[1] - (top + bottom - row.getHeight()) / 2;
      if (instant || NativeUi.reducedMotion()) vertical.scrollBy(0, delta);
      else vertical.smoothScrollBy(0, delta);
    }
  }

  private void reveal(Holder holder) {
    if (!holder.itemView.hasFocus()) return;
    Rect rect = new Rect();
    holder.itemView.getDrawingRect(rect);
    row.offsetDescendantRectToMyCoords(holder.itemView, rect);
    int extra = Math.round(width() * (focusScale() - 1));
    boolean rtl = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    int left = rect.left - (rtl ? extra : 0), right = rect.left + width() + (rtl ? 0 : extra);
    int delta =
        left < row.getPaddingLeft()
            ? left - row.getPaddingLeft()
            : right > row.getWidth() - row.getPaddingRight()
                ? right - row.getWidth() + row.getPaddingRight()
                : 0;
    if (delta != 0) {
      if (instant || NativeUi.reducedMotion()) row.scrollBy(delta, 0);
      else row.smoothScrollBy(delta, 0);
    }
  }

  private void updateMotion() {
    for (int i = 0; i < row.getChildCount(); i++) {
      RecyclerView.ViewHolder holder = row.getChildViewHolder(row.getChildAt(i));
      if (holder instanceof Holder) applyMotion((Holder) holder, false);
    }
  }

  private void applyMotion(Holder holder, boolean immediate) {
    int position = holder.getBindingAdapterPosition();
    boolean focused = holder.itemView.hasFocus();
    float direction = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1;
    float shift =
        selectedPosition >= 0 && position > selectedPosition
            ? width() * (focusScale() - 1) * direction
            : 0;
    NativeUi.move(holder.itemView, shift, 1, immediate || instant);
    NativeUi.move(holder.artwork, 0, focused ? focusScale() : 1, immediate || instant);
    holder.artwork.setElevation(focused ? ui.px(18) : 0);
    holder.artwork.setForeground(focused && !classic() ? ui.focusOutline() : null);
    applySelection(holder, immediate);
  }

  private void applySelection(Holder holder, boolean immediate) {
    boolean selected = holder.card != null && holder.card.id.equals(contextSelection);
    holder.itemView.setSelected(selected);
    holder.itemView.setStateDescription(selected ? "Recommendations shown below" : null);
    int captionColor = selected ? 0xffe4d7bf : Color.WHITE;
    if (holder.selectionMotion != null && (immediate || instant || NativeUi.reducedMotion())) {
      holder.selectionMotion.cancel();
      holder.selectionMotion = null;
    }
    if (holder.selected != selected || immediate || instant || NativeUi.reducedMotion()) {
      if (holder.selectionMotion != null) holder.selectionMotion.cancel();
      holder.selected = selected;
      if (!immediate && canAnimate(holder)) {
        int from = holder.title.getCurrentTextColor();
        android.animation.ArgbEvaluator colors = new android.animation.ArgbEvaluator();
        float markerAlpha = holder.marker.getAlpha();
        holder.marker.setVisibility(View.VISIBLE);
        holder.selectionMotion = ValueAnimator.ofFloat(0, 1);
        holder.selectionMotion.setDuration(160);
        holder.selectionMotion.addUpdateListener(
            a -> {
              float f = (Float) a.getAnimatedValue();
              holder.title.setTextColor((Integer) colors.evaluate(f, from, captionColor));
              holder.marker.setAlpha(markerAlpha + ((selected ? 1 : 0) - markerAlpha) * f);
            });
        holder.selectionMotion.start();
      } else {
        holder.title.setTextColor(captionColor);
        holder.marker.setAlpha(selected ? 1 : 0);
      }
    }
    holder.marker.setVisibility(View.VISIBLE);
  }

  private boolean canAnimate(Holder holder) {
    return !instant
        && !NativeUi.reducedMotion()
        && holder.itemView.isAttachedToWindow()
        && holder.itemView.isShown();
  }

  private void cancelContentMotion(Holder holder) {
    if (holder.selectionMotion != null) holder.selectionMotion.cancel();
    if (holder.progressMotion != null) holder.progressMotion.cancel();
    if (holder.revealMotion != null) holder.revealMotion.cancel();
    if (holder.card != null) {
      holder.progress.setProgress(Math.max(0, holder.card.progress));
      holder.itemView.setAlpha(holder.card.enabled ? 1 : .45f);
    }
    holder.selectionMotion = null;
    holder.progressMotion = null;
    holder.marker.setAlpha(0);
  }

  @Override
  public void onViewDetachedFromWindow(@NonNull Holder holder) {
    cancelContentMotion(holder);
    holder.art.animate().cancel();
    holder.art.setAlpha(1f);
    boolean selected = holder.card != null && holder.card.id.equals(contextSelection);
    holder.marker.setAlpha(selected ? 1f : 0f);
    holder.title.setTextColor(selected ? 0xffe4d7bf : Color.WHITE);
  }

  @Override
  public void onViewAttachedToWindow(@NonNull Holder holder) {
    if (holder.card != null && holder.card.app == null && holder.card.kind != NativeCard.Kind.INPUT)
      images.load(
          holder.art, holder.card.art, Math.round(width() * focusScale()), reservedArtHeight());
    applySelection(holder, true);
  }

  void setInstant(boolean instant) {
    this.instant = instant;
    if (instant) {
      cancelRemovals();
      discardPendingRemovals();
      cancelPendingReveal();
      for (int i = 0; i < row.getChildCount(); i++) {
        RecyclerView.ViewHolder holder = row.getChildViewHolder(row.getChildAt(i));
        if (holder instanceof Holder) cancelContentMotion((Holder) holder);
      }
    }
    updateMotion();
  }

  @Override
  public void onViewRecycled(@NonNull Holder holder) {
    cancelContentMotion(holder);
    holder.itemView.animate().cancel();
    holder.artwork.animate().cancel();
    images.clear(holder.art);
    holder.itemView.setTranslationX(0);
    holder.artwork.setScaleX(1);
    holder.artwork.setScaleY(1);
    holder.itemView.setOnFocusChangeListener(null);
    holder.card = null;
  }

  @Override
  public void onCurrentListChanged(
      @NonNull List<NativeCard> previous, @NonNull List<NativeCard> current) {
    selectedPosition = -1;
    for (int i = 0; i < current.size(); i++)
      if (current.get(i).id.equals(selectedId)) selectedPosition = i;
    updateMotion();
    if (instant || NativeUi.reducedMotion()) return;
    long generation = listGeneration;
    // Adapter updates precede holder layout; resolve identities once the accepted diff is drawn.
    cancelPendingReveal();
    pendingReveal =
        new android.view.ViewTreeObserver.OnPreDrawListener() {
          @Override
          public boolean onPreDraw() {
            cancelPendingReveal();
            if (generation != listGeneration || instant || NativeUi.reducedMotion()) return true;
            revealChangedCards(previous, current);
            return true;
          }
        };
    row.getViewTreeObserver().addOnPreDrawListener(pendingReveal);
    row.invalidate();
  }

  private void revealChangedCards(List<NativeCard> previous, List<NativeCard> current) {
    for (int i = 0; i < row.getChildCount(); i++) {
      RecyclerView.ViewHolder viewHolder = row.getChildViewHolder(row.getChildAt(i));
      if (!(viewHolder instanceof Holder)) continue;
      Holder holder = (Holder) viewHolder;
      int position = holder.getBindingAdapterPosition();
      if (holder.card == null
          || position < 0
          || position >= current.size()
          || !holder.card.id.equals(current.get(position).id)
          || !canAnimate(holder)
          || holder.itemView.hasFocus()) continue;
      int oldPosition = -1;
      for (int j = 0; j < previous.size(); j++)
        if (previous.get(j).id.equals(holder.card.id)) {
          oldPosition = j;
          break;
        }
      if (oldPosition == holder.getBindingAdapterPosition()) continue;
      // Reveal only a live diff's visible inserted/moved cards, never normal scroll binds.
      if (holder.revealMotion != null) holder.revealMotion.cancel();
      float alpha = holder.card.enabled ? 1 : .45f;
      holder.revealMotion = ValueAnimator.ofFloat(alpha * .65f, alpha);
      holder.revealMotion.setDuration(160);
      holder.revealMotion.addUpdateListener(
          a -> holder.itemView.setAlpha((Float) a.getAnimatedValue()));
      holder.revealMotion.start();
    }
  }

  static final class Holder extends RecyclerView.ViewHolder {
    final FrameLayout artwork;
    final ImageView art;
    final TextView title, subtitle, tag;
    final ProgressBar progress;
    final View marker;
    NativeCard card;
    boolean selected;
    ValueAnimator selectionMotion, progressMotion, revealMotion;

    Holder(
        View view,
        FrameLayout artwork,
        ImageView art,
        TextView title,
        TextView subtitle,
        TextView tag,
        ProgressBar progress,
        View marker) {
      super(view);
      this.artwork = artwork;
      this.art = art;
      this.title = title;
      this.subtitle = subtitle;
      this.tag = tag;
      this.progress = progress;
      this.marker = marker;
    }
  }
}
