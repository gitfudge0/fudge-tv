package local.tv.launcher;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Process;
import java.util.*;

final class Library {
    static final class App {
        String id,label; Intent launch; Drawable banner; long recent;
        App(String id,String label,Intent launch) {this.id=id;this.label=label;this.launch=launch;}
    }
    static final class Program {
        String id,owner,title,description,art; Intent launch; boolean resumable; long position,duration,engagement; int weight;
    }
    static final class Channel {
        String id,owner,title;
        final List<Program> programs=new ArrayList<>();
    }
    static final class Snapshot {
        final List<App> apps=new ArrayList<>(), allApps=new ArrayList<>();
        final List<Program> programs=new ArrayList<>();
        final List<Channel> channels=new ArrayList<>();
        String channelStatus="Your apps have not shared preview channels.";
        boolean usageEnabled, listingsGranted;
        String programStatus="No shared Continue Watching items yet.";
    }
    static boolean usageAllowed(Context c) {
        AppOpsManager ops=(AppOpsManager)c.getSystemService(Context.APP_OPS_SERVICE);
        return ops!=null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),c.getPackageName())==AppOpsManager.MODE_ALLOWED;
    }
    static Snapshot load(Context c) {
        Snapshot result=new Snapshot();PackageManager pm=c.getPackageManager();
        android.content.SharedPreferences history=c.getSharedPreferences("history",Context.MODE_PRIVATE);
        result.usageEnabled=usageAllowed(c);
        Map<String,Long> usage=new HashMap<>();
        if(result.usageEnabled)try {
            UsageStatsManager manager=(UsageStatsManager)c.getSystemService(Context.USAGE_STATS_SERVICE);
            List<UsageStats> stats=manager.queryUsageStats(UsageStatsManager.INTERVAL_BEST,System.currentTimeMillis()-31L*24*60*60*1000,System.currentTimeMillis());
            if(stats!=null)for(UsageStats stat:stats)usage.put(stat.getPackageName(), Math.max(usage.getOrDefault(stat.getPackageName(),0L),stat.getLastTimeUsed()));
        }catch(SecurityException ignored){result.usageEnabled=false;}
        for(int i=0;i<Policy.PACKAGES.length;i++) {
            String id=Policy.PACKAGES[i]; Intent launch=pm.getLeanbackLaunchIntentForPackage(id);
            App app=new App(id,Policy.LABELS[i],launch);
            app.recent=Math.max(history.getLong(id,0L),usage.getOrDefault(id,0L));
            try {app.banner=pm.getApplicationBanner(id);if(app.banner==null)app.banner=pm.getApplicationIcon(id);}catch(PackageManager.NameNotFoundException ignored){}
            result.apps.add(app);
        }
        result.apps.sort((a,b)->Policy.compareRecent(a.recent,b.recent,a.label,b.label));
        Intent query=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Set<String> found=new HashSet<>();
        for(ResolveInfo info:pm.queryIntentActivities(query,0)) {
            if(info.activityInfo==null || !info.activityInfo.exported || !found.add(info.activityInfo.packageName))continue;
            String id=info.activityInfo.packageName;
            if(!Policy.safeUserApp(id,info.activityInfo.name,info.loadLabel(pm).toString()))continue;
            Intent launch=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setComponent(new ComponentName(id,info.activityInfo.name));
            App app=new App(id,info.loadLabel(pm).toString(),launch);
            try {app.banner=info.activityInfo.loadBanner(pm);if(app.banner==null)app.banner=info.loadIcon(pm);}catch(RuntimeException ignored){}
            result.allApps.add(app);
        }
        result.allApps.sort((a,b)->a.label.compareToIgnoreCase(b.label));
        result.listingsGranted=c.checkSelfPermission("android.permission.READ_TV_LISTINGS")==PackageManager.PERMISSION_GRANTED;
        loadWatchNext(c,result);
        loadChannels(c,result);
        return result;
    }
    private static void loadWatchNext(Context c,Snapshot result) {
        String[] columns={"_id",TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME,TvContract.WatchNextPrograms.COLUMN_TITLE,TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,TvContract.WatchNextPrograms.COLUMN_INTENT_URI,TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,TvContract.WatchNextPrograms.COLUMN_BROWSABLE};
        // AOSP WatchNext provider rejects non-null selections for ordinary applications.
        try(Cursor cursor=c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI,columns,null,null,null)) {
            if(cursor==null)return;
            int examined=0;
            while(cursor.moveToNext() && examined++<1000) {
                String owner=cursor.getString(1);
                if(!Policy.RETAINED.contains(owner) || cursor.getInt(9)!=TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE || cursor.getInt(10)==0)continue;
                Intent target=validatedResume(c,cursor.getString(5),owner);boolean resumable=target!=null;
                if(target==null)target=c.getPackageManager().getLeanbackLaunchIntentForPackage(owner);
                if(target==null)continue;
                String title=cursor.getString(2);if(title==null || title.trim().isEmpty())continue;
                Program program=new Program();program.id=owner+":"+cursor.getLong(0);program.owner=owner;program.title=title;
                program.description=cursor.getString(3);program.art=cursor.getString(4);program.launch=target;program.resumable=resumable;
                program.position=Math.max(0,cursor.getLong(6));program.duration=Math.max(0,cursor.getLong(7));program.engagement=cursor.getLong(8);
                result.programs.add(program);
            }
            result.programs.sort((a,b)->Long.compare(b.engagement,a.engagement));
            if(result.programs.size()>30)result.programs.subList(30,result.programs.size()).clear();
            if(result.programs.isEmpty()) result.programStatus=result.listingsGranted?"Your apps have not shared any Continue Watching items.":"TV listings access is optional. Apps may keep their watch history private.";
        }catch(SecurityException e) {result.programStatus="Allow TV listings in Data access to look for shared Continue Watching items.";}
        catch(RuntimeException e){result.programStatus="Continue Watching is unavailable from this TV’s provider. Your apps still open normally.";}
    }
    /** Published provider previews only. No catalog lookups or viewing-history uploads. */
    private static void loadChannels(Context c,Snapshot result) {
        Map<Long,Channel> channels=new LinkedHashMap<>();
        String[] channelColumns={"_id",TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME,
                TvContract.Channels.COLUMN_DISPLAY_NAME,TvContract.Channels.COLUMN_TYPE,
                TvContract.Channels.COLUMN_BROWSABLE};
        // TV providers can reject selections for ordinary apps. Filter ownership in memory.
        try(Cursor cursor=c.getContentResolver().query(TvContract.Channels.CONTENT_URI,channelColumns,null,null,null)) {
            if(cursor==null){result.channelStatus="Preview channels are unavailable.";return;}
            int examined=0;
            while(cursor.moveToNext() && examined++<1000 && channels.size()<12) {
                String owner=cursor.getString(1),title=cursor.getString(2);
                if(!Policy.RETAINED.contains(owner) || !TvContract.Channels.TYPE_PREVIEW.equals(cursor.getString(3)) || cursor.getInt(4)==0 || title==null || title.trim().isEmpty())continue;
                Channel row=new Channel();row.id="channel:"+owner+":"+cursor.getLong(0);row.owner=owner;row.title=title;
                channels.put(cursor.getLong(0),row);
            }
        } catch(SecurityException e){result.channelStatus="TV listings access is needed to read shared preview channels.";return;}
          catch(RuntimeException e){result.channelStatus="Preview channels are unavailable from this TV’s provider.";return;}
        if(channels.isEmpty())return;
        String[] columns={"_id",TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME,
                TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,TvContract.PreviewPrograms.COLUMN_TITLE,
                TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                TvContract.PreviewPrograms.COLUMN_INTENT_URI,TvContract.PreviewPrograms.COLUMN_BROWSABLE,
                TvContract.PreviewPrograms.COLUMN_WEIGHT};
        try(Cursor cursor=c.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,columns,null,null,null)) {
            if(cursor==null){result.channelStatus="Preview programs are unavailable.";return;}
            int examined=0,total=0;
            Set<String> seen=new HashSet<>();
            while(cursor.moveToNext() && examined++<3000 && total<120) {
                Channel channel=channels.get(cursor.getLong(2));String owner=cursor.getString(1),title=cursor.getString(3);
                if(channel==null || !Policy.RETAINED.contains(owner) || !channel.owner.equals(owner) || channel.programs.size()>=20 || cursor.getInt(7)==0 || title==null || title.trim().isEmpty())continue;
                String id="preview:"+owner+":"+cursor.getLong(0);if(!seen.add(id))continue;
                Intent target=validatedResume(c,cursor.getString(6),owner);
                if(target==null)target=c.getPackageManager().getLeanbackLaunchIntentForPackage(owner);
                if(target==null)continue;
                Program program=new Program();program.id=id;program.owner=owner;program.title=title;
                program.description=cursor.getString(4);program.art=cursor.getString(5);program.launch=target;
                // A provider preview is not proof of playback history or resume position.
                program.resumable=false;program.position=0;program.duration=0;program.weight=cursor.getInt(8);
                channel.programs.add(program);total++;
            }
            for(Channel channel:channels.values())if(!channel.programs.isEmpty()) {
                channel.programs.sort((a,b)->Integer.compare(b.weight,a.weight));result.channels.add(channel);
            }
            result.channelStatus=result.channels.isEmpty()?"Your apps have not shared visible preview programs.":"Published by your installed apps";
        } catch(SecurityException e){result.channels.clear();result.channelStatus="TV listings access is needed to read shared preview programs.";}
          catch(RuntimeException e){result.channels.clear();result.channelStatus="Preview programs are unavailable from this TV’s provider.";}
    }
    static Intent validatedResume(Context c,String raw,String owner) {
        if(raw==null || !Policy.RETAINED.contains(owner))return null;
        try {
            Intent parsed=Intent.parseUri(raw,Intent.URI_INTENT_SCHEME);
            String componentPackage=parsed.getComponent()==null?null:parsed.getComponent().getPackageName();
            if(!Policy.ownerAllowed(owner,parsed.getPackage(),componentPackage) || parsed.getSelector()!=null)return null;
            Uri data=parsed.getData();if(data==null || !Policy.linkSchemeAllowed(data.getScheme()))return null;
            // Constrain every link to the row owner; never trust a provider-supplied external component.
            parsed.setPackage(owner);parsed.setClipData(null);parsed.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if(parsed.getAction()==null)parsed.setAction(Intent.ACTION_VIEW);
            ResolveInfo resolved=c.getPackageManager().resolveActivity(parsed,PackageManager.MATCH_DEFAULT_ONLY);
            if(resolved==null || resolved.activityInfo==null || !Policy.resolvedOwnerAllowed(owner,resolved.activityInfo.packageName,resolved.activityInfo.exported))return null;
            parsed.setComponent(new ComponentName(owner,resolved.activityInfo.name));
            return parsed;
        }catch(Exception ignored){return null;}
    }
}
