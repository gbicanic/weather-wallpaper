package com.gbicanic.tvoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;


/**
 * Shows a live weather panel (see WeatherPanel) in the top-left corner while the Projectivy home
 * screen is in front, and hides it everywhere else (apps, screensaver, screen off). While the home
 * screen is in front it also plays Otvoreni radio (see Radio), stopping as soon as anything else is.
 */
public class OverlayService extends Service {
    private static final String TAG = "TvOverlay";
    private static final String HOME_ACTIVITY = "com.spocky.projengmenu.ui.home.MainActivity";

    private static final long POLL_MS = 1500;
    private static final long REFRESH_MS = 30 * 60 * 1000;
    private static final long RETRY_MS = 60 * 1000;
    private static final long REDRAW_MS = 60 * 1000;
    // Home screen must stay in front this long before the radio starts (skips quick passes through it).
    private static final long RADIO_DELAY_MS = 5000;
    // The radio always starts at this TV/Sonos volume (0-100 scale).
    private static final int RADIO_VOLUME = 8;

    // Panel position in 1920x1080 screen units, clear of the clock and the app grid.
    private static final int PANEL_X = 40, PANEL_Y = 40;

    private Handler handler;
    private WindowManager windowManager;
    private UsageStatsManager usageStats;
    private PowerManager power;
    private ImageView view;
    private Bitmap panel;
    private WeatherPanel.City[] cities;
    private Radio radio;
    private AudioManager audio;
    private long homeSince;
    private boolean radioOn;
    private int volumeBeforeRadio = -1;

    private boolean shown;
    private boolean dreaming;
    private long nextFetchAt;
    private long nextRedrawAt;
    private long lastEventAt;
    private String frontActivity = HOME_ACTIVITY;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            updateFrontActivity();
            boolean wanted = HOME_ACTIVITY.equals(frontActivity) && power.isInteractive() && !dreaming;
            long now = System.currentTimeMillis();
            if (cities != null && now >= nextRedrawAt) redraw(now);
            if (wanted && panel != null) show(); else hide();
            if (wanted) {
                if (homeSince == 0) homeSince = now;
                if (now - homeSince >= RADIO_DELAY_MS && !radioOn) startRadio();
            } else {
                homeSince = 0;
                if (radioOn) stopRadio();
            }
            if (now >= nextFetchAt) fetch();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private final BroadcastReceiver dreamReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            dreaming = Intent.ACTION_DREAMING_STARTED.equals(intent.getAction());
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        radio = new Radio();
        audio = getSystemService(AudioManager.class);
        windowManager = getSystemService(WindowManager.class);
        usageStats = getSystemService(UsageStatsManager.class);
        power = getSystemService(PowerManager.class);

        view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.FIT_XY);

        IntentFilter dreams = new IntentFilter(Intent.ACTION_DREAMING_STARTED);
        dreams.addAction(Intent.ACTION_DREAMING_STOPPED);
        registerReceiver(dreamReceiver, dreams);

        startForeground(1, buildNotification());
        lastEventAt = System.currentTimeMillis() - 60 * 60 * 1000;
        handler.post(poll);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(poll);
        unregisterReceiver(dreamReceiver);
        if (radioOn) stopRadio();
        hide();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        NotificationChannel channel = new NotificationChannel(
                "overlay", "Weather overlay", NotificationManager.IMPORTANCE_MIN);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        return new Notification.Builder(this, "overlay")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Weather overlay")
                .setContentText("Forecast and Otvoreni radio on the home screen")
                .build();
    }

    /** Sets the radio volume, remembering the previous one so films get their volume back. */
    private void startRadio() {
        radioOn = true;
        volumeBeforeRadio = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        setVolume(RADIO_VOLUME);
        Log.i(TAG, "Radio on: volume " + volumeBeforeRadio + " -> " + audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        radio.start();
    }

    private void stopRadio() {
        radioOn = false;
        radio.stop();
        Log.i(TAG, "Radio off at volume " + audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        // Put the old volume back, unless it was changed while the radio played.
        if (volumeBeforeRadio >= 0 && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == RADIO_VOLUME) {
            setVolume(volumeBeforeRadio);
        }
        volumeBeforeRadio = -1;
    }

    private void setVolume(int volume) {
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not set volume", e);
        }
    }

    /** Tracks the most recently resumed activity from usage events. */
    private void updateFrontActivity() {
        long now = System.currentTimeMillis();
        UsageEvents events = usageStats.queryEvents(lastEventAt, now);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                frontActivity = event.getClassName();
            }
            lastEventAt = Math.max(lastEventAt, event.getTimeStamp() + 1);
        }
    }

    private void show() {
        if (shown) return;
        Rect screen = windowManager.getCurrentWindowMetrics().getBounds();
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                screen.width() * WeatherPanel.W / 1920,
                screen.height() * WeatherPanel.H / 1080,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = screen.width() * PANEL_X / 1920;
        params.y = screen.height() * PANEL_Y / 1080;
        try {
            windowManager.addView(view, params);
            shown = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not show overlay (is 'display over other apps' allowed?)", e);
        }
    }

    private void hide() {
        if (!shown) return;
        windowManager.removeView(view);
        shown = false;
    }

    /** Redraws the panel from the last forecast, so each city's local time stays current. */
    private void redraw(long now) {
        panel = WeatherPanel.render(cities, now);
        view.setImageBitmap(panel);
        nextRedrawAt = now - now % REDRAW_MS + REDRAW_MS;
    }

    private void fetch() {
        nextFetchAt = System.currentTimeMillis() + REFRESH_MS;
        // Anonymous classes, not lambdas: android.jar has no LambdaMetafactory for javac.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final WeatherPanel.City[] fresh = WeatherPanel.fetch();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            cities = fresh;
                            redraw(System.currentTimeMillis());
                        }
                    });
                    Log.i(TAG, "Forecast updated");
                } catch (Exception e) {
                    Log.w(TAG, "Forecast download failed, retrying soon", e);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            nextFetchAt = System.currentTimeMillis() + RETRY_MS;
                        }
                    });
                }
            }
        }).start();
    }
}
