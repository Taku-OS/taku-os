package android.content;
import java.util.*;
public class Intent {
 public static final String ACTION_MAIN="android.intent.action.MAIN", CATEGORY_HOME="android.intent.category.HOME", CATEGORY_DEFAULT="android.intent.category.DEFAULT";
 public static final String ACTION_ASSIST="android.intent.action.ASSIST"; public static final int FLAG_ACTIVITY_NEW_TASK=1;
 private String action,pkg; private Object component,selector,data; private Set<String> categories=new HashSet<>();
 public Intent(String a){action=a;} public String getAction(){return action;}
 public Intent addCategory(String c){categories.add(c);return this;} public boolean hasCategory(String c){return categories.contains(c);} public Set<String> getCategories(){return categories;}
 public Intent setPackage(String p){pkg=p;return this;} public String getPackage(){return pkg;}
 public Object getComponent(){return component;} public Intent setComponent(Object v){component=v;return this;}
 public Object getSelector(){return selector;} public Intent setSelector(Object v){selector=v;return this;}
 public Object getData(){return data;} public Intent setData(Object v){data=v;return this;}
 public Intent setFlags(int f){return this;}
}
