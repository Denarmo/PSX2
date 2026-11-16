package com.izzy2lost.psx2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

import io.github.ecp.Ecp;

/**
 * ECP `configure` outside a session (SPEC 6.6): a frontend's explicit broadcast once PSX2 ECP is
 * installed and whenever the player's BIOS, game folders or settings change there. It does what
 * the session request does, off the main thread; there is no answer.
 */
public final class EcpConfigureReceiver extends BroadcastReceiver {
    private static final String TAG = "EcpConfigure";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Ecp.ACTION_CONFIGURE.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Ecp.EXTRA_CONFIGURE);
        if (text == null || text.isEmpty()) return;
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                EcpConfigure.apply(app, new JSONObject(text));
            } catch (Throwable t) {
                Log.w(TAG, "configure failed", t);
            } finally {
                pending.finish();
            }
        }, "ecp-configure").start();
    }
}
