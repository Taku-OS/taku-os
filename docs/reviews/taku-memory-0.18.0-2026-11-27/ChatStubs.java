package org.agentos.lab;
import org.json.*;
import java.util.*;
final class MemoryGen { static final Set<String> owned=new HashSet<>(); static boolean owns(String t){return owned.contains(t);} }
final class MemoryBridge { static final List<JSONObject> calls=Collections.synchronizedList(new ArrayList<>()); static JSONObject provider(String method,JSONObject p){calls.add(new JSONObject().put("method",method).put("params",p));return new JSONObject();} }
final class HarnessCatalog { static JSONObject replyContract(){return new JSONObject().put("contractHash","test").put("catalogVersion","test").put("components",new JSONObject());} }
