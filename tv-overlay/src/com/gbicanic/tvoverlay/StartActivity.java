package com.gbicanic.tvoverlay;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Invisible entry point: starts the overlay service and closes. */
public class StartActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startForegroundService(new Intent(this, OverlayService.class));
        finish();
    }
}
