package com.archos.mediacenter.video.leanback.settings;

import android.content.Intent;
import android.os.Bundle;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import com.archos.mediacenter.video.utils.ThemeManager;

public class VideoSettingsActivity extends LeanbackActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Apply black theme variant for leanback preferences when in black theme
        if (ThemeManager.getInstance(this).isBlackTheme()) {
            setTheme(R.style.MyLeanbackTheme_Preferences_Black);
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_settings);
        overridePendingTransition(R.anim.slide_in_from_right, 0);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        // FIXME
        overridePendingTransition(0, R.anim.slide_out_to_right);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (getSupportFragmentManager().findFragmentById(R.id.settingsFragment) != null) {
            ((VideoSettingsFragment) getSupportFragmentManager().findFragmentById(R.id.settingsFragment)).onActivityResult(requestCode, resultCode, data);
        }
    }
}