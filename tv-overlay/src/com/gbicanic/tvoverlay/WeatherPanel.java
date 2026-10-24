package com.gbicanic.tvoverlay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.SimpleTimeZone;
import java.util.TimeZone;

/**
 * Fetches the forecast from Open-Meteo and draws the side-by-side panel
 * (same layout as overlay.png from generate.py, but with live local times).
 */
final class WeatherPanel {
    static final int W = 1000, H = 380;

    private static final String[] NAMES = {"Dubai", "Harrow", "Zagreb"};
    private static final String URL = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=25.2048,51.5714,45.8150&longitude=55.2708,-0.3337,15.9819"
            + "&current=temperature_2m,weather_code,is_day"
            + "&daily=temperature_2m_max,temperature_2m_min,weather_code"
            + "&timezone=auto&forecast_days=4";

    private static final int TEXT = Color.rgb(236, 232, 240);
    private static final int MUTED = Color.rgb(196, 192, 204);
    private static final int ACCENT = Color.rgb(255, 196, 92);
    private static final int CLOUD = Color.rgb(190, 196, 210);
    private static final int STORM_CLOUD = Color.rgb(150, 156, 172);
    private static final int RAIN = Color.rgb(110, 170, 255);
    private static final int MOON = Color.rgb(230, 226, 210);

    /** One city's forecast. Index 0 of the daily arrays is today. */
    static final class City {
        String name;
        int utcOffsetSeconds;
        double temp;
        int code;
        boolean isDay;
        String[] days = new String[4];
        double[] max = new double[4], min = new double[4];
        int[] codes = new int[4];
    }

