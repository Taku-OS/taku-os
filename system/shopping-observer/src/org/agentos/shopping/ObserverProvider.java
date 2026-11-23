// SPDX-License-Identifier: Apache-2.0
package org.agentos.shopping;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import java.util.Set;
import org.taku.tryon.common.AgentAccess;
import org.taku.tryon.contract.LiveTryOnContract;

/** Authenticated Agent controls. No API returns screenshots, room text, model prompts, or private tickets. */
public final class ObserverProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String argument, Bundle extras) {
        int callerUid = Binder.getCallingUid();
        if (LiveTryOnContract.REDEEM.equals(method)) return argument == null
            ? LiveAdmissionStore.redeem(getContext(), callerUid, extras) : ShoppingObserverService.reply(false, "invalid_admission");
        AgentAccess.enforce(getContext(), callerUid);
        long identity = Binder.clearCallingIdentity();
        try {
            if (argument != null) return ShoppingObserverService.reply(false, "unexpected_argument");
            if ("status".equals(method)) {
                if (extras != null && !extras.isEmpty()) return ShoppingObserverService.reply(false, "unexpected_parameters");
                Bundle result = ShoppingObserverService.connected() ? ShoppingObserverService.command("status", Bundle.EMPTY)
                    : ShoppingObserverService.reply(true, "service_not_connected");
                // Settings and discovery remain available even while accessibility is disabled.
                result.putBoolean("connected", ShoppingObserverService.connected());
                result.putAll(Preferences.read(getContext()));
                result.putStringArray("commands", new String[]{"status", "configure", "tryOn", "cancel", "retry", "select"});
                return result;
            }
            if ("configure".equals(method)) {
                try { boolean saved = Preferences.configure(getContext(), extras); return ShoppingObserverService.reply(saved, saved ? "configured" : "settings_write_failed"); }
                catch (IllegalArgumentException invalid) { return ShoppingObserverService.reply(false, invalid.getMessage()); }
            }
            if (method == null || !Set.of("tryOn", "cancel", "retry", "select").contains(method)) return ShoppingObserverService.reply(false, "unknown_command");
            if (extras != null && !extras.isEmpty() && (!"select".equals(method)
                    || !Set.of("x", "y").containsAll(extras.keySet()))) return ShoppingObserverService.reply(false, "unexpected_parameters");
            return ShoppingObserverService.command(method, extras);
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { throw new UnsupportedOperationException("use_call"); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read_only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("read_only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("read_only"); }
}
