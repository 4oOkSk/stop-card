package io.github.user4ooksk.stopcard.fixtures;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.*;
import android.os.Process;
import android.util.Log;
import android.util.Rational;
import android.widget.*;
import java.util.List;

/** Three small, local-only test apps. Each native entry records its own PID and monotonic time. */
public class ProbeActivity extends Activity {
    private static final String BASE = "io.github.user4ooksk.stopcard.test.";
    private static final String CLASS = "io.github.user4ooksk.stopcard.fixtures.ProbeActivity";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView output;
    private LinearLayout column;
    private boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) { event("bind connected " + name); }
        public void onServiceDisconnected(ComponentName name) { event("bind disconnected " + name); }
        public void onBindingDied(ComponentName name) { event("binding died " + name); }
        public void onNullBinding(ComponentName name) { event("null binding " + name); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(20, 20, 20, 20);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("probe", "Test events", NotificationManager.IMPORTANCE_DEFAULT));
        TextView title = new TextView(this);
        title.setText(getPackageName() + "\nPID " + Process.myPid() + " · target " + target());
        column.addView(title);
        button("New document card", () -> startActivity(new Intent(this, ProbeActivity.class)
            .setData(Uri.parse("stopcard:document/" + SystemClock.elapsedRealtimeNanos()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)));
        button("Open excluded task", () -> startActivity(new Intent(this, ProbeActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)));
        button("Picture in picture", () -> enterPictureInPictureMode(new PictureInPictureParams.Builder()
            .setAspectRatio(new Rational(16, 9)).build()));
        button("Open target flow with result and binding", this::openFlow);
        button("Start target Service now (stop in 3 s; destroy takes 1 s)", () -> event("start returned " +
            startService(component(target(), CLASS + "$ProbeService").putExtra("stop_after_ms", 3_000L))));
        button("Send target async broadcast now (finish in 3 s)", () ->
            sendBroadcast(component(target(), CLASS + "$ProbeReceiver").putExtra("async_ms", 3_000L)));
        button("Delayed target Activity (5 s, then press Home)", () -> later(() -> startActivity(component(target(), CLASS + "$Flow"))));
        button("Start target Service after 5 s", () -> later(() -> event("start returned " + startService(component(target(), CLASS + "$ProbeService")))));
        button("Bind target Service after 5 s", () -> later(() -> bindTarget()));
        button("Release binding", this::unbind);
        button("Send target broadcast after 5 s", () -> later(() -> sendBroadcast(component(target(), CLASS + "$ProbeReceiver"))));
        button("Query target Provider after 5 s", () -> later(() -> {
            try (Cursor result = getContentResolver().query(Uri.parse("content://" + target() + ".probe/check"), null, null, null, null)) {
                event("provider returned " + (result == null ? "null" : result.getCount() + " row(s)"));
            }
        }));
        button("Send target PendingIntent after 5 s", () -> {
            PendingIntent pending = PendingIntent.getBroadcast(this, 9, component(target(), CLASS + "$ProbeReceiver"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            later(() -> { try { pending.send(); event("PendingIntent sent"); } catch (PendingIntent.CanceledException e) { event("PendingIntent canceled"); } });
        });
        button("Schedule own Job (5–10 s)", () -> {
            int result = getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(10, new ComponentName(this, ProbeJob.class))
                .setMinimumLatency(5_000).setOverrideDeadline(10_000).build());
            event("job schedule " + result);
        });
        button("Schedule own inexact alarm", () -> {
            PendingIntent pending = PendingIntent.getBroadcast(this, 11, new Intent(this, ProbeReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 8_000, pending);
            event("alarm registered; delivery time is set by Android");
        });
        button("Post own notification after 5 s", () -> later(() -> notify(this, getPackageName(), false)));
        button("Allow A as notification delegate", () -> {
            getSystemService(NotificationManager.class).setNotificationDelegate(BASE + "a");
            event("notification delegate " + BASE + "a");
        });
        button("Post delegated notification for target", () -> notify(this, target(), true));
        button("Start own foreground Service", () -> startForegroundService(new Intent(this, ForegroundProbe.class)));
        button("Stop own foreground Service", () -> stopService(new Intent(this, ForegroundProbe.class)));
        button("Finish Activity (keep task)", this::finish);
        button("Finish and remove task", this::finishAndRemoveTask);
        button("Refresh local event log", this::refresh);
        output = new TextView(this);
        column.addView(output);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        event("Activity created " + getTaskId());
        refresh();
    }

    private String target() { return BASE + (getPackageName().endsWith(".a") ? "b" : getPackageName().endsWith(".b") ? "c" : "a"); }
    private static Intent component(String pkg, String name) { return new Intent("io.github.user4ooksk.stopcard.PROBE").setComponent(new ComponentName(pkg, name)).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES); }
    private void button(String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(v -> attempt(action));
        column.addView(button);
    }
    private void attempt(Runnable action) { try { action.run(); } catch (RuntimeException e) { event("caller result " + e.getClass().getSimpleName() + ": " + e.getMessage()); } }
    private void later(Runnable action) { event("queued for 5 s; press Home now"); handler.postDelayed(() -> attempt(action), 5_000); }
    private void openFlow() {
        startActivityForResult(component(target(), CLASS + "$Flow"), 100);
        bindTarget();
    }
    private void bindTarget() {
        if (!bound) bound = bindService(component(target(), CLASS + "$ProbeService"), connection, Context.BIND_AUTO_CREATE);
        event("bind returned " + bound);
    }
    private void unbind() { if (bound) { unbindService(connection); bound = false; event("binding released"); } }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        event("result callback request=" + request + " result=" + result);
        if (request == 100) handler.postDelayed(this::unbind, 5_000);
    }
    @Override protected void onDestroy() { unbind(); event("Activity destroyed"); super.onDestroy(); }
    private void event(String message) { record(this, message); refresh(); }
    private void refresh() { if (output != null) output.setText(getSharedPreferences("events", MODE_PRIVATE).getString("lines", "")); }
    private static synchronized void record(Context context, String message) {
        String line = SystemClock.elapsedRealtime() + " " + context.getPackageName() + " pid=" + Process.myPid() + " " + message;
        Log.i("StopCardFixture", line);
        var prefs = context.getSharedPreferences("events", MODE_PRIVATE);
        String lines = prefs.getString("lines", "") + line + "\n";
        if (lines.length() > 16_384) lines = lines.substring(lines.indexOf('\n', lines.length() - 12_000) + 1);
        prefs.edit().putString("lines", lines).apply();
    }
    private static Notification notification(Context context) {
        context.getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("probe", "Test events", NotificationManager.IMPORTANCE_DEFAULT));
        return new Notification.Builder(context, "probe").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Stop Card test event").setContentText(context.getPackageName())
            .setContentIntent(PendingIntent.getActivity(context, 1, new Intent(context, ProbeActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build();
    }
    private static void notify(Context context, String pkg, boolean delegated) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification notification = notification(context);
        if (delegated) manager.notifyAsPackage(pkg, "probe", 1, notification); else manager.notify(1, notification);
        record(context, "notification returned owner=" + pkg + " delegated=" + delegated);
    }

    public static class Flow extends Activity {
        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            record(this, "Flow created task=" + getTaskId());
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            TextView label = new TextView(this);
            label.setText("Local test flow: " + getPackageName() + "\nNo network or payment.");
            column.addView(label);
            List<String> actions = getPackageName().endsWith(".c") ? List.of("Return result after 5 s", "Cancel") :
                List.of("Authenticate in C", "Return result after 5 s", "Cancel");
            for (String text : actions) {
                Button button = new Button(this); button.setText(text); column.addView(button);
                button.setOnClickListener(v -> {
                    if (text.startsWith("Authenticate")) {
                        try { startActivityForResult(component(BASE + "c", CLASS + "$Flow"), 101); }
                        catch (RuntimeException e) { record(this, e.getClass().getSimpleName()); }
                    } else if (text.startsWith("Return")) new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        record(this, "send result"); setResult(RESULT_OK); finish();
                    }, 5_000);
                    else { record(this, "cancel"); setResult(RESULT_CANCELED); finish(); }
                });
            }
            setContentView(column);
        }
        @Override protected void onActivityResult(int request, int result, Intent data) { super.onActivityResult(request, result, data); record(this, "nested result delivered " + result); }
        @Override protected void onDestroy() { record(this, "Flow destroyed"); super.onDestroy(); }
    }
    public static class ProbeService extends Service {
        private boolean slowDestroy;
        @Override public void onCreate() { super.onCreate(); record(this, "Service created"); }
        @Override public int onStartCommand(Intent i, int flags, int id) {
            record(this, "Service started");
            long delay = i == null ? 0 : i.getLongExtra("stop_after_ms", 0);
            if (delay > 0) {
                slowDestroy = true;
                new Handler(Looper.getMainLooper()).postDelayed(() -> { record(this, "Service stopSelf"); stopSelf(id); }, delay);
            }
            return START_NOT_STICKY;
        }
        @Override public IBinder onBind(Intent i) { record(this, "Service bound"); return new Binder(); }
        @Override public boolean onUnbind(Intent i) { record(this, "Service unbound"); return false; }
        @Override public void onDestroy() {
            record(this, "Service destroy begin");
            if (slowDestroy) SystemClock.sleep(1_000); // Explicit probe of premature lifecycle release.
            record(this, "Service destroyed"); super.onDestroy();
        }
    }
    public static class ForegroundProbe extends ProbeService {
        @Override public void onCreate() { super.onCreate(); startForeground(2, notification(this)); record(this, "foreground requested"); }
    }
    public static class ProbeReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent i) {
            record(context, "Broadcast delivered");
            long delay = i.getLongExtra("async_ms", 0);
            if (delay > 0) {
                PendingResult pending = goAsync();
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    record(context, "Broadcast async finish"); pending.finish();
                }, delay);
            }
        }
    }
    public static class ProbeJob extends JobService {
        @Override public boolean onStartJob(JobParameters p) { record(this, "Job delivered"); return false; }
        @Override public boolean onStopJob(JobParameters p) { record(this, "Job stopped"); return false; }
    }
    public static class ProbeProvider extends ContentProvider {
        @Override public boolean onCreate() { record(getContext(), "Provider created"); return true; }
        @Override public Cursor query(Uri u, String[] projection, String selection, String[] args, String sort) {
            record(getContext(), "Provider queried"); MatrixCursor result = new MatrixCursor(new String[]{"pid", "elapsed"});
            result.addRow(new Object[]{Process.myPid(), SystemClock.elapsedRealtime()}); return result;
        }
        @Override public String getType(Uri u) { return "vnd.android.cursor.item/stopcard-probe"; }
        @Override public Uri insert(Uri u, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri u, String where, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri u, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    }
}
