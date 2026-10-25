package local.tv.launcher;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
/** Pure boundary checks; never include request URLs or credentials in error messages. */
public final class NetworkPolicy {
 private static final Set<String> HOSTS=new HashSet<>(Arrays.asList("v3-cinemeta.strem.io","cinemeta-catalogs.strem.io","images.metahub.space","episodes.metahub.space","m.media-amazon.com","api.open-meteo.com","geocoding-api.open-meteo.com"));
 private NetworkPolicy(){}
 public static boolean allowedUrl(String value){try{URI u=new URI(value);return "https".equals(u.getScheme())&&HOSTS.contains(u.getHost())&&u.getUserInfo()==null&&(u.getPort()==-1||u.getPort()==443)&&u.getFragment()==null;}catch(Exception e){return false;}}
 /** Only the official add-on's observed 307 catalog handoff; destination repeats the exact path. */
 public static boolean catalogRedirect(String from,String to){try{URI source=new URI(from);if(!allowedUrl(from)||!"v3-cinemeta.strem.io".equals(source.getHost())||source.getQuery()!=null)return false;String path=source.getRawPath();java.util.regex.Matcher match=java.util.regex.Pattern.compile("/catalog/(movie|series)/(top|imdbRating)(/[^?#]*)?\\.json").matcher(path);return match.matches()&&allowedUrl(to)&&to.equals("https://cinemeta-catalogs.strem.io/"+match.group(2)+path);}catch(Exception e){return false;}}
 public static boolean imageUrl(String value){try{URI u=new URI(value);if(!allowedUrl(value)||u.getQuery()!=null)return false;String path=u.getPath();if("images.metahub.space".equals(u.getHost()))return path.matches("/(poster|background|logo)/(small|medium|large)/tt[0-9]{7,10}/img");if("episodes.metahub.space".equals(u.getHost()))return path.matches("/tt[0-9]{7,10}/[0-9]+/[0-9]+/w[0-9]+\\.jpg");return "m.media-amazon.com".equals(u.getHost())&&path.matches("/images/M/[A-Za-z0-9@._-]+\\.(jpg|png|webp)");}catch(Exception e){return false;}}
 public static boolean validType(String type){return "movie".equals(type)||"series".equals(type);}
 public static boolean validId(String id){return id!=null&&id.matches("tt[0-9]{7,10}");}
 public static boolean obsoleteMetadataHost(String host){if(host==null)return false;host=host.toLowerCase(java.util.Locale.ROOT).replaceAll("\\.+$","");return (host.equals("tmdb.org")||host.endsWith(".tmdb.org")||host.equals("themoviedb.org")||host.endsWith(".themoviedb.org"));}
 public static String query(String value){if(value==null)return "";String s=value.trim();if(s.length()>120)s=s.substring(0,120);return s.replaceAll("[\\p{Cntrl}]","");}
 private static String genreKey(String value){return value==null?"":value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]","");}
 public static boolean genreMatches(String query,String actual){String q=genreKey(query),a=genreKey(actual);return !q.isEmpty()&&(q.equals(a)||((q.equals("scifi")||q.equals("sciencefiction"))&&(a.equals("sciencefiction")||a.equals("scififantasy")||a.equals("scifi"))));}
}
