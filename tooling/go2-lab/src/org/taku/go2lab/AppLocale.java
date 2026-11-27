package org.taku.go2lab;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;
import java.util.Locale;

/** Shared language preference for the native shell and its loopback pages. */
final class AppLocale {
  static String language(Context context){
    String fallback=context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?"zh":"en";
    return context.getSharedPreferences("go2-ui",Context.MODE_PRIVATE).getString("language",fallback);
  }
  static void save(Context context,String language){
    if(language.equals("en")||language.equals("zh"))context.getSharedPreferences("go2-ui",Context.MODE_PRIVATE).edit().putString("language",language).apply();
  }
  static Context context(Context context){
    Configuration config=new Configuration(context.getResources().getConfiguration());
    config.setLocales(new LocaleList(language(context).equals("zh")?Locale.SIMPLIFIED_CHINESE:Locale.ENGLISH));
    return context.createConfigurationContext(config);
  }
}
