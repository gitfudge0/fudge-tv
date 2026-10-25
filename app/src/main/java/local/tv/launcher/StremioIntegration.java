package local.tv.launcher;
import android.app.Activity;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import java.util.List;
/** Exact title navigation only. Resolution uses registered handlers, never a fixed package. */
public final class StremioIntegration {
 private StremioIntegration(){}
 public static boolean open(Activity activity,String type,String imdbId){
  final String uri;try{uri=CinemetaPolicy.detailUri(type,imdbId);}catch(IllegalArgumentException e){return false;}
  Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE);
  List<ResolveInfo> handlers=activity.getPackageManager().queryIntentActivities(intent,0);
  if(handlers.isEmpty())return false;
  try{if(handlers.size()==1){ResolveInfo handler=handlers.get(0);if(handler.activityInfo==null||!handler.activityInfo.exported)return false;intent.setComponent(new ComponentName(handler.activityInfo.packageName,handler.activityInfo.name));}activity.startActivity(intent);return true;}catch(ActivityNotFoundException|SecurityException e){return false;}
 }
}
