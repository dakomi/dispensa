package eu.frigo.dispensa.util;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Simple file-based debug logger for diagnosing sync issues.
 *
 * <p>Writes timestamped log lines to {@code dispensa_debug.log} inside
 * {@link Context#getFilesDir()}.  All writes also pass through
 * {@link android.util.Log} so messages appear in logcat as usual.
 *
 * <p>The log file can be shared with developers via the
 * {@code pref_debug_export_log} preference in Settings, which triggers a
 * {@link android.content.Intent#ACTION_SEND} share sheet using
 * {@link androidx.core.content.FileProvider}.
 *
 * <p>Usage:
 * <pre>
 *   DebugLogger.i("MyTag", "something happened");
 *   DebugLogger.e("MyTag", "something failed", exception);
 * </pre>
 */
public final class DebugLogger {

    private static final String TAG = "DebugLogger";
    /** Name of the log file stored in {@link Context#getFilesDir()}. */
    public static final String LOG_FILE_NAME = "dispensa_debug.log";

    /** Maximum log file size before it is truncated (1 MiB). */
    private static final long MAX_LOG_SIZE_BYTES = 1024L * 1024L;

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    // Static singleton — context is never stored; only the file path is used.
    private static volatile File sLogFile = null;

    private DebugLogger() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /** Logs an info-level message. */
    public static void i(String tag, String message) {
        Log.i(tag, message);
        write("I", tag, message, null);
    }

    /** Logs a warning-level message. */
    public static void w(String tag, String message) {
        Log.w(tag, message);
        write("W", tag, message, null);
    }

    /** Logs a warning-level message with a throwable. */
    public static void w(String tag, String message, Throwable throwable) {
        Log.w(tag, message, throwable);
        write("W", tag, message, throwable);
    }

    /** Logs an error-level message with a throwable. */
    public static void e(String tag, String message, Throwable throwable) {
        Log.e(tag, message, throwable);
        write("E", tag, message, throwable);
    }

    /** Logs an error-level message. */
    public static void e(String tag, String message) {
        Log.e(tag, message);
        write("E", tag, message, null);
    }

    /**
     * Initialises the log file reference.  Call once from
     * {@link android.app.Application#onCreate()}.
     */
    public static void init(Context context) {
        sLogFile = new File(context.getFilesDir(), LOG_FILE_NAME);
        i(TAG, "=== Dispensa debug log opened ===");
    }

    /**
     * Returns the log {@link File}, or {@code null} if {@link #init} has not been called.
     */
    public static File getLogFile() {
        return sLogFile;
    }

    /**
     * Deletes the existing log file and starts a fresh one.
     */
    public static void clear() {
        File f = sLogFile;
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        i(TAG, "=== Log cleared ===");
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static synchronized void write(String level, String tag, String message,
            Throwable throwable) {
        File f = sLogFile;
        if (f == null) return;

        // Rotate the file when it grows too large
        if (f.length() > MAX_LOG_SIZE_BYTES) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(f, /* append= */ true))) {
            String timestamp = DATE_FORMAT.format(new Date());
            bw.write(timestamp + " " + level + "/" + tag + ": " + message);
            bw.newLine();
            if (throwable != null) {
                bw.write("  " + throwable);
                bw.newLine();
                for (StackTraceElement el : throwable.getStackTrace()) {
                    bw.write("    at " + el);
                    bw.newLine();
                }
                Throwable cause = throwable.getCause();
                if (cause != null) {
                    bw.write("  Caused by: " + cause);
                    bw.newLine();
                    for (StackTraceElement el : cause.getStackTrace()) {
                        bw.write("    at " + el);
                        bw.newLine();
                    }
                }
            }
        } catch (IOException ignored) {
            // Cannot log the logging failure — silently swallow to avoid infinite loops.
        }
    }
}
