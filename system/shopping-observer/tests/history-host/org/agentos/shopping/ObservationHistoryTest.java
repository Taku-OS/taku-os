// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/** Runs the actual durable history adapter against in-memory Android storage. */
public final class ObservationHistoryTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static LivePage page(String host, String title) {
        return LivePage.of("com.zhiliaoapp.musically",100,host,title);
    }
    private static final class Store implements SharedPreferences {
        final Map<String,Object> values=new HashMap<>();
        boolean failNextCommit;
        @Override public long getLong(String key,long fallback) {
            return values.get(key) instanceof Long value ? value : fallback;
        }
        @Override public String getString(String key,String fallback) {
            if(key.equals("items")) throw new AssertionError("obsolete item history was read");
            return values.get(key) instanceof String value ? value : fallback;
        }
        @Override public Editor edit() {
            return new Editor() {
                final Map<String,Object> writes=new HashMap<>();
                public Editor putLong(String key,long value) { writes.put(key,value);return this; }
                public Editor putString(String key,String value) { writes.put(key,value);return this; }
                public void apply() { values.putAll(writes); }
                public boolean commit() {
                    if(failNextCommit) { failNextCommit=false;return false; }
                    values.putAll(writes);return true;
                }
            };
        }
    }
    private static Context context(Store store) {
        return new Context() {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {
                check(name.equals("observation-history")&&mode==Context.MODE_PRIVATE,"private history store retained");
                return store;
            }
        };
    }
    public static void main(String[] args) {
        long now=System.currentTimeMillis();
        LivePage card=page("Same presenter","Blue hoodie"), noCard=page("Same presenter","");
        LivePage other=page("Another presenter","Red shirt");
        Store history=new Store();
        String oldItems=new JSONObject().put(card.contentKey(),now).put(noCard.hostKey(),now).toString();
        history.values.put("items",oldItems);
        Context first=context(history);
        check(!ObservationHistory.suppressed(first,card),"old card history alone cannot block reminders after upgrade");
        check(!ObservationHistory.suppressed(first,noCard),"old room reminder history alone cannot block reminders after upgrade");
        check(!ObservationHistory.suppressed(context(history),card),"a recreated context must not restore obsolete item suppression");

        history.values.put("published",now-59000);
        check(ObservationHistory.suppressed(first,card),"recent global reminder remains blocked for the same item");
        check(ObservationHistory.suppressed(first,other),"recent global reminder remains blocked for another room");
        check(ObservationHistory.suppressed(context(history),card),"global cooldown survives reconnection");
        history.values.put("published",now-60001);
        check(!ObservationHistory.suppressed(context(history),card),"expired global cooldown ignores a recent legacy item entry");
        history.values.put("published",now+60000);
        check(ObservationHistory.suppressed(first,card),"clock rollback cannot bypass the ordinary cooldown");

        long before=System.currentTimeMillis();
        ObservationHistory.published(first,card);
        long written=(Long)history.values.get("published");
        check(written>=before&&written<=System.currentTimeMillis(),"real publication records the global time");
        check(oldItems.equals(history.values.get("items")),"publication neither refreshes nor deletes old item history");
        check(ObservationHistory.suppressed(context(history),other),"publication globally suppresses immediate duplicate reminders");

        Store visual=new Store();Context visualContext=context(visual);
        check(ObservationHistory.claimVision(visualContext,noCard),"first opted-in no-card visual attempt can be claimed");
        check(!ObservationHistory.claimVision(context(visual),page("Other presenter","")),"visual global sixty-second cost interval survives reconnection");
        visual.values.put("vision",System.currentTimeMillis()-60001);
        check(!ObservationHistory.claimVision(context(visual),noCard),"same-room visual thirty-minute cost limit survives reminder dedup removal");
        check(ObservationHistory.claimVision(context(visual),page("Other presenter","")),"another room can be checked after the visual global interval");
        visual.values.put("vision",System.currentTimeMillis()-60001);
        visual.values.put("vision-rooms",new JSONObject().put(noCard.hostKey(),System.currentTimeMillis()-1800001).toString());
        check(ObservationHistory.claimVision(context(visual),noCard),"independent visual room limit expires after thirty minutes");

        Store failed=new Store();failed.failNextCommit=true;
        check(!ObservationHistory.claimVision(context(failed),noCard),"failed durable cost claim cannot authorize a model request");
        check(!failed.values.containsKey("vision")&&!failed.values.containsKey("vision-rooms"),"failed commit leaves no successful paid claim");
        check(ObservationHistory.claimVision(context(failed),noCard),"a later successful commit remains usable");
        System.out.println(assertions+" durable observation history assertions passed");
    }
}
