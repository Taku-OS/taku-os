package org.agentos.agent.lifecycleprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;

/** Test-only instrumentation: replay the reported order against the installed, real RN host. */
public final class HostLifecycleProbe extends Instrumentation {
  private Bundle args;
  private Object host;
  private Method resume;
  private Method pause;
  private Method destroy;
  private Method context;
  private Activity home;
  private Activity chat;
  private boolean baseline;
  private final ArrayList<String> checks = new ArrayList<>();

  @Override public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    args = arguments;
    baseline = Boolean.parseBoolean(args.getString("baseline", "false"));
    start();
  }

  @Override public void onStart() {
    Bundle result = new Bundle();
    try {
      home = open("org.agentos.agent.home.HomeActivity");
      chat = open("org.agentos.agent.MainActivity");
      Field poolField = home.getClass().getDeclaredField(args.getString("pool"));
      poolField.setAccessible(true);
      Object pool = poolField.get(home);
      Field hostField = pool.getClass().getDeclaredField(args.getString("host"));
      hostField.setAccessible(true);
      host = hostField.get(pool);
      resume = method(args.getString("resume"), 2);
      pause = method(args.getString("pause"), 1);
      destroy = method(args.getString("destroy"), 1);
      context = method(args.getString("context"), 0);
      for (int i = 0; i < 100 && context.invoke(host) == null; i++) Thread.sleep(100);
      if (context.invoke(host) == null) throw new AssertionError("React context never initialized");

      Throwable[] failure = new Throwable[1];
      runOnMainSync(() -> {
        try {
          call(resume, chat, chat);
          call(resume, home, home);
          call(pause, chat);
          check("late-chat-pause", "RESUMED", home);
          if (baseline) {
            throw new AssertionError("Baseline unexpectedly accepted the reported invalid order");
          }
          call(pause, home);
          check("last-pause", "BEFORE_RESUME", home);
          call(resume, chat, chat);
          call(resume, home, home);
          call(pause, home);
          check("other-resumed-page-restored", "RESUMED", chat);
          call(destroy, home);
          check("old-destroy", "RESUMED", chat);
          call(resume, home, home);
          call(destroy, home);
          check("current-destroy-restores-other", "RESUMED", chat);
          call(pause, chat);
          check("ordinary-pause", "BEFORE_RESUME", chat);
          call(destroy, chat);
          check("last-destroy", "BEFORE_CREATE", null);
          call(resume, chat, chat);
          check("resume-after-destroy", "RESUMED", chat);
        } catch (Throwable error) {
          failure[0] = unwrap(error);
        } finally {
          // Restore the real top activity after the deliberately reordered callbacks.
          try { call(pause, home); } catch (Throwable ignored) { }
          try { call(resume, chat, chat); } catch (Throwable ignored) { }
        }
      });
      Throwable error = failure[0];
      if (baseline) {
        if (!(error instanceof AssertionError) || !String.valueOf(error.getMessage()).contains("Pausing an activity that is not the current activity")) {
          throw new AssertionError("Expected the original RN identity assertion", error);
        }
        result.putString("result", "EXPECTED_BASELINE_ASSERTION");
        result.putString("assertion", error.getMessage());
      } else {
        if (error != null) throw new AssertionError("Host lifecycle regression", error);
        ActivityMonitor monitor = addMonitor("org.agentos.agent.MainActivity", null, false);
        runOnMainSync(() -> chat.recreate());
        Activity replacement = waitForMonitorWithTimeout(monitor, 10000);
        removeMonitor(monitor);
        if (replacement == null || replacement == chat) throw new AssertionError("Activity recreation did not create a new instance");
        chat = replacement;
        Throwable[] recreateFailure = new Throwable[1];
        for (int i = 0; i < 100; i++) {
          waitForIdleSync();
          runOnMainSync(() -> {
            try { check("activity-recreation", "RESUMED", chat); recreateFailure[0] = null; }
            catch (Throwable checkError) { recreateFailure[0] = checkError; }
          });
          if (recreateFailure[0] == null) break;
          Thread.sleep(100);
        }
        if (recreateFailure[0] != null) throw new AssertionError("Recreated Activity did not recover", recreateFailure[0]);
        result.putString("result", "PASS");
      }
      result.putString("checks", checks.toString());
      result.putString("hostClass", host.getClass().getName());
      finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("result", "FAIL");
      result.putString("error", error.toString());
      if (error.getCause() != null) result.putString("cause", error.getCause().toString());
      finish(Activity.RESULT_CANCELED, result);
    }
  }

  private Activity open(String name) {
    Intent intent = new Intent().setClassName("org.agentos.agent", name)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    Activity activity = startActivitySync(intent);
    waitForIdleSync();
    return activity;
  }

  private Method method(String name, int arity) throws Exception {
    for (Method method : host.getClass().getMethods()) {
      if (method.getName().equals(name) && method.getParameterTypes().length == arity) return method;
    }
    throw new NoSuchMethodException(name + "/" + arity);
  }

  private void call(Method method, Object... values) throws Exception { method.invoke(host, values); }

  private void check(String name, String expectedState, Activity expectedActivity) throws Exception {
    Object reactContext = context.invoke(host);
    Object state = reactContext.getClass().getMethod("getLifecycleState").invoke(reactContext);
    Object activity = reactContext.getClass().getMethod("getCurrentActivity").invoke(reactContext);
    if (!expectedState.equals(state.toString()) || activity != expectedActivity) {
      throw new AssertionError(name + ": state=" + state + ", activity=" + activity);
    }
    checks.add(name);
  }

  private static Throwable unwrap(Throwable error) {
    while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
    return error;
  }
}
