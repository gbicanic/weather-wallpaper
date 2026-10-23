# Weather wallpaper

A 1920×1080 TV wallpaper comparing the current weather in Dubai and Harrow on the Hill.
Data comes from [Open-Meteo](https://open-meteo.com/) (free, no API key).

A GitHub Action regenerates it every 30 minutes and publishes it with GitHub Pages at:

    https://<user>.github.io/<repo>/wallpaper.png

Set that address as the **Uri** under Projectivy → Appearance → Wallpaper → Media file → Configure.

To change cities, edit `CITIES` at the top of `generate.py`. To change the
background, replace `background.jpg` (delete it for a plain dark background);
`PHOTO_BLUR` and `PHOTO_DIM` control how soft and dark it is.

Background photo: [Harrow on the Hill from Northwick Park](https://commons.wikimedia.org/wiki/File:Harrow_on_the_Hill_from_Northwick_Park_(51359551422).jpg)
by Simon from London, [CC BY 2.0](https://creativecommons.org/licenses/by/2.0), via Wikimedia Commons
(cropped, resized, blurred and darkened).

Note: GitHub pauses scheduled workflows in a repository after 60 days without
any activity. If the picture stops updating, open the **Actions** tab and re-enable it.
