"""Render a 1920x1080 TV wallpaper comparing current weather in two cities.

Weather comes from Open-Meteo (free, no API key). The layout keeps the
upper-left area for the weather panels so Projectivy's clock (top right)
and app grid (lower half) don't cover them.

Usage: python generate.py [output.png]
"""

import json
import math
import sys
import urllib.request
from datetime import datetime
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

CITIES = [
    {"name": "Dubai", "lat": 25.2048, "lon": 55.2708},
    {"name": "Harrow on the Hill", "lat": 51.5714, "lon": -0.3337},
]

W, H = 1920, 1080
BG_TOP = (14, 10, 18)
BG_BOTTOM = (4, 3, 6)
TEXT = (236, 232, 240)
MUTED = (150, 144, 160)
ACCENT = (255, 196, 92)
CLOUD = (190, 196, 210)
RAIN = (110, 170, 255)

FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/System/Library/Fonts/Supplemental/Arial.ttf",
    "/Library/Fonts/Arial.ttf",
]
BOLD_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "/Library/Fonts/Arial Bold.ttf",
]

# WMO weather codes -> (label, icon kind)
WMO = {
    0: ("Clear", "sun"),
    1: ("Mostly clear", "sun"),
    2: ("Partly cloudy", "partly"),
    3: ("Overcast", "cloud"),
    45: ("Fog", "fog"),
    48: ("Fog", "fog"),
    51: ("Light drizzle", "rain"),
    53: ("Drizzle", "rain"),
    55: ("Heavy drizzle", "rain"),
    56: ("Freezing drizzle", "rain"),
    57: ("Freezing drizzle", "rain"),
    61: ("Light rain", "rain"),
    63: ("Rain", "rain"),
    65: ("Heavy rain", "rain"),
    66: ("Freezing rain", "rain"),
    67: ("Freezing rain", "rain"),
    71: ("Light snow", "snow"),
    73: ("Snow", "snow"),
    75: ("Heavy snow", "snow"),
    77: ("Snow grains", "snow"),
    80: ("Showers", "rain"),
    81: ("Showers", "rain"),
    82: ("Heavy showers", "rain"),
    85: ("Snow showers", "snow"),
    86: ("Snow showers", "snow"),
    95: ("Thunderstorm", "storm"),
    96: ("Thunderstorm", "storm"),
    99: ("Thunderstorm", "storm"),
}


def font(size, bold=False):
    for path in BOLD_CANDIDATES if bold else FONT_CANDIDATES:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default(size)


def fetch():
    lats = ",".join(str(c["lat"]) for c in CITIES)
    lons = ",".join(str(c["lon"]) for c in CITIES)
    url = (
        "https://api.open-meteo.com/v1/forecast"
        f"?latitude={lats}&longitude={lons}"
        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,"
        "weather_code,wind_speed_10m,is_day"
        "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max"
        "&timezone=auto&forecast_days=4"
    )
    with urllib.request.urlopen(url, timeout=30) as resp:
        data = json.load(resp)
    return data if isinstance(data, list) else [data]


