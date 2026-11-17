package pro.sketchware.ai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Remembers the latest logs that test apps send to Sketchware (the logcat reader's broadcasts),
 * also while the logcat reader isn't open, so a crash that happened before the user opened it can
 * still be fixed with RKD AI. Kept in memory only.
 */
public final class AiLogCollector {

    public static final String ACTION_NEW_DEBUG_LOG = "pro.sketchware.ACTION_NEW_DEBUG_LOG";
    private static final int MAX_LOGS = 500;

    private static final ArrayDeque<String[]> logs = new ArrayDeque<>();
    private static boolean started;

    private AiLogCollector() {
    }

    public static synchronized void start(@NonNull Context context) {
        if (started) {
            return;
        }
        started = true;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                add(intent.getStringExtra("packageName"), intent.getStringExtra("log"));
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_NEW_DEBUG_LOG);
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
    }

    static synchronized void add(@Nullable String packageName, @Nullable String log) {
        if (log == null) {
            return;
        }
        logs.addLast(new String[]{packageName == null ? "" : packageName, log});
        while (logs.size() > MAX_LOGS) {
            logs.removeFirst();
        }
    }

    /**
     * @return The remembered logs of an app, oldest first.
     */
    @NonNull
    public static synchronized List<String> recent(@Nullable String packageName) {
        List<String> recent = new ArrayList<>();
        for (String[] log : logs) {
            if (packageName == null || log[0].isEmpty() || log[0].equals(packageName)) {
                recent.add(log[1]);
            }
        }
        return recent;
    }
}
