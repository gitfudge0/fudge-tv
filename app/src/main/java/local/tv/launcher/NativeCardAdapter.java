package local.tv.launcher;

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
  private int selectedPosition = -1;
  private boolean instant;
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

  int width() {
    return ui.px(kind == NativeCard.Kind.APP ? 220 : kind == NativeCard.Kind.WATCH ? 300 : 200);
  }

  int artHeight() {
    return ui.px(kind == NativeCard.Kind.APP ? 124 : kind == NativeCard.Kind.WATCH ? 169 : 300);
  }

  float focusScale() {
    return kind == NativeCard.Kind.APP
        ? 290f / 220
        : kind == NativeCard.Kind.WATCH ? 380f / 300 : 1.25f;
  }

  int reservedArtHeight() {
    return Math.round(artHeight() * focusScale());
  }

  private int subtitleTop() {
    return reservedArtHeight() + ui.px(50);
  }

  private int subtitleHeight() {
    return ui.px(kind == NativeCard.Kind.WATCH ? 50 : 25);
  }

  int contentHeight() {
    return subtitleTop() + subtitleHeight() + ui.px(8);
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
    RecyclerView.LayoutParams layout =
        new RecyclerView.LayoutParams(width() + ui.px(20), ViewGroup.LayoutParams.MATCH_PARENT);
    card.setLayoutParams(layout);
    ImageView art = new ImageView(parent.getContext());
    art.setScaleType(
        kind == NativeCard.Kind.APP
            ? ImageView.ScaleType.FIT_CENTER
            : ImageView.ScaleType.CENTER_CROP);
    FrameLayout artwork = new FrameLayout(parent.getContext());
    artwork.setBackground(ui.surface(0xff17191d, 11));
    artwork.setClipToOutline(true);
    artwork.addView(art, new FrameLayout.LayoutParams(-1, -1));
    FrameLayout.LayoutParams artLayout = new FrameLayout.LayoutParams(width(), artHeight());
    artLayout.topMargin = reservedArtHeight() - artHeight();
    card.addView(artwork, artLayout);
    artwork.setPivotX(0);
    artwork.setPivotY(artHeight());
    TextView title = ui.text("", kind == NativeCard.Kind.APP ? 21 : 22, Color.WHITE);
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    FrameLayout.LayoutParams titleLayout = new FrameLayout.LayoutParams(width(), ui.px(28));
    titleLayout.topMargin = reservedArtHeight() + ui.px(17);
    card.addView(title, titleLayout);
    TextView subtitle = ui.text("", 18, NativeUi.MUTED);
    subtitle.setMaxLines(kind == NativeCard.Kind.WATCH ? 2 : 1);
    subtitle.setEllipsize(TextUtils.TruncateAt.END);
    FrameLayout.LayoutParams subtitleLayout =
        new FrameLayout.LayoutParams(width(), subtitleHeight());
    subtitleLayout.topMargin = subtitleTop();
    card.addView(subtitle, subtitleLayout);
    ProgressBar progress =
        new ProgressBar(parent.getContext(), null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100);
    progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
    FrameLayout.LayoutParams progressLayout =
        new FrameLayout.LayoutParams(width() - ui.px(24), ui.px(5));
    progressLayout.leftMargin = ui.px(12);
    progressLayout.bottomMargin = ui.px(12);
    progressLayout.gravity = Gravity.BOTTOM;
    artwork.addView(progress, progressLayout);
    TextView tag = ui.text("", 17, Color.WHITE);
    tag.setBackground(ui.surface(0x77000000, 4));
    tag.setPadding(ui.px(6), ui.px(4), ui.px(6), ui.px(4));
    FrameLayout.LayoutParams tagLayout =
        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
    tagLayout.leftMargin = ui.px(12);
    tagLayout.bottomMargin = ui.px(11);
    artwork.addView(tag, tagLayout);
    return new Holder(card, artwork, art, title, subtitle, tag, progress);
  }

  @Override
  public void onBindViewHolder(@NonNull Holder holder, int position) {
    NativeCard card = getItem(position);
    if (holder.card == null || !holder.card.id.equals(card.id)) {
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
    holder.subtitle.setText(card.subtitle);
    holder.subtitle.setVisibility(
        card.subtitle == null || card.subtitle.isEmpty() ? View.INVISIBLE : View.VISIBLE);
    holder.itemView.setEnabled(card.enabled);
    holder.itemView.setFocusable(card.enabled);
    holder.itemView.setAlpha(card.enabled ? 1f : .45f);
    holder.itemView.setContentDescription(
        card.title
            + ". "
            + (card.item == null ? "" : holder.tag.getText() + ". ")
            + (card.subtitle == null ? "" : card.subtitle));
    holder.progress.setVisibility(card.progress < 0 ? View.GONE : View.VISIBLE);
    holder.progress.setProgress(Math.max(0, card.progress));
    if (card.app != null) images.showApp(holder.art, card.app, false);
    else images.load(holder.art, card.art, Math.round(width() * focusScale()), reservedArtHeight());
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
  }

  void setInstant(boolean instant) {
    this.instant = instant;
    updateMotion();
  }

  @Override
  public void onViewRecycled(@NonNull Holder holder) {
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
  }

  static final class Holder extends RecyclerView.ViewHolder {
    final FrameLayout artwork;
    final ImageView art;
    final TextView title, subtitle, tag;
    final ProgressBar progress;
    NativeCard card;

    Holder(
        View view,
        FrameLayout artwork,
        ImageView art,
        TextView title,
        TextView subtitle,
        TextView tag,
        ProgressBar progress) {
      super(view);
      this.artwork = artwork;
      this.art = art;
      this.title = title;
      this.subtitle = subtitle;
      this.tag = tag;
      this.progress = progress;
    }
  }
}
