package local.tv.launcher;

import android.app.Activity;
import android.os.Bundle;

/** Verification-only exported target for real SearchManager metadata and launch mapping. */
public class SearchFixtureActivity extends Activity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getSharedPreferences("search-fixture", MODE_PRIVATE)
        .edit()
        .putString("data", String.valueOf(getIntent().getData()))
        .putString("query", getIntent().getStringExtra(android.app.SearchManager.QUERY))
        .putString("action", getIntent().getAction())
        .commit();
    finish();
  }
}
