package android.content.pm;
import java.util.HashSet;
import java.util.Set;
public final class PackageManager {
    public static final int SIGNATURE_MATCH=0,SIGNATURE_NO_MATCH=-3;
    public final Set<String> platformSigned=new HashSet<>(Set.of("org.agentos.assistant","org.agentos.surfaces","com.android.systemui"));
    public int assistantUid=1000;
    public int checkSignatures(String platform,String app){return "android".equals(platform)&&platformSigned.contains(app)?SIGNATURE_MATCH:SIGNATURE_NO_MATCH;}
    public ApplicationInfo getApplicationInfo(String app,int flags)throws NameNotFoundException {
        if(!"org.agentos.assistant".equals(app))throw new NameNotFoundException();
        ApplicationInfo info=new ApplicationInfo();info.uid=assistantUid;return info;
    }
    public static final class NameNotFoundException extends Exception {}
}
