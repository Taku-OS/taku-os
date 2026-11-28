// SPDX-License-Identifier: Apache-2.0
package org.agentos.lab;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/** Host checks for the MOSS-Live key view and the default Live Talk provider of an unsaved selection. */
public final class LiveProviderDefaultsTest {
  static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
  static void write(File dir, String name, String text) throws Exception {
    Files.write(new File(dir, name).toPath(), text.getBytes(StandardCharsets.UTF_8));
  }
  static void clear(File dir) { for (File f : dir.listFiles()) f.delete(); }

  public static void main(String[] args) throws Exception {
    File dir = Files.createTempDirectory("taku-live-provider").toFile();
    ByokProviders byok = new ByokProviders(dir, new File(dir, "magpie"), "http://127.0.0.1:1");

    check(AudioConfigBridge.defaultLiveProfile(byok).equals("moss-live"), "new device defaults to MOSS-Live");
    JSONObject none = byok.mossSettings();
    check(!none.getBoolean("configured") && none.isNull("masked") && none.isNull("error"), "no MOSS key");

    write(dir, "fal.json", "{\"key\":\"fal-fixture-key-1234\"}");
    check(AudioConfigBridge.defaultLiveProfile(byok).equals("fal-grok"), "legacy fal device keeps Grok");
    write(dir, "gemini-live.json", "{\"key\":\"gemini-fixture-key-5678\"}");
    check(AudioConfigBridge.defaultLiveProfile(byok).equals("fal-grok"), "fal and Gemini keep the old Grok default");
    new File(dir, "fal.json").delete();
    check(AudioConfigBridge.defaultLiveProfile(byok).equals("gemini-live"), "Gemini-only device keeps Gemini Live");

    write(dir, "moss.json", "{\"key\":\"moss-fixture-key-abcd\"}");
    check(AudioConfigBridge.defaultLiveProfile(byok).equals("moss-live"), "a MOSS key selects MOSS-Live");
    JSONObject saved = byok.mossSettings();
    check(saved.getBoolean("configured") && saved.getString("masked").equals("•••• abcd") && saved.isNull("error"), "masked MOSS key");
    check(!saved.toString().contains("moss-fixture-key"), "settings never expose the key");
    check(byok.geminiSettings().getBoolean("configured"), "Gemini key independent of MOSS");

    write(dir, "moss.json", "{\"key\":42}");
    JSONObject unreadable = byok.mossSettings();
    check(!unreadable.getBoolean("configured") && unreadable.getString("error").equals("KEY_UNREADABLE"), "unreadable MOSS key degrades");
    check(AudioConfigBridge.defaultLiveProfile(byok).equals("moss-live"), "an unreadable MOSS key still marks a MOSS device");

    clear(dir);
    write(dir, "fal.json", "not json");
    check(AudioConfigBridge.defaultLiveProfile(byok).equals("fal-grok"), "an unreadable fal key still marks a legacy device");
    JSONObject standby = AudioConfigBridge.standby(new JSONObject());
    check(standby.getBoolean("enabled") && standby.getInt("hotTimeoutSec") == 15 && standby.getInt("coldTimeoutSec") == 60
      && standby.getBoolean("lockScreenWakeEnabled"), "standby defaults");
    JSONObject savedStandby = new JSONObject().put("liveStandby", new JSONObject().put("enabled", false).put("hotTimeoutSec", 30)
      .put("coldTimeoutSec", 120).put("lockScreenWakeEnabled", false));
    JSONObject read = AudioConfigBridge.standby(savedStandby);
    check(!read.getBoolean("enabled") && read.getInt("hotTimeoutSec") == 30 && read.getInt("coldTimeoutSec") == 120, "saved standby");
    for (Object[] bad : new Object[][]{{4, 60}, {121, 60}, {15, 14}, {15, 601}, {"15", 60}, {15.5, 60}}) {
      JSONObject invalid = new JSONObject().put("liveStandby", new JSONObject().put("enabled", true).put("hotTimeoutSec", bad[0])
        .put("coldTimeoutSec", bad[1]).put("lockScreenWakeEnabled", true));
      check(AudioConfigBridge.standby(invalid).getInt("hotTimeoutSec") == 15, "out-of-range standby reads as defaults");
    }
    clear(dir); dir.delete();
    System.out.println("PASS LiveProviderDefaultsTest: D5 default provider, MOSS key masking, unreadable-key handling and standby settings");
  }
}
