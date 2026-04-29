package com.archos.mediacenter.video.leanback.settings;

import android.os.Bundle;

import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.leanback.LeanbackActivity;
import com.archos.mediacenter.video.utils.ThemeManager;

public class VideoSettingsLicencesActivity extends LeanbackActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Apply black theme variant for leanback preferences when in black theme
        if (ThemeManager.getInstance(this).isBlackTheme()) {
            setTheme(R.style.MyLeanbackTheme_Preferences_Black);
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_settings_licences);
    }
}