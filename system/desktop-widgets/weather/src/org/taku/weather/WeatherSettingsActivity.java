package org.taku.weather;
import android.app.*;
import android.os.*;
import android.widget.*;
import org.json.*;
public final class WeatherSettingsActivity extends Activity {
 private TextView status;private EditText city;
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final Runnable update=new Runnable(){public void run(){try{JSONObject d=WeatherData.read(WeatherSettingsActivity.this);String text=d.getString("city")+"\n\n";
  if(d.getBoolean("available")){JSONObject c=d.getJSONObject("current");text+=Math.round(c.getDouble("temperature_2m"))+"°C · "+WeatherData.condition(c.getInt("weather_code"))+"\nFeels like "+c.getDouble("apparent_temperature")+"°C\nHumidity "+c.getInt("relative_humidity_2m")+"%\nWind "+c.getDouble("wind_speed_10m")+" km/h\n\nUpdated "+c.getString("time")+"\n"+c.getString("timezone");}
  else text+="Weather unavailable";
  if(d.getBoolean("fetching"))text+="\nRefreshing…";if(d.getBoolean("refreshScheduled"))text+="\nRefresh queued ("+(d.getLong("refreshRetryAfterMs")+999)/1000+"s)";if(d.getBoolean("stale"))text+="\nWaiting for current weather";if(!d.getString("error").isEmpty())text+="\n"+d.getString("error");status.setText(text);
 }catch(Exception e){status.setText(e.getMessage());}handler.postDelayed(this,1000);}};
 @Override public void onCreate(Bundle b){super.onCreate(b);LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(28,64,28,32);box.setBackgroundColor(0xfff8f9fb);status=new TextView(this);status.setTextSize(22);box.addView(status);
  Button refresh=new Button(this);refresh.setText("Refresh weather");refresh.setOnClickListener(v->WeatherData.refresh(this));box.addView(refresh);
  city=new EditText(this);city.setHint("Search city");city.setSingleLine(true);box.addView(city);Button search=new Button(this);search.setText("Choose city");box.addView(search);
  search.setOnClickListener(v->{search.setEnabled(false);String query=city.getText().toString();new Thread(()->{try{JSONArray results=WeatherData.search(query);String[] labels=new String[results.length()];for(int i=0;i<labels.length;i++){JSONObject r=results.getJSONObject(i);labels[i]=r.getString("name")+", "+r.optString("admin1")+", "+r.optString("country");}runOnUiThread(()->{if(isFinishing()||isDestroyed())return;new AlertDialog.Builder(this).setTitle("Choose city").setItems(labels,(dialog,index)->{try{JSONObject r=results.getJSONObject(index);WeatherData.location(this,new JSONObject().put("city",r.getString("name")).put("latitude",r.getDouble("latitude")).put("longitude",r.getDouble("longitude")));}catch(Exception e){Toast.makeText(this,e.getMessage(),1).show();}}).setNegativeButton("Cancel",null).show();});}catch(Exception e){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())Toast.makeText(this,e.getMessage(),1).show();});}finally{runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())search.setEnabled(true);});}}).start();});
  TextView attribution=new TextView(this);attribution.setText("Weather data by Open-Meteo.com");attribution.setPadding(0,24,0,0);box.addView(attribution);ScrollView scroll=new ScrollView(this);scroll.addView(box);setContentView(scroll);scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});WeatherData.refresh(this);
 }
 @Override public void onResume(){super.onResume();handler.post(update);}
 @Override public void onPause(){handler.removeCallbacks(update);super.onPause();}
}
