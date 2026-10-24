# Weather wallpaper

A 1920×1080 TV wallpaper comparing the current weather in Dubai and Harrow on the Hill.
Data comes from [Open-Meteo](https://open-meteo.com/) (free, no API key).

A GitHub Action regenerates it every 30 minutes and publishes it with GitHub Pages at:

    https://<user>.github.io/<repo>/wallpaper.png

Set that address as the **Uri** under Projectivy → Appearance → Wallpaper → Media file → Configure.

To change cities, edit `CITIES` at the top of `generate.py`. To change the
background, replace `background.jpg` (delete it for a plain dark background);
`PHOTO_BLUR` and `PHOTO_DIM` control how soft and dark it is.

Background photo: aerial view of Harrow School and Harrow on the Hill, used with the
repository owner's permission (cropped, resized, blurred and darkened).

Note: GitHub pauses scheduled workflows in a repository after 60 days without
any activity. If the picture stops updating, open the **Actions** tab and re-enable it.

## TV overlay

The job also publishes `overlay.png`, a transparent 1000×380 panel with Dubai, Harrow and
Zagreb side by side (now + next 3 days), at:

    https://<user>.github.io/<repo>/overlay.png

Since version 2 the `tv-overlay` Android app no longer uses that picture: it fetches the
forecast from Open-Meteo itself every 30 minutes and draws the same panel on the TV, with
each city's local time redrawn every minute (GitHub's scheduled runs can lag by hours).
Its cities are set in `tv-overlay/src/com/gbicanic/tvoverlay/WeatherPanel.java`; rebuild
with `tv-overlay/build.sh` and install with `adb install -r tv-overlay/tv-overlay.apk`.
