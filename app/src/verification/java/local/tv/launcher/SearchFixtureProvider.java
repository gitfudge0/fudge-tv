package local.tv.launcher;

import android.app.SearchManager;
import android.content.*;
import android.database.*;
import android.net.Uri;

/** Public searchable fixture only present in the non-release verification variant. */
public class SearchFixtureProvider extends ContentProvider {
  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    String query = args == null ? uri.getLastPathSegment() : args[0];
    if ("broken".equals(query)) throw new IllegalStateException("fixture provider failure");
    if ("slow".equals(query)) {
      try {
        Thread.sleep(2500);
      } catch (InterruptedException ignored) {
      }
    }
    MatrixCursor cursor =
        new MatrixCursor(
            new String[] {
              "_id",
              SearchManager.SUGGEST_COLUMN_TEXT_1,
              SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID
            });
    cursor.addRow(new Object[] {1, "Fixture " + query, query});
    return cursor;
  }

  @Override
  public String getType(Uri uri) {
    return SearchManager.SUGGEST_MIME_TYPE;
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri uri, String where, String[] args) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri uri, ContentValues values, String where, String[] args) {
    throw new UnsupportedOperationException();
  }
}
