package org.taku.weather;

import android.content.*;
import android.app.job.*;
import org.json.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** The app, widget and Agent all read this same durable cache. Never synthesizes weather. */
public final class WeatherData {
    private static final ScheduledExecutorService IO=Executors.newSingleThreadScheduledExecutor();
    private static final RequestInterval REFRESH_INTERVAL=new RequestInterval(30_000);
    private static final RequestInterval SEARCH_INTERVAL=new RequestInterval(2_000);
    private static boolean refreshScheduled;
    private static boolean fetching;
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("weather",Context.MODE_PRIVATE);}
    public static synchronized JSONObject read(Context c) throws JSONException {
        SharedPreferences p=prefs(c);
        JSONObject o=new JSONObject().put("city",p.getString("city","Guangzhou")).put("latitude",Double.parseDouble(p.getString("latitude","23.1291")))
          .put("longitude",Double.parseDouble(p.getString("longitude","113.2644"))).put("source","Open-Meteo").put("attribution","Weather data by Open-Meteo.com")
          .put("fetching",fetching).put("refreshScheduled",refreshScheduled)
          .put("refreshRetryAfterMs",REFRESH_INTERVAL.remaining(android.os.SystemClock.elapsedRealtime()))
          .put("searchRetryAfterMs",SEARCH_INTERVAL.remaining(android.os.SystemClock.elapsedRealtime()))
          .put("fetchedAt",p.getLong("fetchedAt",0)).put("error",p.getString("error",""));
        String raw=p.getString("current",null);
        if(raw==null)o.put("available",false);
        else o.put("available",true).put("current",new JSONObject(raw));
        return o.put("stale",p.getLong("fetchedAt",0)==0||System.currentTimeMillis()-p.getLong("fetchedAt",0)>3600000);
    }
    public static synchronized JSONObject location(Context c,JSONObject data) throws JSONException {
        String city=data.getString("city").trim();double lat=data.getDouble("latitude"),lon=data.getDouble("longitude");
        if(city.isEmpty()||city.length()>100||!Double.isFinite(lat)||!Double.isFinite(lon)||lat < -90||lat>90||lon < -180||lon>180)throw new IllegalArgumentException("INVALID_ARGUMENT: city and valid latitude/longitude are required");
        if(!prefs(c).edit().putString("city",city).putString("latitude",Double.toString(lat)).putString("longitude",Double.toString(lon)).remove("current").remove("fetchedAt").remove("error").commit())throw new IllegalStateException("STATE_WRITE_FAILED");
        WeatherWidget.update(c);refresh(c);return read(c);
    }
    private static JSONObject fetch(String url) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(10000);connection.setReadTimeout(10000);
        try {int status=connection.getResponseCode();if(status!=200)throw new IllegalStateException("HTTP_"+status);
            try(java.io.InputStream input=connection.getInputStream()){return new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
        }finally{connection.disconnect();}
    }
    public static JSONArray search(String name) throws Exception {
        if(name==null||name.trim().length()<2||name.length()>100)throw new IllegalArgumentException("INVALID_ARGUMENT: city query needs 2–100 characters");
        if(!SEARCH_INTERVAL.acquire(android.os.SystemClock.elapsedRealtime()))throw new IllegalStateException("RATE_LIMITED: wait before searching again; see get.searchRetryAfterMs");
        JSONObject r=fetch("https://geocoding-api.open-meteo.com/v1/search?name="+URLEncoder.encode(name,"UTF-8")+"&count=8&language=en&format=json");
        return r.optJSONArray("results")==null?new JSONArray():r.getJSONArray("results");
    }
    public static synchronized void refresh(Context c) {
        if(fetching||refreshScheduled)return;
        if(!REFRESH_INTERVAL.acquire(android.os.SystemClock.elapsedRealtime())) {
            refreshScheduled=true;Context app=c.getApplicationContext();
            IO.schedule(()->{synchronized(WeatherData.class){refreshScheduled=false;refresh(app);}},
                REFRESH_INTERVAL.remaining(android.os.SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);
            return;
        }
        fetching=true;Context app=c.getApplicationContext();
        IO.execute(()->{
            String lat=prefs(app).getString("latitude","23.1291"),lon=prefs(app).getString("longitude","113.2644");
            try {
                JSONObject result=fetch("https://api.open-meteo.com/v1/forecast?latitude="+lat+"&longitude="+lon+"&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m&timezone=auto");
                JSONObject current=result.getJSONObject("current").put("timezone",result.getString("timezone"));
                synchronized(WeatherData.class){if(sameLocation(app,lat,lon)) prefs(app).edit().putString("current",current.toString()).putLong("fetchedAt",System.currentTimeMillis()).putString("error","").commit();}
            }catch(Exception e){synchronized(WeatherData.class){if(sameLocation(app,lat,lon))prefs(app).edit().putString("error",e.getMessage()==null?"NETWORK_ERROR":e.getMessage()).commit();}}
            finally {synchronized(WeatherData.class){fetching=false;}WeatherWidget.update(app); if(!sameLocation(app,lat,lon))refresh(app);}
        });
    }
    private static boolean sameLocation(Context c,String lat,String lon){return lat.equals(prefs(c).getString("latitude","23.1291"))&&lon.equals(prefs(c).getString("longitude","113.2644"));}
    public static void schedule(Context c){c.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(4103,new ComponentName(c,RefreshJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(1800000).setPersisted(true).build());}
    public static String condition(int code){if(code==0)return "Clear";if(code==1)return "Mainly clear";if(code==2)return "Partly cloudy";if(code==3)return "Overcast";if(code<=48)return "Fog";if(code<=67)return "Rain";if(code<=77)return "Snow";if(code<=82)return "Showers";if(code<=86)return "Snow";return "Thunderstorm";}
}
