package android.content.pm; import java.util.*; import android.content.Intent;
public class PackageManager {
public static final int GET_META_DATA=8;
public static class NameNotFoundException extends Exception {}
public boolean isDeviceUpgrading(){return true;}
public int getPackageUid(String n,int flags)throws NameNotFoundException{return 12345;}
public List<ResolveInfo> services=new ArrayList<>();
public List<ResolveInfo> queryIntentServices(Intent i,int flags){List<ResolveInfo> out=new ArrayList<>();for(ResolveInfo r:services)if(i.getPackage()==null||i.getPackage().equals(r.serviceInfo.packageName))out.add(r);return out;}
public static final int MATCH_DEFAULT_ONLY=1,MATCH_DIRECT_BOOT_AWARE=2,MATCH_DIRECT_BOOT_UNAWARE=4; public static final String FEATURE_AUTOMOTIVE="car",FEATURE_WATCH="watch";
public List<ResolveInfo> homes=new ArrayList<>(); public List<ResolveInfo> queryIntentActivities(Intent i,int flags){List<ResolveInfo> out=new ArrayList<>(); for(ResolveInfo r:homes) if(i.getPackage()==null||i.getPackage().equals(r.activityInfo.packageName))out.add(r);return out;}
public ResolveInfo resolveActivity(Intent i,int flags){return null;} public boolean hasSystemFeature(String f){return false;}}