def draw_sun(d, cx, cy, r, moon=False):
    if moon:
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(230, 226, 210))
        o = r * 0.55
        d.ellipse((cx - r + o, cy - r - o * 0.3, cx + r + o, cy + r - o * 0.3), fill=BG_TOP)
        return
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=ACCENT)
    for i in range(8):
        a = i * math.pi / 4
        x1, y1 = cx + math.cos(a) * r * 1.35, cy + math.sin(a) * r * 1.35
        x2, y2 = cx + math.cos(a) * r * 1.75, cy + math.sin(a) * r * 1.75
        d.line((x1, y1, x2, y2), fill=ACCENT, width=max(3, r // 7))


def draw_cloud(d, cx, cy, s, color=CLOUD):
    d.ellipse((cx - s * 1.1, cy - s * 0.2, cx - s * 0.1, cy + s * 0.6), fill=color)
    d.ellipse((cx - s * 0.6, cy - s * 0.7, cx + s * 0.5, cy + s * 0.5), fill=color)
    d.ellipse((cx + s * 0.1, cy - s * 0.35, cx + s * 1.1, cy + s * 0.6), fill=color)
    d.rectangle((cx - s * 0.7, cy + s * 0.1, cx + s * 0.7, cy + s * 0.6), fill=color)


def draw_icon(d, kind, cx, cy, s, is_day):
    if kind == "sun":
        draw_sun(d, cx, cy, int(s * 0.55), moon=not is_day)
    elif kind == "partly":
        draw_sun(d, cx - s * 0.35, cy - s * 0.3, int(s * 0.4), moon=not is_day)
        draw_cloud(d, cx + s * 0.15, cy + s * 0.15, s * 0.6)
    elif kind == "fog":
        for i in range(4):
            y = cy - s * 0.45 + i * s * 0.3
            d.line((cx - s * 0.8, y, cx + s * 0.8, y), fill=CLOUD, width=max(4, int(s * 0.1)))
    else:
        draw_cloud(d, cx, cy - s * 0.15, s * 0.7, color=(150, 156, 172) if kind == "storm" else CLOUD)
        if kind in ("rain", "storm"):
            for i in range(3):
                x = cx - s * 0.4 + i * s * 0.4
                d.line((x, cy + s * 0.5, x - s * 0.12, cy + s * 0.85), fill=RAIN, width=max(4, int(s * 0.08)))
        if kind == "snow":
            for i in range(3):
                x = cx - s * 0.4 + i * s * 0.4
                r = s * 0.07
                d.ellipse((x - r, cy + s * 0.65 - r, x + r, cy + s * 0.65 + r), fill=TEXT)
        if kind == "storm":
            d.polygon(
                [(cx, cy + s * 0.35), (cx - s * 0.2, cy + s * 0.75), (cx, cy + s * 0.7), (cx - s * 0.1, cy + s)],
                fill=ACCENT,
            )


def background():
    img = Image.new("RGB", (W, H), BG_TOP)
    d = ImageDraw.Draw(img)
    for y in range(H):
        t = y / (H - 1)
        c = tuple(round(a + (b - a) * t) for a, b in zip(BG_TOP, BG_BOTTOM))
        d.line((0, y, W, y), fill=c)
    return img


def render(results, out):
    img = background()
    d = ImageDraw.Draw(img)

    f_city = font(44, bold=True)
    f_time = font(30)
    f_temp = font(132, bold=True)
    f_cond = font(38)
    f_small = font(30)

    panel_w, gap, left, top = 560, 60, 110, 70
    for i, (city, res) in enumerate(zip(CITIES, results)):
        cur, daily = res["current"], res["daily"]
        label, kind = WMO.get(cur["weather_code"], ("—", "cloud"))
        local = datetime.fromisoformat(cur["time"])
        x = left + i * (panel_w + gap)

        d.text((x, top), city["name"], font=f_city, fill=TEXT)
        d.text((x, top + 58), f"{local:%a %-I:%M %p} local", font=f_time, fill=MUTED)

        temp = f"{round(cur['temperature_2m'])}°"
        d.text((x, top + 100), temp, font=f_temp, fill=TEXT)
        tw = d.textlength(temp, font=f_temp)
        draw_icon(d, kind, x + tw + 95, top + 185, 80, bool(cur["is_day"]))

        d.text((x, top + 255), label, font=f_cond, fill=TEXT)
        hi, lo = round(daily["temperature_2m_max"][0]), round(daily["temperature_2m_min"][0])
        feels = round(cur["apparent_temperature"])
        d.text(
            (x, top + 305),
            f"H {hi}°  L {lo}°  ·  Feels {feels}°  ·  {cur['relative_humidity_2m']}%",
            font=f_small,
            fill=MUTED,
        )

    divider_x = left + panel_w + gap // 2 - 10
    d.line((divider_x, top + 10, divider_x, top + 340), fill=(60, 52, 70), width=2)

    draw_forecast(d, results)

    img.save(out, "PNG", optimize=True)


def draw_forecast(d, results):
    """Next three days for each city, in the strip below the launcher's app row."""
    f_head = font(30, bold=True)
    f_day = font(30)
    f_temp = font(34, bold=True)
    f_small = font(26)

    col_w, top, left = 270, 790, 110
    for i, (city, res) in enumerate(zip(CITIES, results)):
        daily = res["daily"]
        x0 = left + i * (3 * col_w + 40)
        d.text((x0, top), f"{city['name']} · next 3 days", font=f_head, fill=MUTED)
        for j in range(1, 4):
            x = x0 + (j - 1) * col_w
            day = datetime.fromisoformat(daily["time"][j])
            _, kind = WMO.get(daily["weather_code"][j], ("—", "cloud"))
            d.text((x, top + 52), f"{day:%a %-d}", font=f_day, fill=TEXT)
            draw_icon(d, kind, x + 40, top + 148, 34, True)
            hi, lo = round(daily["temperature_2m_max"][j]), round(daily["temperature_2m_min"][j])
            d.text((x + 95, top + 108), f"{hi}°", font=f_temp, fill=TEXT)
            d.text((x + 95, top + 152), f"{lo}°", font=f_small, fill=MUTED)
            rain = daily.get("precipitation_probability_max", [None] * 4)[j]
            if rain is not None:
                d.text((x, top + 200), f"Rain {rain}%", font=f_small, fill=RAIN if rain >= 40 else MUTED)

    divider_x = left + 3 * col_w + 5
    d.line((divider_x, top + 5, divider_x, top + 235), fill=(60, 52, 70), width=2)


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "wallpaper.png")
    out.parent.mkdir(parents=True, exist_ok=True)
    render(fetch(), out)
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
