package org.robolectric.shadows;

import android.os.SystemClock;
import org.robolectric.internal.Implements;

@Implements(value = SystemClock.class, callThroughByDefault = true)
public class ShadowSystemClock {
    private static long bootedAt = now();

    private static long now() {
        return System.currentTimeMillis();
    }

    /**
     * Sets the current wall time, in milliseconds.  Requires the calling
     * process to have appropriate permissions.
     *
     * @return if the clock was successfully set to the specified time.
     */
    native public static boolean setCurrentTimeMillis(long millis);

    /**
     * Returns milliseconds since boot, not counting time spent in deep sleep.
     * <b>Note:</b> This value may get reset occasionally (before it would
     * otherwise wrap around).
     *
     * @return milliseconds of non-sleep uptime since boot.
     */
    public static long uptimeMillis() {
        return now() - bootedAt;
    }

    /**
     * Returns milliseconds since boot, including time spent in sleep.
     *
     * @return elapsed milliseconds since boot.
     */
    public static long elapsedRealtime() {
        return uptimeMillis();
    }

    /**
     * Returns milliseconds running in the current thread.
     *
     * @return elapsed milliseconds in the thread
     */
    public static long currentThreadTimeMillis() {
        return uptimeMillis();
    }

    /**
     * Returns microseconds running in the current thread.
     *
     * @return elapsed microseconds in the thread
     *
     * @hide
     */
    public static long currentThreadTimeMicro() {
        return uptimeMillis() * 1000;
    }

    /**
     * Returns current wall time in  microseconds.
     *
     * @return elapsed microseconds in wall time
     *
     * @hide
     */
    public static long currentTimeMicro() {
        return now() * 1000;
    }
}
