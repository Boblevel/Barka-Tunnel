package com.barkatunnel.app.ipfinder;

import android.media.AudioAttributes;
import android.os.SystemClock;
import android.os.VibrationEffect;
import java.util.ArrayList;
import java.util.List;
import org.robolectric.annotation.ClassName;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadows.ShadowSystemVibrator;

/** Records requests, not simulated motor duration (predefined effects have unknown duration). */
@Implements(className = "android.os.SystemVibrator", isInAndroidSdk = false)
public class RecordingSystemVibrator extends ShadowSystemVibrator {
    private static final List<Long> requestTimes = new ArrayList<>();
    private static final List<VibrationEffect> requestedEffects = new ArrayList<>();

    public static List<Long> getRequestTimes() { return new ArrayList<>(requestTimes); }
    public static List<VibrationEffect> getRequestedEffects() { return new ArrayList<>(requestedEffects); }
    @Resetter public static void resetRequests() { requestTimes.clear(); requestedEffects.clear(); }

    @Override
    @Implementation(minSdk = 29, maxSdk = 30)
    protected void vibrate(int uid, String pkg, VibrationEffect effect, String reason, AudioAttributes attrs) {
        requestTimes.add(SystemClock.uptimeMillis());
        requestedEffects.add(effect);
        super.vibrate(uid, pkg, effect, reason, attrs);
    }

    @Override
    @Implementation(minSdk = 31)
    protected void vibrate(int uid, String pkg,
            @ClassName("android.os.VibrationEffect") Object effect, String reason,
            @ClassName("android.os.VibrationAttributes") Object attrs) {
        requestTimes.add(SystemClock.uptimeMillis());
        requestedEffects.add((VibrationEffect) effect);
        super.vibrate(uid, pkg, effect, reason, attrs);
    }
}