    static City[] fetch() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(URL).openConnection();
        conn.setUseCaches(false);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        String body;
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            body = out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }

        JSONArray all = new JSONArray(body);
        City[] cities = new City[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            JSONObject o = all.getJSONObject(i);
            JSONObject cur = o.getJSONObject("current");
            JSONObject daily = o.getJSONObject("daily");
            City c = new City();
            c.name = NAMES[i];
            c.utcOffsetSeconds = o.getInt("utc_offset_seconds");
            c.temp = cur.getDouble("temperature_2m");
            c.code = cur.getInt("weather_code");
            c.isDay = cur.getInt("is_day") == 1;
            for (int d = 0; d < 4; d++) {
                c.days[d] = daily.getJSONArray("time").getString(d);
                c.max[d] = daily.getJSONArray("temperature_2m_max").getDouble(d);
                c.min[d] = daily.getJSONArray("temperature_2m_min").getDouble(d);
                c.codes[d] = daily.getJSONArray("weather_code").getInt(d);
            }
            cities[i] = c;
        }
        return cities;
    }

    static Bitmap render(City[] cities, long nowMillis) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(185, 10, 8, 14));
        c.drawRoundRect(new RectF(0, 0, W - 1, H - 1), 28, 28, bg);

        Paint city = text(34, true, TEXT);
        Paint time = text(22, false, MUTED);
        Paint temp = text(76, true, TEXT);
        Paint cond = text(26, false, TEXT);
        Paint small = text(22, false, MUTED);
        Paint smallBright = text(22, false, TEXT);
        Paint divider = new Paint();
        divider.setColor(Color.rgb(70, 62, 80));
        divider.setStrokeWidth(2);

        int colW = (W - 40) / cities.length;
        for (int i = 0; i < cities.length; i++) {
            City ct = cities[i];
            float x = 20 + i * colW + 16;
            if (i > 0) c.drawLine(x - 16, 24, x - 16, H - 24, divider);

            draw(c, ct.name, x, 18, city);
            draw(c, localTime(nowMillis, ct.utcOffsetSeconds), x, 60, time);

            String t = Math.round(ct.temp) + "°";
            draw(c, t, x, 86, temp);
            drawIcon(c, kind(ct.code), x + temp.measureText(t) + 50, 136, 40, ct.isDay);

            draw(c, label(ct.code), x, 182, cond);
            draw(c, "H " + Math.round(ct.max[0]) + "°  L " + Math.round(ct.min[0]) + "°", x, 214, small);

            for (int d = 1; d < 4; d++) {
                float dx = x + (d - 1) * 96;
                draw(c, weekday(ct.days[d]), dx, 258, small);
                drawIcon(c, kind(ct.codes[d]), dx + 22, 308, 20, true);
                draw(c, Math.round(ct.max[d]) + "°/" + Math.round(ct.min[d]) + "°", dx, 334, smallBright);
            }
        }
        return bmp;
    }

    // ---- text helpers ----

    private static Paint text(float size, boolean bold, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return p;
    }

    /** Draws text with its top edge at {@code top}, like Pillow's ImageDraw.text. */
    private static void draw(Canvas c, String s, float x, float top, Paint p) {
        c.drawText(s, x, top - p.ascent(), p);
    }

    private static String localTime(long nowMillis, int offsetSeconds) {
        SimpleDateFormat f = new SimpleDateFormat("EEE h:mm a", Locale.ENGLISH);
        f.setTimeZone(new SimpleTimeZone(offsetSeconds * 1000, "city"));
        return f.format(new Date(nowMillis));
    }

    private static String weekday(String isoDate) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            SimpleDateFormat out = new SimpleDateFormat("EEE", Locale.ENGLISH);
            out.setTimeZone(TimeZone.getTimeZone("UTC"));
            return out.format(in.parse(isoDate));
        } catch (Exception e) {
            return "";
        }
    }

    // ---- WMO weather codes ----

    private static String label(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: return "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: return "Light drizzle";
            case 53: return "Drizzle";
            case 55: return "Heavy drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: return "Light rain";
            case 63: return "Rain";
            case 65: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: return "Light snow";
            case 73: return "Snow";
            case 75: return "Heavy snow";
            case 77: return "Snow grains";
            case 80: case 81: return "Showers";
            case 82: return "Heavy showers";
            case 85: case 86: return "Snow showers";
            case 95: case 96: case 99: return "Thunderstorm";
            default: return "—";
        }
    }

    private static String kind(int code) {
        if (code <= 1) return "sun";
        if (code == 2) return "partly";
        if (code == 3) return "cloud";
        if (code == 45 || code == 48) return "fog";
        if (code >= 95) return "storm";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "snow";
        if (code >= 51) return "rain";
        return "cloud";
    }

    // ---- icons (same shapes as generate.py) ----

    private static void drawIcon(Canvas c, String kind, float cx, float cy, float s, boolean isDay) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        switch (kind) {
            case "sun":
                sun(c, cx, cy, s * 0.55f, !isDay);
                break;
            case "partly":
                sun(c, cx - s * 0.35f, cy - s * 0.3f, s * 0.4f, !isDay);
                cloud(c, cx + s * 0.15f, cy + s * 0.15f, s * 0.6f, CLOUD);
                break;
            case "fog":
                p.setColor(CLOUD);
                p.setStrokeWidth(Math.max(4, s * 0.1f));
                for (int i = 0; i < 4; i++) {
                    float y = cy - s * 0.45f + i * s * 0.3f;
                    c.drawLine(cx - s * 0.8f, y, cx + s * 0.8f, y, p);
                }
                break;
            default:
                cloud(c, cx, cy - s * 0.15f, s * 0.7f, kind.equals("storm") ? STORM_CLOUD : CLOUD);
                if (kind.equals("rain") || kind.equals("storm")) {
                    p.setColor(RAIN);
                    p.setStrokeWidth(Math.max(4, s * 0.08f));
                    for (int i = 0; i < 3; i++) {
                        float x = cx - s * 0.4f + i * s * 0.4f;
                        c.drawLine(x, cy + s * 0.5f, x - s * 0.12f, cy + s * 0.85f, p);
                    }
                }
                if (kind.equals("snow")) {
                    p.setColor(TEXT);
                    for (int i = 0; i < 3; i++) {
                        c.drawCircle(cx - s * 0.4f + i * s * 0.4f, cy + s * 0.65f, s * 0.07f, p);
                    }
                }
                if (kind.equals("storm")) {
                    Path bolt = new Path();
                    bolt.moveTo(cx, cy + s * 0.35f);
                    bolt.lineTo(cx - s * 0.2f, cy + s * 0.75f);
                    bolt.lineTo(cx, cy + s * 0.7f);
                    bolt.lineTo(cx - s * 0.1f, cy + s);
                    bolt.close();
                    p.setColor(ACCENT);
                    c.drawPath(bolt, p);
                }
        }
    }

    private static void sun(Canvas c, float cx, float cy, float r, boolean moon) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (moon) {
            float o = r * 0.55f;
            Path disc = new Path();
            disc.addCircle(cx, cy, r, Path.Direction.CW);
            Path bite = new Path();
            bite.addCircle(cx + o, cy - o * 0.3f, r, Path.Direction.CW);
            disc.op(bite, Path.Op.DIFFERENCE);
            p.setColor(MOON);
            c.drawPath(disc, p);
            return;
        }
        p.setColor(ACCENT);
        c.drawCircle(cx, cy, r, p);
        p.setStrokeWidth(Math.max(3, r / 7));
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            c.drawLine(cx + (float) Math.cos(a) * r * 1.35f, cy + (float) Math.sin(a) * r * 1.35f,
                    cx + (float) Math.cos(a) * r * 1.75f, cy + (float) Math.sin(a) * r * 1.75f, p);
        }
    }

    private static void cloud(Canvas c, float cx, float cy, float s, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        c.drawOval(cx - s * 1.1f, cy - s * 0.2f, cx - s * 0.1f, cy + s * 0.6f, p);
        c.drawOval(cx - s * 0.6f, cy - s * 0.7f, cx + s * 0.5f, cy + s * 0.5f, p);
        c.drawOval(cx + s * 0.1f, cy - s * 0.35f, cx + s * 1.1f, cy + s * 0.6f, p);
        c.drawRect(cx - s * 0.7f, cy + s * 0.1f, cx + s * 0.7f, cy + s * 0.6f, p);
    }

    private WeatherPanel() {
    }
}
