package local.tv.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Isolated real-window host with deterministic fixtures; never contacts remote services. */
public class NativeUiTestActivity extends Activity implements NativeHomeView.Listener {
  NativeHomeView home;
  NativeImages images;
  final List<String> searches = new ArrayList<>(),
      launches = new ArrayList<>(),
      resumes = new ArrayList<>();
  int detailRequests, detailCancellations;
  JSONObject openedTitle;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setFlags(1024, 1024);
    Bitmap bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.RGB_565);
    bitmap.eraseColor(0xff354359);
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, data);
    bitmap.recycle();
    images =
        new NativeImages(
            this, new File(getCacheDir(), "ui-fixture-art"), uri -> data.toByteArray());
    home = new NativeHomeView(this, images, this);
    setContentView(home);
    home.renderSnapshot(fixtureSnapshot());
    home.renderCatalog(fixtureCatalog());
    home.setActive(true);
  }

  static Library.Snapshot fixtureSnapshot() {
    Library.Snapshot snapshot = new Library.Snapshot();
    snapshot.channelCapability = "available";
    for (int i = 0; i < 7; i++) {
      Library.App app =
          new Library.App(
              "fixture.app" + i, i == 0 ? "Stremio" : "App " + i, new Intent("fixture"));
      app.banner = new ColorDrawable(0xff5d6a7b);
      snapshot.apps.add(app);
      snapshot.allApps.add(app);
    }
    for (int i = 0; i < 12; i++) {
      Library.Program p = new Library.Program();
      p.id = "watch:" + i;
      p.title = "Shared title " + i;
      p.owner = "fixture.app0";
      p.season = "1";
      p.episode = String.valueOf(i + 1);
      p.resumable = true;
      p.position = 30000;
      p.duration = 600000;
      p.art = "https://images.metahub.space/fixture.jpg";
      snapshot.programs.add(p);
    }
    Library.Channel channel = new Library.Channel();
    channel.id = "fixture";
    channel.title = "Fixture recommendations";
    channel.owner = "fixture.app0";
    for (int i = 0; i < 24; i++) {
      Library.Program p = new Library.Program();
      p.id = "published:" + i;
      p.title = "Published title " + i;
      p.owner = "fixture.app0";
      p.art = "https://images.metahub.space/fixture.jpg";
      channel.programs.add(p);
    }
    snapshot.channels.add(channel);
    return snapshot;
  }

  static JSONObject fixtureCatalog() {
    JSONObject result = new JSONObject();
    JSONArray items = new JSONArray();
    try {
      for (int i = 0; i < 48; i++)
        items.put(
            new JSONObject()
                .put("key", "movie:tt" + (1000000 + i))
                .put("title", "Fixture title " + i)
                .put("mediaType", "movie")
                .put("year", "2025")
                .put("genres", new JSONArray().put("Drama"))
                .put(
                    "overview",
                    i % 2 == 0
                        ? "A short synopsis."
                        : "A longer synopsis that deliberately occupies two lines of text while"
                            + " keeping the description region and all of the rows below it at"
                            + " the same position.")
                .put("poster", "https://images.metahub.space/fixture.jpg")
                .put("art", "https://images.metahub.space/fixture.jpg"));
      result
          .put("items", items)
          .put(
              "featured",
              new JSONArray()
                  .put(items.get(0))
                  .put(items.get(1))
                  .put(items.get(2))
                  .put(items.get(3)))
          .put("status", "live");
    } catch (org.json.JSONException e) {
      throw new AssertionError(e);
    }
    return result;
  }

  @Override
  public void onSearch(String query) {
    searches.add(query);
  }

  @Override
  public void onCancelSearch() {}

  @Override
  public void onDetails(JSONObject item) {
    detailRequests++;
  }

  @Override
  public void onCancelDetails() {
    detailCancellations++;
  }

  @Override
  public void onOpenTitle(JSONObject item) {
    openedTitle = item;
  }

  @Override
  public void onResumeProgram(String id) {
    resumes.add(id);
  }

  @Override
  public void onLaunchApp(String id) {
    launches.add(id);
  }

  @Override
  public void onControls() {}

  @Override
  public void onAllApps() {}

  @Override
  public void onRefresh() {}

  @Override
  protected void onDestroy() {
    home.release();
    images.close();
    super.onDestroy();
  }

  public void onInput(String id) {
    launches.add("input:" + id);
  }

  public void onSystemHome() {
    launches.add("system-home");
  }

  public void onSystemSettings() {
    launches.add("system-settings");
  }
}
