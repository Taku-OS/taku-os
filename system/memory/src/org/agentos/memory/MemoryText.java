package org.agentos.memory;
import android.content.Context;
/** Localizes persisted status codes, including statuses saved by pre-0.4 versions. */
final class MemoryText {
 static String status(Context c,String raw){
  if(raw.startsWith("batch_done:"))try{return c.getString(R.string.analysis_batch_done,Integer.parseInt(raw.substring(11)));}catch(NumberFormatException ignored){}
  if(raw.startsWith("最近一批已完成（"))try{return c.getString(R.string.analysis_batch_done,Integer.parseInt(raw.replaceAll("[^0-9]","")));}catch(NumberFormatException ignored){}
  return c.getString(switch(raw){
   case "connected","已连接"->R.string.connected;
   case "surface_unavailable","建议暂时无法展示，请检查系统展示组件版本"->R.string.surface_unavailable;
   case "stale","结果已过期，排队记录保留"->R.string.analysis_stale;
   case "backoff","服务暂时不可用，队列保留并退避重试"->R.string.analysis_backoff;
   case "尚无分析请求"->R.string.no_analysis;
   case "disconnected","尚未连接"->R.string.disconnected;
   default->raw.isEmpty()?R.string.no_analysis:R.string.service_unavailable;
  });
 }
}
