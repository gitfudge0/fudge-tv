package local.tv.launcher;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
/** Blocking Cinemeta client. All callers use the lifecycle-controlled network executor. */
public final class CatalogClient {
 private final Cache cache;private final Transport transport;
 interface Transport{JSONObject get(String path)throws Exception;}
 public CatalogClient(Context context){
  this(new Cache(context,"cinemeta_metadata_v1",8*1024*1024),path->new JSONObject(new String(SecureHttp.get("https://v3-cinemeta.strem.io"+path,Collections.singletonMap("Accept","application/json"),path.startsWith("/meta/")?5*1024*1024:1024*1024),StandardCharsets.UTF_8)));
  // Retire the old secret; preserve the legacy metadata cache untouched.
  context.getSharedPreferences("catalog_private",Context.MODE_PRIVATE).edit().remove("credential").apply();
 }
 CatalogClient(Cache cache,Transport transport){this.cache=cache;this.transport=transport;}
 private JSONObject request(String path)throws Exception{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();return transport.get(path);}
 public JSONObject home(){return load("home:v1",()->{
  JSONArray movies=new JSONArray(),series=new JSONArray(),featuredMovies=new JSONArray(),featuredSeries=new JSONArray();int successes=0,featuredSuccesses=0;
  try{movies=catalog("movie","top","");successes++;}catch(Exception ignored){}
  try{series=catalog("series","top","");successes++;}catch(Exception ignored){}
  try{featuredMovies=catalog("movie","imdbRating","");featuredSuccesses++;}catch(Exception ignored){}
  try{featuredSeries=catalog("series","imdbRating","");featuredSuccesses++;}catch(Exception ignored){}
  if(successes==0||movies.length()+series.length()==0)throw new IOException("Catalog unavailable");
  return envelope(interleave(movies,series,24),interleave(featuredMovies,featuredSeries,4),"live",successes<2||featuredSuccesses<2?"Some catalog results are unavailable":"").put("partial",successes<2||featuredSuccesses<2);
 });}
 private JSONArray catalog(String type,String catalog,String extra)throws Exception{return normalizeList(request(CinemetaPolicy.catalogPath(type,catalog,extra)).getJSONArray("metas"),type);}
 public JSONObject watchSearch(String query){return search(query);}
 public JSONObject search(String query){String q=NetworkPolicy.query(query);if(q.isEmpty())return envelope(new JSONArray(),new JSONArray(),"live","Enter a title or app name");return load("search:v1:"+q,()->{
  JSONArray movies=new JSONArray(),series=new JSONArray();int successes=0;boolean partial=false;
  for(String type:new String[]{"movie","series"}){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();JSONArray found=new JSONArray();boolean successful=false;
   try{found=catalog(type,"top","search="+CinemetaPolicy.encode(q));successful=true;}catch(Exception ignored){partial=true;}
   String genre=CinemetaPolicy.genre(q,type);if(!genre.isEmpty()){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();try{found=merge(catalog(type,"top","genre="+CinemetaPolicy.encode(genre)),found);successful=true;}catch(Exception ignored){partial=true;}}
   if(successful)successes++;if(type.equals("movie"))movies=found;else series=found;
  }
  if(successes==0)throw new IOException("Catalog unavailable");return envelope(interleave(movies,series,24),new JSONArray(),"live",partial?"Some catalog results are unavailable":"").put("partial",partial);
 });}
 public JSONObject details(String type,String id){if(!NetworkPolicy.validType(type)||!NetworkPolicy.validId(id))return envelope(new JSONArray(),new JSONArray(),"error","Invalid title");return load("details:v1:"+type+":"+id,()->{JSONObject raw=request(CinemetaPolicy.metaPath(type,id)).optJSONObject("meta");JSONObject item=normalize(raw,type);if(item==null||!id.equals(item.optString("id")))throw new IOException("Invalid metadata");return envelope(new JSONArray(),new JSONArray(),"live","").put("item",item);});}
 private interface Fetch{JSONObject get()throws Exception;}
 private JSONObject load(String key,Fetch fetch){JSONObject saved=cache.read(key);if(saved!=null&&cache.fresh(key,3600000))return status(saved,"cached","Cached within the last hour",false);try{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();JSONObject result=fetch.get();if(!Thread.currentThread().isInterrupted()&&!result.optBoolean("partial"))cache.write(key,result);return result;}catch(Exception ignored){return saved==null?envelope(new JSONArray(),new JSONArray(),"error","Catalog unavailable; check connection"):status(saved,"cached","Offline: showing previously fetched catalog",true);}}
 private static JSONObject status(JSONObject o,String s,String message,boolean stale){try{o.put("status",s).put("message",message).put("stale",stale);}catch(JSONException ignored){}return o;}
 static JSONObject envelope(JSONArray items,JSONArray featured,String status,String message){JSONObject o=new JSONObject();try{o.put("items",items).put("featured",featured).put("status",status).put("message",message).put("stale",false);}catch(JSONException ignored){}return o;}
 static JSONArray normalizeList(JSONArray rows,String type)throws JSONException{JSONArray out=new JSONArray();if(rows!=null)for(int i=0;i<rows.length()&&out.length()<24;i++){JSONObject raw=rows.optJSONObject(i);if(raw!=null){raw.remove("videos");add(out,normalize(raw,type));}}return out;}
 private static void add(JSONArray out,JSONObject item)throws JSONException{if(item==null||out.length()>=24)return;for(int i=0;i<out.length();i++)if(out.getJSONObject(i).optString("key").equals(item.optString("key")))return;out.put(item);}
 private static JSONArray merge(JSONArray first,JSONArray second)throws JSONException{for(int i=0;i<second.length();i++)add(first,second.getJSONObject(i));return first;}
 private static JSONArray interleave(JSONArray a,JSONArray b,int limit)throws JSONException{JSONArray out=new JSONArray();for(int i=0;i<Math.max(a.length(),b.length())&&out.length()<limit;i++){if(i<a.length())add(out,a.getJSONObject(i));if(out.length()<limit&&i<b.length())add(out,b.getJSONObject(i));}return out;}
 static JSONObject normalize(JSONObject r,String requestedType)throws JSONException{
  if(r==null)return null;String id=r.optString("id",r.optString("imdb_id","")),type=r.optString("type",requestedType),title=text(r,"name");
  if(!NetworkPolicy.validType(type)||!type.equals(requestedType)||!NetworkPolicy.validId(id)||title.isEmpty())return null;
  String year=text(r,"releaseInfo");if(year.isEmpty())year=text(r,"year");String released=text(r,"released");if(year.isEmpty()&&released.matches("[0-9]{4}-.*"))year=released.substring(0,4);
  JSONObject out=new JSONObject().put("key",type+":"+id).put("id",id).put("imdbId",id).put("mediaType",type).put("title",title).put("year",year).put("released",released).put("overview",text(r,"description")).put("poster",image(text(r,"poster"))).put("art",image(text(r,"background"))).put("tagline","");
  JSONArray genres=r.optJSONArray("genres");if(genres==null)genres=r.optJSONArray("genre");out.put("genres",strings(genres,12));
  String runtime=text(r,"runtime");out.put("runtimeText",runtime);if(runtime.matches("[0-9]{1,4} min"))out.put("runtime",Integer.parseInt(runtime.split(" ")[0]));double rating=r.optDouble("imdbRating",Double.NaN);if(!Double.isNaN(rating)&&rating>=0&&rating<=10)out.put("rating",rating);
  JSONArray cast=new JSONArray(),people=r.optJSONArray("cast");if(people!=null)for(int i=0;i<people.length()&&i<12;i++){Object person=people.opt(i);if(!(person instanceof String))continue;String name=(String)person;if(!name.isEmpty())cast.put(new JSONObject().put("name",name).put("character","").put("photo",""));}out.put("cast",cast).put("directors",strings(r.optJSONArray("director"),12)).put("writers",strings(r.optJSONArray("writer"),12));
  JSONArray episodes=new JSONArray(),videos=r.optJSONArray("videos");Set<Integer> seasons=new TreeSet<>();if(videos!=null)for(int i=0;i<videos.length();i++){JSONObject v=videos.optJSONObject(i);if(v==null)continue;int season=v.optInt("season",-1),episode=v.optInt("episode",v.optInt("number",-1));String vid=v.optString("id","");if(season<0||episode<0||!vid.equals(id+":"+season+":"+episode))continue;seasons.add(season);episodes.put(new JSONObject().put("id",vid).put("title",text(v,"name")).put("season",season).put("episode",episode).put("released",text(v,"released")).put("overview",text(v,"description")).put("thumbnail",image(text(v,"thumbnail"))));}
  JSONArray ss=new JSONArray();for(Integer season:seasons)ss.put(season);return out.put("episodes",episodes).put("seasons",ss);
 }
 private static String text(JSONObject r,String key){return r.isNull(key)?"":r.optString(key,"");}
 private static JSONArray strings(JSONArray values,int max){JSONArray out=new JSONArray();if(values!=null)for(int i=0;i<values.length()&&out.length()<max;i++){Object value=values.opt(i);if(value instanceof String&&!((String)value).isEmpty())out.put(value);}return out;}
 private static String image(String url){return NetworkPolicy.imageUrl(url)?url:"";}
 /** Native private cache keys are digests of metadata identifiers, never credentials. */
 static final class Cache {
  final File dir;final long cap;Cache(Context c,String name,long cap){this(new File(c.getCacheDir(),name),cap);}
  Cache(File directory,long cap){dir=directory;dir.mkdirs();this.cap=cap;}
  private File file(String key){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder();for(byte b:bytes)hex.append(String.format(Locale.ROOT,"%02x",b&255));return new File(dir,hex+".json");}catch(Exception e){throw new IllegalStateException("Cache unavailable");}}
  synchronized boolean fresh(String key,long ttl){File f=file(key);long age=System.currentTimeMillis()-f.lastModified();return f.exists()&&age>=0&&age<ttl;}
  synchronized JSONObject read(String key){File f=file(key);if(!f.isFile()||f.length()>5*1024*1024)return null;try(FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>5*1024*1024)return null;out.write(b,0,n);}return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));}catch(Exception e){return null;}}
  synchronized void write(String key,JSONObject value){byte[] data=value.toString().getBytes(StandardCharsets.UTF_8);if(data.length>5*1024*1024)return;File target=file(key),temp=new File(dir,"pending.tmp");try{try(FileOutputStream out=new FileOutputStream(temp)){out.write(data);}if(!temp.renameTo(target))temp.delete();File[] files=dir.listFiles();if(files==null)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));long total=0;for(File f:files)total+=f.length();for(File f:files){if(total<=cap)break;long size=f.length();if(f.delete())total-=size;}}catch(Exception ignored){temp.delete();}}
  synchronized void clear(){File[] files=dir.listFiles();if(files!=null)for(File f:files)f.delete();}
 }
}
