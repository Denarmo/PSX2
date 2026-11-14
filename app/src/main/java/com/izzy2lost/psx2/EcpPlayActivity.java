package com.izzy2lost.psx2;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.InputEvent;

import androidx.annotation.NonNull;
import androidx.drawerlayout.widget.DrawerLayout;

import io.github.ecp.EcpEngine;

/**
 * The Emulator Control Protocol entry point (io.github.ecp.action.PLAY, see ECP.md). It is the
 * normal PSX2 game screen started for one game: with the ECP extras a frontend controls it
 * through {@link EcpSession}; without them (level 0) it boots the intent's data and PSX2's own
 * menus work as usual.
 */
public class EcpPlayActivity extends MainActivity {
    private static final String TAG = "EcpPlayActivity";

    private EcpSession mSession;
    private boolean mFinishing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        mSession = EcpSession.from(this, intent);
        if (mSession != null) {
            mSession.start();
        } else if (intent != null && intent.getData() != null) {
            startSessionGame(intent.getDataString());
        } else {
            Log.w(TAG, "Started without a game");
            finish();
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        // The frontend brings the game back with REORDER_TO_FRONT, often with the same extras.
        if (mSession != null && EcpEngine.Companion.isBringBack(intent, mSession.intentSession())) return;
        EcpSession next = EcpSession.from(this, intent);
        if (next == null) return;
        Log.i(TAG, "New ECP session replaces the current one");
        setIntent(intent);
        if (mSession != null) mSession.onActivityDestroyed();
        mSession = next;
        mSession.start();
    }

    @Override
    protected void onDestroy() {
        if (mSession != null) mSession.onActivityDestroyed();
        super.onDestroy();
    }

    /** Called on the UI thread once the frontend's hello settled the capabilities. */
    void onSessionNegotiated() {
        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        if (drawer != null) {
            drawer.setDrawerLockMode(isFrontendMenuAttached()
                    ? DrawerLayout.LOCK_MODE_LOCKED_CLOSED : DrawerLayout.LOCK_MODE_UNLOCKED);
        }
    }

    /** SPEC 6.1: resume puts the engine back in the foreground if it isn't. */
    void bringToFront() {
        if (isFinishing() || isDestroyed() || hasWindowFocus()) return;
        try {
            startActivity(new Intent(this, EcpPlayActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to come back to the front", e);
        }
    }

    void finishSession() {
        if (mFinishing) return;
        mFinishing = true;
        // onDestroy stops the VM and ends the process, as PSX2's own exit does.
        finishAndRemoveTask();
    }

    @Override
    protected boolean isLaunchedForGame() {
        return true;
    }

    /** Never boot the PS2 menu while waiting for launch. */
    @Override
    public boolean isBootBiosOnStartEnabled() {
        return false;
    }

    @Override
    protected boolean isFrontendMenuAttached() {
        return mSession != null && mSession.isMenuAttached();
    }

    @Override
    protected void onFrontendMenuRequested(String source) {
        if (mSession != null) mSession.requestMenu(source);
    }

    @Override
    protected boolean isFrontendPauseRequested() {
        return mSession != null && mSession.isPauseHeld();
    }

    @Override
    protected void applySessionSettings() {
        if (mSession != null) mSession.applyLaunchSettings();
    }

    @Override
    protected boolean onEmulationThreadEnded(boolean started, String startupError) {
        if (mSession == null) return false;
        mSession.onVmThreadEnded(started, startupError);
        return true;
    }

    @Override
    protected boolean onSessionInputEvent(InputEvent event) {
        return mSession != null && mSession.onInput(event);
    }
}
