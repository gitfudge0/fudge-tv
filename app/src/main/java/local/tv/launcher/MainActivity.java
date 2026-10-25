package local.tv.launcher;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.tv.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Trusted packaged presentation. Native code owns credentials, data sources and every app launch. */
public final class MainActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService libraryExecutor=Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor network=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),new ThreadPoolExecutor.DiscardOldestPolicy());
    private WebView web;private NativeImages images;private CatalogClient catalog;private WeatherClient weather;
    private Library.Snapshot snapshot=new Library.Snapshot();
    private final Map<String,Library.Program> programs=new HashMap<>();
    private final Map<String,Library.App> apps=new HashMap<>();
    private final Map<String,JSONObject> titles=new LinkedHashMap<>();
    private boolean resumed,pageReady,textEditing;private int libraryGeneration,catalogGeneration,searchGeneration,detailsGeneration,weatherGeneration;
    private Future<?> searchTask,detailsTask;private float viewportScale;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        images=new NativeImages(this);catalog=new CatalogClient(this);weather=new WeatherClient(this);
        viewportScale=1f/getResources().getDisplayMetrics().density;
        try{createWebView();}catch(RuntimeException failure){safeUiFailure();}
    }
    @SuppressWarnings({"deprecation","SetJavaScriptEnabled"})
    private void createWebView(){
        web=new WebView(this);web.setBackgroundColor(Color.rgb(9,10,12));web.setFocusable(true);web.setFocusableInTouchMode(true);setContentView(web);
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setAllowFileAccessFromFileURLs(false);settings.setAllowUniversalAccessFromFileURLs(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSupportMultipleWindows(false);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setMediaPlaybackRequiresUserGesture(true);settings.setTextZoom(100);settings.setUseWideViewPort(true);settings.setLoadWithOverviewMode(false);settings.setBuiltInZoomControls(false);settings.setDisplayZoomControls(false);settings.setSupportZoom(false);settings.setGeolocationEnabled(false);settings.setSaveFormData(false);
        web.setInitialScale(Math.round(viewportScale*100));CookieManager.getInstance().setAcceptCookie(false);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);WebView.setWebContentsDebuggingEnabled(false);
        WebViewAssetLoader loader=new WebViewAssetLoader.Builder().addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClientCompat(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !BridgePolicy.INDEX.equals(request.getUrl().toString());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();String url=uri.toString();
                try{
                    if(!"GET".equals(request.getMethod()))return denied();
                    if(BridgePolicy.INDEX.equals(url)){
                        String html;try(InputStream in=getAssets().open("ui/index.html")){html=new String(read(in,512*1024),StandardCharsets.UTF_8);}
                        String viewport="width=1920,initial-scale="+viewportScale+",minimum-scale="+viewportScale+",maximum-scale="+viewportScale+",user-scalable=no";
                        html=html.replace("width=device-width,initial-scale=1",viewport);
                        return response("text/html",html.getBytes(StandardCharsets.UTF_8));
                    }
                    if("https".equals(uri.getScheme())&&"appassets.androidplatform.net".equals(uri.getHost())&&(uri.getPort()==-1||uri.getPort()==443)){
                        String path=uri.getPath();if(path!=null&&path.startsWith("/art/"))return response("image/jpeg",images.get(path.substring(5)));
                        if(path!=null&&path.startsWith("/assets/ui/")&&!path.contains("..")){WebResourceResponse resource=loader.shouldInterceptRequest(uri);return resource==null?denied():resource;}
                    }
                    if(NetworkPolicy.imageUrl(url)){String local=images.artwork(url);return response("image/jpeg",images.get(Uri.parse(local).getLastPathSegment()));}
                }catch(Exception ignored){}return denied();
            }
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){pageReady=false;}
            @Override public void onPageFinished(WebView view,String url){if(BridgePolicy.INDEX.equals(url)&&web!=null)web.requestFocus();}
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail){pageReady=false;if(web==view)web=null;view.destroy();main.post(()->safeUiFailure());return true;}
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,android.net.http.SslError error){handler.cancel();}
        });
        web.setWebChromeClient(new WebChromeClient(){@Override public boolean onCreateWindow(WebView view,boolean dialog,boolean gesture,android.os.Message result){return false;}@Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){callback.invoke(origin,false,false);}@Override public void onPermissionRequest(PermissionRequest request){request.deny();}});
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))throw new IllegalStateException("Trusted messaging unavailable");
        WebViewCompat.addWebMessageListener(web,"Native",Collections.singleton(BridgePolicy.ORIGIN),(view,message,origin,isMainFrame,reply)->{
            if(web==null||!isMainFrame||!BridgePolicy.trustedOrigin(origin.toString())||!BridgePolicy.INDEX.equals(web.getUrl()))return;
            String value=message.getData();if(!BridgePolicy.payloadLength(value))return;
            try{JSONObject data=new JSONObject(value);String operation=data.optString("op");if(BridgePolicy.operation(operation))handle(operation,data);}catch(JSONException ignored){}
        });
        web.loadUrl(BridgePolicy.INDEX);
    }
    private static byte[] read(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>limit)throw new IOException("Asset too large");out.write(buffer,0,n);}return out.toByteArray();}
    private static WebResourceResponse response(String mime,byte[] bytes){Map<String,String> headers=new HashMap<>();headers.put("Cache-Control","no-store");headers.put("X-Content-Type-Options","nosniff");return new WebResourceResponse(mime,"UTF-8",200,"OK",headers,new ByteArrayInputStream(bytes));}
    private static WebResourceResponse denied(){return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.singletonMap("Cache-Control","no-store"),new ByteArrayInputStream(new byte[0]));}
    private void handle(String op,JSONObject data){
        switch(op){
            case "ready":pageReady=true;sendConfig();refreshLibrary();loadHome();loadWeather(city());break;
            case "resume":Library.Program p=programs.get(data.optString("id"));if(p!=null)launch(p.launch,p.owner);break;
            case "launchApp":Library.App a=apps.get(data.optString("package"));if(a!=null&&Policy.safeUserApp(a.id,a.launch==null?"":String.valueOf(a.launch.getComponent()),a.label))launch(a.launch,a.id);break;
            case "details":loadDetails(data.optString("key"),BridgePolicy.requestId(data.optString("requestId")));break;
            case "openDiscovery":openDiscovery(data.optString("key"));break;
            case "search":loadSearch(NetworkPolicy.query(data.optString("query")),BridgePolicy.requestId(data.optString("requestId")));break;
            case "weather":String name=NetworkPolicy.query(data.optString("city"));if(!name.isEmpty()){getSharedPreferences("presentation",MODE_PRIVATE).edit().putString("city",name).apply();loadWeather(name);}break;
            case "region":regionDialog();break;
            case "controls":controls();break;
            case "listings":requestListings();break;
            case "usage":usageSettings();break;
            case "allApps":allApps();break;
            case "imeOpen":textEditing=true;web.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(web,InputMethodManager.SHOW_IMPLICIT);break;
            case "imeHide":textEditing=false;((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(web.getWindowToken(),0);break;
            case "textFocus":textEditing=data.optBoolean("active");break;
            case "refresh":refreshLibrary();loadHome();loadWeather(city());break;
            case "metrics":recordMetrics(data);break;
        }
    }
    private void sendConfig(){try{JSONObject config=new JSONObject().put("credentialConfigured",catalog.hasCredential()).put("city",city()).put("region",catalog.region()).put("reducedMotion",!android.animation.ValueAnimator.areAnimatorsEnabled()).put("appVersion","2.0");send("config",config);}catch(JSONException ignored){}}
    private String city(){return getSharedPreferences("presentation",MODE_PRIVATE).getString("city","Pune");}
    private void refreshLibrary(){final int generation=++libraryGeneration;libraryExecutor.execute(()->{Library.Snapshot next=Library.load(getApplicationContext());JSONObject result=serialize(next);main.post(()->{if(!resumed||generation!=libraryGeneration)return;snapshot=next;programs.clear();apps.clear();for(Library.Program p:next.programs)programs.put(p.id,p);for(Library.Channel channel:next.channels)for(Library.Program p:channel.programs)programs.put(p.id,p);for(Library.App a:next.allApps)apps.put(a.id,a);for(Library.App a:next.apps)apps.put(a.id,a);send("snapshot",result);});});}
    private JSONObject serialize(Library.Snapshot value){JSONObject out=new JSONObject();try{
        JSONArray watch=new JSONArray(),retained=new JSONArray();
        for(Library.Program p:value.programs){String owner=p.owner;for(int i=0;i<Policy.PACKAGES.length;i++)if(owner.equals(Policy.PACKAGES[i]))owner=Policy.LABELS[i];watch.put(new JSONObject().put("id",p.id).put("title",p.title).put("owner",owner).put("art",images.artwork(p.art)).put("position",p.position).put("duration",p.duration).put("resumable",p.resumable));}
        for(Library.App a:value.apps)retained.put(new JSONObject().put("package",a.id).put("name",a.label).put("installed",a.launch!=null).put("art",images.banner(a.id,a.banner)));
        JSONArray channels=new JSONArray();for(Library.Channel channel:value.channels){JSONArray entries=new JSONArray();for(Library.Program p:channel.programs)entries.put(new JSONObject().put("id",p.id).put("title",p.title).put("art",images.artwork(p.art)));String owner=channel.owner;for(int i=0;i<Policy.PACKAGES.length;i++)if(owner.equals(Policy.PACKAGES[i]))owner=Policy.LABELS[i];channels.put(new JSONObject().put("id",channel.id).put("owner",owner).put("title",channel.title).put("items",entries));}
        out.put("channels",channels).put("channelStatus",value.channelStatus).put("queue",watch).put("apps",retained).put("queueStatus",value.programStatus).put("usageEnabled",value.usageEnabled).put("listingsGranted",value.listingsGranted);
    }catch(JSONException ignored){}return out;}
    private void rememberTitles(JSONObject result){for(String field:new String[]{"items","featured"}){JSONArray array=result.optJSONArray(field);if(array!=null)for(int i=0;i<array.length();i++){JSONObject item=array.optJSONObject(i);if(item!=null&&BridgePolicy.titleKey(item.optString("key")))titles.put(item.optString("key"),item);}}JSONObject item=result.optJSONObject("item");if(item!=null&&BridgePolicy.titleKey(item.optString("key")))titles.put(item.optString("key"),item);while(titles.size()>200)titles.remove(titles.keySet().iterator().next());}
    private void loadHome(){final int generation=++catalogGeneration;network.execute(()->{JSONObject result=catalog.home();main.post(()->{if(!resumed||generation!=catalogGeneration)return;rememberTitles(result);send("catalog",result);});});}
    private void loadSearch(String query,String requestId){final int generation=++searchGeneration;if(searchTask!=null)searchTask.cancel(true);searchTask=network.submit(()->{JSONObject result=catalog.search(query);try{result.put("requestId",requestId);}catch(JSONException ignored){}main.post(()->{if(!resumed||generation!=searchGeneration)return;rememberTitles(result);send("search",result);});});}
    private void loadDetails(String key,String requestId){if(!BridgePolicy.titleKey(key)||!titles.containsKey(key))return;final int generation=++detailsGeneration;if(detailsTask!=null)detailsTask.cancel(true);String[] parts=key.split(":");detailsTask=network.submit(()->{JSONObject result=catalog.details(parts[0],Integer.parseInt(parts[1]));try{result.put("requestId",requestId).put("key",key);}catch(JSONException ignored){}main.post(()->{if(!resumed||generation!=detailsGeneration)return;rememberTitles(result);send("details",result);});});}
    private void loadWeather(String name){final int generation=++weatherGeneration;network.execute(()->{JSONObject result=weather.load(name);main.post(()->{if(resumed&&generation==weatherGeneration)send("weather",result);});});}
    private void send(String event,JSONObject value){if(!pageReady||web==null||isDestroyed())return;String data=value.toString().replace("\u2028","\\u2028").replace("\u2029","\\u2029");web.evaluateJavascript("window.Fudge&&window.Fudge.nativeEvent("+JSONObject.quote(event)+","+data+");",null);}
    private void openDiscovery(String key){JSONObject item=titles.get(key);if(item==null)return;List<Library.App> choices=new ArrayList<>();List<String> labels=new ArrayList<>();for(Library.App a:snapshot.apps)if(a.launch!=null){choices.add(a);labels.add("Open "+a.label);}if(choices.isEmpty()){toast("No retained streaming app is installed.");return;}new AlertDialog.Builder(this).setTitle(item.optString("title")).setItems(labels.toArray(new String[0]),(dialog,which)->{Library.App a=choices.get(which);toast("Opening "+a.label+". Search for the title in that app.");launch(a.launch,a.id);}).setNegativeButton("Cancel",null).show();}
    private void launch(Intent intent,String owner){if(intent==null){toast("This app is not installed.");return;}try{startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));getSharedPreferences("history",MODE_PRIVATE).edit().putLong(owner,System.currentTimeMillis()).apply();}catch(ActivityNotFoundException|SecurityException e){toast("Unable to open this item. Open the app directly.");}}
    private void regionDialog(){EditText input=new EditText(this);input.setSingleLine(true);input.setText(catalog.region());input.setHint("Two-letter country code");new AlertDialog.Builder(this).setTitle("Provider region").setMessage("Availability region only; no GPS location is requested.").setView(input).setPositiveButton("Save",(dialog,which)->{String region=input.getText().toString().trim().toUpperCase(Locale.ROOT);if(!region.matches("[A-Z]{2}")){toast("Use a two-letter country code.");return;}network.execute(()->{catalog.setRegion(region);main.post(()->{sendConfig();toast("Provider region updated.");});});}).setNegativeButton("Cancel",null).show();}
    private void controls(){String[] labels={"Inputs / Live TV","Wi-Fi settings","TV settings","TV listings permission","Usage access","All installed TV apps","About Fudge Launcher"};new AlertDialog.Builder(this).setTitle("TV controls").setItems(labels,(dialog,which)->{switch(which){case 0:inputs();break;case 1:openSystem(new Intent(Settings.ACTION_WIFI_SETTINGS));break;case 2:openSystem(new Intent(Settings.ACTION_SETTINGS));break;case 3:requestListings();break;case 4:usageSettings();break;case 5:allApps();break;case 6:new AlertDialog.Builder(this).setTitle("Fudge Launcher 2.0").setMessage("Trusted local presentation. Real shared Continue Watching and installed-app launches. Live discovery by TMDB; weather by Open-Meteo. No geolocation, analytics or simulated playback. Credentials stay native and private.").setPositiveButton("Close",null).show();break;}}).setNegativeButton("Close",null).show();}
    private void allApps(){List<Library.App> choices=new ArrayList<>();List<String> labels=new ArrayList<>();for(Library.App a:snapshot.allApps)if(a.launch!=null&&Policy.safeUserApp(a.id,String.valueOf(a.launch.getComponent()),a.label)){choices.add(a);labels.add(a.label);}new AlertDialog.Builder(this).setTitle("Installed TV apps").setItems(labels.toArray(new String[0]),(dialog,which)->{Library.App a=choices.get(which);launch(a.launch,a.id);}).setNegativeButton("Close",null).show();}
    private void requestListings(){if(checkSelfPermission("android.permission.READ_TV_LISTINGS")==PackageManager.PERMISSION_GRANTED){refreshLibrary();toast("TV listings already allowed.");}else requestPermissions(new String[]{"android.permission.READ_TV_LISTINGS"},44);}
    private void usageSettings(){Intent intent=new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(Uri.parse("package:"+getPackageName()));if(intent.resolveActivity(getPackageManager())==null)intent.setData(null);openSystem(intent);}
    private void inputs(){try{TvInputManager manager=(TvInputManager)getSystemService(TV_INPUT_SERVICE);List<TvInputInfo> available=new ArrayList<>();List<String> labels=new ArrayList<>();if(manager!=null)for(TvInputInfo input:manager.getTvInputList())if(input.isPassthroughInput()&&!input.isHidden(this)){available.add(input);labels.add(input.loadLabel(this).toString());}labels.add("Live TV / system input menu");new AlertDialog.Builder(this).setTitle("Inputs").setItems(labels.toArray(new String[0]),(dialog,which)->{if(which>=available.size()){systemInputs();return;}try{Intent intent=new Intent(Intent.ACTION_VIEW,TvContract.buildChannelUriForPassthroughInput(available.get(which).getId()));if(intent.resolveActivity(getPackageManager())==null)systemInputs();else startActivity(intent);}catch(SecurityException|ActivityNotFoundException e){systemInputs();}}).setNegativeButton("Close",null).show();}catch(SecurityException e){systemInputs();}}
    private void systemInputs(){Intent menu=new Intent("android.intent.action.TV_INPUTS");if(menu.resolveActivity(getPackageManager())!=null)openSystem(menu);else{toast("Use INPUT on the Sony remote, or Inputs in TV settings.");openSystem(new Intent(Settings.ACTION_SETTINGS));}}
    private void openSystem(Intent intent){try{startActivity(intent);}catch(ActivityNotFoundException|SecurityException e){toast("This TV does not provide that settings screen.");}}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private void safeUiFailure(){new AlertDialog.Builder(this).setTitle("Fudge Launcher").setMessage("The local presentation could not load. TV settings and installed apps remain available.").setPositiveButton("Retry",(d,w)->recreate()).setNeutralButton("TV controls",(d,w)->controls()).setCancelable(false).show();}
    private void recordMetrics(JSONObject incoming){try{JSONObject safe=new JSONObject();for(String key:new String[]{"innerWidth","innerHeight","dpr","scale","fontSize","lineHeight","fontWeight"}){double value=incoming.optDouble(key,Double.NaN);if(Double.isFinite(value))safe.put(key,value);}safe.put("interLoaded",incoming.optBoolean("interLoaded"));safe.put("fontFamily",incoming.optString("fontFamily").startsWith("Inter")?"Inter":"fallback");for(String key:new String[]{"hero","copy","queue","nav","rail"}){JSONArray value=incoming.optJSONArray(key);if(value!=null&&value.length()==4){JSONArray rect=new JSONArray();for(int i=0;i<4;i++)rect.put(Math.max(-10000,Math.min(10000,value.optDouble(i))));safe.put(key,rect);}}Log.i("FudgeViewport",safe.toString());}catch(JSONException ignored){}}
    @Override protected void onResume(){super.onResume();resumed=true;if(web!=null)web.onResume();if(pageReady){sendConfig();refreshLibrary();loadHome();loadWeather(city());send("lifecycle",object("active",true));}}
    @Override protected void onPause(){resumed=false;libraryGeneration++;catalogGeneration++;searchGeneration++;detailsGeneration++;weatherGeneration++;if(pageReady)send("lifecycle",object("active",false));if(web!=null)web.onPause();super.onPause();}
    @Override protected void onDestroy(){pageReady=false;main.removeCallbacksAndMessages(null);libraryExecutor.shutdownNow();network.shutdownNow();if(web!=null){web.stopLoading();web.destroy();web=null;}super.onDestroy();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(pageReady&&event.getAction()==KeyEvent.ACTION_DOWN){String key=null;switch(event.getKeyCode()){case KeyEvent.KEYCODE_DPAD_LEFT:if(!textEditing)key="left";break;case KeyEvent.KEYCODE_DPAD_RIGHT:if(!textEditing)key="right";break;case KeyEvent.KEYCODE_DPAD_UP:key="up";break;case KeyEvent.KEYCODE_DPAD_DOWN:key="down";break;case KeyEvent.KEYCODE_DPAD_CENTER:case KeyEvent.KEYCODE_ENTER:if(!textEditing)key="enter";break;}if(key!=null){if(!key.equals("enter")||event.getRepeatCount()==0)web.evaluateJavascript("window.Fudge&&Fudge.remote("+JSONObject.quote(key)+");",null);return true;}}return super.dispatchKeyEvent(event);}
    @Override public void onBackPressed(){if(pageReady)web.evaluateJavascript("window.Fudge&&Fudge.back();",null);else super.onBackPressed();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==44)refreshLibrary();}
    private static JSONObject object(String key,Object value){JSONObject result=new JSONObject();try{result.put(key,value);}catch(JSONException ignored){}return result;}
}
