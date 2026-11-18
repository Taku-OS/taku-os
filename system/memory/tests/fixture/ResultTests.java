package org.agentos.memory;
import android.app.Instrumentation;
import android.os.Bundle;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import org.json.JSONObject;

/** Native Android result rendering: readable bilingual text and inert untrusted markup. */
public final class ResultTests extends Instrumentation {
 int checks;
 void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
 public void onCreate(Bundle b){super.onCreate(b);start();}
 public void onStart(){Bundle result=new Bundle();try{
  String original="## Plan\n\n- **Alex:** 17:00\n- Sam: 18:00";
  CharSequence text=MemoryResult.render(original);
  check(text.toString().contains("Plan")&&text.toString().contains("Alex:")&&text.toString().contains("18:00"),"facts preserved");
  check(!text.toString().contains("##")&&!text.toString().contains("**"),"headings and emphasis rendered");
  check(((Spanned)text).getSpans(0,text.length(),StyleSpan.class).length>0,"native styles present");
  check(original.contains("## Plan")&&original.contains("**Alex:**"),"source remains unchanged");
  CharSequence chinese=MemoryResult.render("## 已确认\n\n- 明天 10:00，会议室三");
  check(chinese.toString().contains("已确认")&&chinese.toString().contains("明天 10:00，会议室三"),"Chinese preserved");
  CharSequence literal=MemoryResult.render("<b>untrusted HTML</b>");
  check(literal.toString().contains("<b>untrusted HTML</b>"),"raw HTML displayed literally");
  CharSequence link=MemoryResult.render("[Open](javascript:alert(1)) and [Website](https://example.org)");
  check(link.toString().contains("Open")&&link.toString().contains("Website"),"link labels retained");
  check(((Spanned)link).getSpans(0,link.length(),URLSpan.class).length==0,"model links are inert");
  result.putString("result",new JSONObject().put("passed",true).put("checks",checks).toString());finish(-1,result);
 }catch(Throwable e){result.putString("failure",e.toString());finish(0,result);}}
}
