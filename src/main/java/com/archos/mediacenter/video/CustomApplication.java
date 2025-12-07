// Copyright 2017 Archos SA
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.archos.mediacenter.video;


import static com.archos.filecorelibrary.FileUtils.getPermissions;
import static com.archos.filecorelibrary.FileUtils.hasPermission;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StrictMode;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.preference.PreferenceManager;

import com.archos.environment.ArchosFeatures;
import com.archos.environment.ArchosUtils;
import com.archos.environment.NetworkState;
import com.archos.filecorelibrary.FileUtilsQ;
import com.archos.filecorelibrary.jcifs.JcifsUtils;
import com.archos.filecorelibrary.samba.NetworkCredentialsDatabase;
import com.archos.filecorelibrary.samba.SambaDiscovery;
import com.archos.filecorelibrary.smbj.SmbjUtils;
import com.archos.filecorelibrary.sshj.SshjUtils;
import com.archos.filecorelibrary.webdav.WebdavUtils;
import com.archos.mediacenter.utils.trakt.Trakt;
import com.archos.mediacenter.utils.trakt.TraktService;
import com.archos.mediacenter.video.browser.BootupRecommandationService;
import com.archos.mediacenter.video.picasso.SmbRequestHandler;
import com.archos.mediacenter.video.picasso.ThumbnailRequestHandler;
import com.archos.mediacenter.video.player.PrivateMode;
import com.archos.mediacenter.video.player.PlayerActivity;
import com.archos.mediacenter.video.utils.LocaleConfigParser;
import com.archos.mediacenter.video.utils.OpenSubtitlesApiHelper;
import com.archos.mediacenter.video.utils.TrustingOkHttp3Downloader;
import com.archos.mediacenter.video.utils.VideoPreferencesCommon;
import com.archos.medialib.LibAvos;
import com.archos.mediaprovider.video.NetworkAutoRefresh;
import com.archos.mediaprovider.video.VideoStoreImportReceiver;
import com.archos.mediascraper.ScraperImage;
import com.jakewharton.threetenabp.AndroidThreeTen;
import com.squareup.picasso.Picasso;

import httpimage.FileSystemPersistence;
import httpimage.HttpImageManager;
import io.sentry.SentryLevel;
import io.sentry.android.core.SentryAndroid;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.PropertyChangeListener;
import java.security.Provider;
import java.security.Security;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.lang.reflect.Field;
import java.util.Map;

public class CustomApplication extends Application implements DefaultLifecycleObserver {

    private static Logger log = null;

    private static CustomApplication sInstance;

    private NetworkState networkState = null;
    private static boolean isNetworkStateRegistered = false;
    private static boolean isVideStoreImportReceiverRegistered = false;
    private static boolean isNetworkStateListenerAdded = false;
    private static boolean isHDMIPlugReceiverRegistered = false;
    private static long hdmiAudioEncodingFlag = 0;
    private static long spdifAudioEncodingFlag = 0;
    private static int[] hdmiAudioEncodingsFlags;
    private static int[] spdifAudioEncodingsFlags;
    private static long maxAudioChannelCount = 0;
    private static boolean hasHdmi = false;
    private static boolean hasSpdif = false;
    private static String supportedRefreshRates = "";
    private static AudioManager mAudioManager;
    private static AudioDeviceCallback mAudioDeviceCallback;
    private static boolean isIecEncapsulationCapable = false;
    private static boolean isDirectPcmMultichannelCapable = false;
    public static final long allHdmiAudioCodecs = 0b11111111111111111111111111111111;
    private static boolean hasManageExternalStoragePermissionInManifest = false;
    public static boolean isManageExternalStoragePermissionInManifest() { return hasManageExternalStoragePermissionInManifest; }

    private static int [] novaVersionArray;
    private static int [] novaPreviousVersionArray;
    private static String novaLongVersion;
    private static String novaShortVersion;
    private static int novaVersionCode = -1;
    private static String novaVersionName;
    private static boolean novaUpdated = false;

    public static int[] getNovaVersionArray() { return novaVersionArray; }
    public static String getNovaLongVersion() { return novaLongVersion; }
    public static String getNovaShortVersion() { return novaShortVersion; }
    public static int getNovaVersionCode() { return novaVersionCode; }
    public static String getNovaVersionName() { return novaVersionName; }
    public static boolean isNovaUpdated() { return novaUpdated; }
    public static void clearUpdatedFlag(Context context) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        sharedPreferences.edit().putBoolean("app_updated", false).commit();
        novaUpdated = false;
    }

    // AVOS encoding values are aligned with Android AudioFormat ones
    // note that API level has been checked with https://cs.android.com/android/platform/superproject/+/android-6.0.0_r23:frameworks/base/media/java/android/media/AudioFormat.java
    // maintain sync with avos audio_spdif.c
    private final int AVOS_ENCODING_INVALID = 0;                // 0 -> AudioFormat.ENCODING_INVALID = 0 (API21)
    private final int AVOS_ENCODING_DEFAULT = 1;                // 1 -> AudioFormat.ENCODING_DEFAULT = 1 (API21)
    private final int AVOS_ENCODING_PCM_16BIT = 2;              // 2 -> AudioFormat.ENCODING_PCM_16BIT = 2 (API21)
    private final int AVOS_ENCODING_PCM_8BIT = 3;               // 3 -> AudioFormat.ENCODING_PCM_8BIT = 3 (API21)
    private final int AVOS_ENCODING_PCM_FLOAT = 4;              // 4 -> AudioFormat.ENCODING_PCM_FLOAT = 4 (API21)
    private final int AVOS_ENCODING_AC3 = 5;                    // 5 -> AudioFormat.ENCODING_AC3 = 5 (API21)
    private final int AVOS_ENCODING_E_AC3 = 6;                  // 6 -> AudioFormat.ENCODING_E_AC3 = 6 (API21)
    private final int AVOS_ENCODING_DTS = 7;                    // 7 -> AudioFormat.ENCODING_DTS = 7 (API23)
    private final int AVOS_ENCODING_DTS_HD = 8;                 // 8 -> AudioFormat.ENCODING_DTS_HD = 8 (API23)
    private final int AVOS_ENCODING_MP3 = 9;                    // 9 -> AudioFormat.ENCODING_MP3 = 9 (API23)
    private final int AVOS_ENCODING_AAC_LC = 10;                // 10 -> AudioFormat.ENCODING_AAC_LC = 10 (API23)
    private final int AVOS_ENCODING_AAC_HE_V1 = 11;             // 11 -> AudioFormat.ENCODING_AAC_HE_V1 = 11 (API23)
    private final int AVOS_ENCODING_AAC_HE_V2 = 12;             // 12 -> AudioFormat.ENCODING_AAC_HE_V2 = 12 (API23)
    private final int AVOS_ENCODING_IEC61937 = 13;              // 13 -> AudioFormat.ENCODING_IEC61937 = 13 (API24)
    private final int AVOS_ENCODING_DOLBY_TRUEHD = 14;          // 14 -> AudioFormat.ENCODING_DOLBY_TRUEHD = 14 (API25)
    private final int AVOS_ENCODING_AAC_ELD = 15;               // 15 -> AudioFormat.ENCODING_AAC_ELD = 15 (API28)
    private final int AVOS_ENCODING_AAC_XHE = 16;               // 16 -> AudioFormat.ENCODING_AAC_XHE = 16 (API28)
    private final int AVOS_ENCODING_AC4 = 17;                   // 17 -> AudioFormat.ENCODING_AC4 = 17 (API28)
    private final int AVOS_ENCODING_E_AC3_JOC = 18;             // 18 -> AudioFormat.ENCODING_E_AC3_JOC = 18 (API29)
    private final int AVOS_ENCODING_DOLBY_MAT = 19;             // 19 -> AudioFormat.ENCODING_DOLBY_MAT = 19 (API29)
    private final int AVOS_ENCODING_OPUS = 20;                  // 20 -> AudioFormat.ENCODING_OPUS = 20 (API30)
    private final int AVOS_ENCODING_PCM_24BIT_PACKED = 21;      // 21 -> AudioFormat.ENCODING_PCM_24BIT_PACKED = 21 (API31)
    private final int AVOS_ENCODING_PCM_32BIT = 22;             // 22 -> AudioFormat.ENCODING_PCM_32BIT = 22 (API31)
    private final int AVOS_ENCODING_MPEGH_BL_L3 = 23;           // 23 -> AudioFormat.ENCODING_MPEGH_BL_L3 = 23 (API31)
    private final int AVOS_ENCODING_MPEGH_BL_L4 = 24;           // 24 -> AudioFormat.ENCODING_MPEGH_BL_L4 = 24 (API31)
    private final int AVOS_ENCODING_MPEGH_LC_L3 = 25;           // 25 -> AudioFormat.ENCODING_MPEGH_LC_L3 = 25 (API31)
    private final int AVOS_ENCODING_MPEGH_LC_L4 = 26;           // 26 -> AudioFormat.ENCODING_MPEGH_LC_L4 = 26 (API31)
    private final int AVOS_ENCODING_DTS_UHD = 27;               // 27 -> AudioFormat.ENCODING_DTS_UHD = 27 (API31)
    private final int AVOS_ENCODING_DRA = 28;                   // 28 -> AudioFormat.ENCODING_DRA = 28 (API31)
    private final int AVOS_ENCODING_DTS_HD_MA = 29;             // 29 -> AudioFormat.ENCODING_DTS_HD_MA = 29 (API34)
    private final int AVOS_ENCODING_DTS_UHD_P1 = 27;            // 27 -> AudioFormat.ENCODING_DTS_UHD_P1 = AVOS_ENCODING_DTS_UHD = 27 (API34)
    private final int AVOS_ENCODING_DTS_UHD_P2 = 30;            // 30 -> AudioFormat.ENCODING_DTS_UHD_P2 = 30 (API34)
    private final int AVOS_ENCODING_DSD = 31;                   // 31 -> AudioFormat.ENCODING_DSD = 31 (API34)
    // TODO: update this variable when adding new encodings
    private final int AVOS_ENCODING_MAX = 31;

    private static volatile boolean isForeground = false;

    public static long getHdmiAudioCodecsFlag() {
        return hdmiAudioEncodingFlag;
    }

    private static SambaDiscovery mSambaDiscovery = null;

    private PropertyChangeListener propertyChangeListener = null;

    private static final VideoStoreImportReceiver videoStoreImportReceiver = new VideoStoreImportReceiver();

    private JcifsUtils jcifsUtils = null;
    private WebdavUtils webdavUtils = null;
    private SmbjUtils smbjUtils = null;
    private SshjUtils sshjUtils = null;
    private FileUtilsQ fileUtilsQ = null;

    private static OpenSubtitlesApiHelper openSubtitlesApiHelper = null;

    private static Context mContext = null;

    public static Context getAppContext() {
        return CustomApplication.mContext;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (BuildConfig.ENABLE_BUG_REPORT) {
            SentryAndroid.init(this, options -> {
                options.setDsn(BuildConfig.SENTRY_DSN);
                options.setSampleRate(null);
                options.setDebug(false);
                options.setEnableSystemEventBreadcrumbs(false);
                });
        }
    }

    public static String BASEDIR;
    private boolean mAutoScraperActive;
    private HttpImageManager mHttpImageManager;

    private static Locale defaultLocale;
    private static Locale systemLocale;

    public CustomApplication() {
        super();
        mAutoScraperActive = false;
    }

    public void setAutoScraperActive(boolean active) {
        mAutoScraperActive = active;
    }

    public boolean isAutoScraperActive() {
        return mAutoScraperActive;
    }

    // store latest video played on a global level
    private static long mLastVideoPlayerId = -42;
    private static Uri mLastVideoPlayedUri = null;
    public static void setLastVideoPlayedId(long videoId) { mLastVideoPlayerId = videoId; }
    public static long getLastVideoPlayedId() { return mLastVideoPlayerId; }
    public static void setLastVideoPlayedUri(Uri videoUri) { mLastVideoPlayedUri = videoUri; }
    public static Uri getLastVideoPlayedUri() { return mLastVideoPlayedUri; }
    public static void resetLastVideoPlayed() {
        setLastVideoPlayedUri(null);
        setLastVideoPlayedId(-42);
    }

    private void getDefaultLocale() {
        getDefaultLocale(this);
    }

    private static void getDefaultLocale(Context context) {
        // Get the locales from the locales_config.xml
        List<Locale> locales = LocaleConfigParser.getLocales(context);
        log.debug("getDefaultLocale: locales={}", locales);
        // Assuming the first locale in the list is the one configured for the application
        if (!locales.isEmpty()) {
            defaultLocale = locales.get(0);
        } else {
            defaultLocale = Locale.getDefault();
        }
        log.debug("getDefaultLocale: systemLocale={}, defaultLocale={}", systemLocale, defaultLocale);
    }

    /**
     * Update IEC61937 encapsulation capability based on HDMI audio encoding flags
     * Called whenever HDMI audio capabilities are detected or changed
     */
    private void updateIecEncapsulationCapability() {
        final int AVOS_ENCODING_IEC61937 = 13;
        isIecEncapsulationCapable = (hdmiAudioEncodingFlag & (1L << AVOS_ENCODING_IEC61937)) != 0;
        log.debug("updateIecEncapsulationCapability: isIecEncapsulationCapable={}", isIecEncapsulationCapable);
    }

    /**
     * Probe whether the device can output multichannel PCM directly (without IEC encapsulation)
     * Only available on API 29+
     * Uses getDirectPlaybackSupport() on API 33+ (preferred, non-deprecated)
     * Falls back to isDirectPlaybackSupported() on API 29-32
     */
    private void updateDirectPcmMultichannelCapability() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || mAudioManager == null || !hasHdmi) {
            isDirectPcmMultichannelCapable = false;
            log.debug("updateDirectPcmMultichannelCapability: API<29 or no HDMI, setting false");
            return;
        }

        boolean supported = false;
        int probedMaxChannels = 2;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build();

            // Probe 7.1 first, then 5.1 to derive max PCM channels supported for direct output
            int[] channelMasks = new int[] {
                    AudioFormat.CHANNEL_OUT_7POINT1,
                    AudioFormat.CHANNEL_OUT_5POINT1
            };

            for (int mask : channelMasks) {
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(mask)
                        .setSampleRate(48000)
                        .build();

                boolean isSupported = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // API 33+: Use getDirectPlaybackSupport() (non-deprecated)
                    try {
                        int support = AudioManager.getDirectPlaybackSupport(format, attrs);
                        // Compare with DIRECT_PLAYBACK_SUPPORTED constant (API 31+) via reflection
                        isSupported = (support == 1); // DIRECT_PLAYBACK_SUPPORTED = 1
                        log.debug("updateDirectPcmMultichannelCapability (API 33+): getDirectPlaybackSupport for mask=0x{} returned {}", Integer.toHexString(mask), support);
                    } catch (NoSuchMethodError e) {
                        log.debug("updateDirectPcmMultichannelCapability: getDirectPlaybackSupport not available, skipping");
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // API 29-32: Use isDirectPlaybackSupported() via reflection
                    try {
                        java.lang.reflect.Method method = AudioManager.class.getMethod("isDirectPlaybackSupported", AudioFormat.class, AudioAttributes.class);
                        isSupported = (Boolean) method.invoke(mAudioManager, format, attrs);
                    } catch (Exception e) {
                        log.debug("updateDirectPcmMultichannelCapability: isDirectPlaybackSupported not available, skipping: {}", e.getMessage());
                    }
                }

                if (isSupported) {
                    supported = true;
                    probedMaxChannels = (mask == AudioFormat.CHANNEL_OUT_7POINT1) ? 8 : 6;
                    log.info("updateDirectPcmMultichannelCapability: direct PCM supported for mask=0x{} (channels={})", Integer.toHexString(mask), probedMaxChannels);
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("updateDirectPcmMultichannelCapability: exception while probing direct PCM", e);
        }

        isDirectPcmMultichannelCapable = supported;
        if (supported && probedMaxChannels > maxAudioChannelCount) {
            maxAudioChannelCount = probedMaxChannels;
        }
        log.info("updateDirectPcmMultichannelCapability: multichannel PCM direct playback supported={}, maxAudioChannelCount={}", supported, maxAudioChannelCount);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        /*
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectAll()
                .penaltyLog()
                .penaltyFlashScreen()
                .build());
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                //.penaltyDeath()
                .build());
        */
        AndroidThreeTen.init(this);

        if (BuildConfig.DEBUG) {
            if (Build.VERSION.SDK_INT <= 31) {
                StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                        .detectLeakedSqlLiteObjects()
                        .detectLeakedClosableObjects()
                        .detectActivityLeaks()
                        .detectLeakedRegistrationObjects()
                        .penaltyLog()
                        .build());
            } else {
                StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                        .detectLeakedSqlLiteObjects()
                        .detectLeakedClosableObjects()
                        .detectActivityLeaks()
                        .detectLeakedRegistrationObjects()
                        .penaltyLog()
                        .detectUnsafeIntentLaunch()
                        .build());
            }
        }

        // register lifecycle observer
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);

        sInstance = this;

        // init application context to make it available to all static methods
        mContext = getApplicationContext();
        // must be done after context is available
        log = LoggerFactory.getLogger(CustomApplication.class);
        setupBouncyCastle();

        systemLocale = Locale.getDefault();
        getDefaultLocale();
        loadLocale();
        log.debug("onCreate: systemLocale={}, defaultLocale={}", systemLocale, defaultLocale);

        // must be done before sambaDiscovery otherwise no context for jcifs
        new Thread(() -> {
            // create instance of jcifsUtils in order to pass context and initial preference
            if (mContext == null) log.warn("onCreate: mContext null!!!");
            if (jcifsUtils == null) jcifsUtils = JcifsUtils.getInstance(mContext);
            if (webdavUtils == null) webdavUtils = WebdavUtils.getInstance(mContext);
            if (smbjUtils == null) smbjUtils = smbjUtils.getInstance(mContext);
            if (sshjUtils == null) sshjUtils = sshjUtils.getInstance(mContext);
            if (fileUtilsQ == null) fileUtilsQ = FileUtilsQ.getInstance(mContext);
        }).start();

        Trakt.initApiKeys(this);
        new Thread() {
            public void run() {
                this.setPriority(Thread.MIN_PRIORITY);
                LibAvos.initAsync(mContext);
            };
        }.start();

        // Initialize picasso thumbnail extension
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
            // for Android versions below 7.1.1 we need to trust letsencrypt certificates
            Picasso.setSingletonInstance(
                    new Picasso.Builder(mContext)
                            .addRequestHandler(new ThumbnailRequestHandler(mContext))
                            .addRequestHandler(new SmbRequestHandler(mContext))
                            .downloader(new TrustingOkHttp3Downloader(mContext))
                            .build()
            );
        } else {
            Picasso.setSingletonInstance(
                    new Picasso.Builder(mContext)
                            .addRequestHandler(new ThumbnailRequestHandler(mContext))
                            .addRequestHandler(new SmbRequestHandler(mContext))
                            .build()
            );
        }

        // Set the dimension of the posters to save
        ScraperImage.setGeneralPosterSize(
                getResources().getDimensionPixelSize(R.dimen.details_poster_width),
                getResources().getDimensionPixelSize(R.dimen.details_poster_height));

        BASEDIR = Environment.getExternalStorageDirectory().getPath()+"Android/data/"+getPackageName();

        // handles NetworkState changes
        networkState = NetworkState.instance(mContext);
        if (propertyChangeListener == null)
            propertyChangeListener = evt -> {
                if (evt.getOldValue() != evt.getNewValue()) {
                    log.trace("NetworkState for {} changed:{} -> {}", evt.getPropertyName(), evt.getOldValue(), evt.getNewValue());
                    launchSambaDiscovery();
                }
            };

        launchSambaDiscovery();

        // init HttpImageManager manager.
        mHttpImageManager = new HttpImageManager(HttpImageManager.createDefaultMemoryCache(), 
                new FileSystemPersistence(BASEDIR));

        // Note: we do not init UPnP here, we wait for the user to enter the network view

        NetworkAutoRefresh.init(this);
        //init credentials db
        NetworkCredentialsDatabase.getInstance().loadCredentials(this);
        ArchosUtils.setGlobalContext(this.getApplicationContext());
        // only launch BootupRecommandation if on AndroidTV and before Android O otherwise target TV channels
        if(ArchosFeatures.isAndroidTV(this) && Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            BootupRecommandationService.init(this);

        log.trace("onCreate: manifest permissions {}", Arrays.toString(getPermissions(mContext)));
        hasManageExternalStoragePermissionInManifest = hasPermission("android.permission.MANAGE_EXTERNAL_STORAGE", mContext);
        log.trace("onCreate: has permission android.permission.MANAGE_EXTERNAL_STORAGE {}", hasManageExternalStoragePermissionInManifest);

        updateVersionState(this);
        if (openSubtitlesApiHelper == null) openSubtitlesApiHelper = OpenSubtitlesApiHelper.getInstance();
        //makeUseOpenSubtitlesRestApi(PreferenceManager.getDefaultSharedPreferences(this).getBoolean(VideoPreferencesCommon.KEY_OPENSUBTITILES_REST_API, true));

        upgradeActions(mContext);

        // Amazon has an "optional" check that when opening IEC61937, the content is stereo
        // It is pushed into some weird vendor callbacks, I have no idea what they are supposed to mean
        // But anyway we can allow IEC61937 @ 8 channels by removing this thing
        try {
            Class<?> fireOSInit = Class.forName("com.amazon.fireos.FireOSInit");
            Field f = fireOSInit.getDeclaredField("sVendorCallbacks");
            f.setAccessible(true);
            Object o = f.get(null);
            Map<Class<?>, Object> m = (Map<Class<?>, Object>) o;
            m.remove(Class.forName("android.media.VendorAudioTrackCallback"));
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException | NullPointerException e) {
        }

        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= 23) {
            // Detect initial audio devices
            AudioDeviceInfo[] devices = mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device : devices) {
                log.debug("onCreate: initial audio device {} {} capabilities {}", device.getType(), device.getProductName(), getSupportedAudioCodecs(getEncodingFlags(device.getEncodings())));
                if (device.getType() == AudioDeviceInfo.TYPE_HDMI) {
                    hasHdmi = true;
                    hdmiAudioEncodingsFlags = device.getEncodings();
                    hdmiAudioEncodingFlag = getEncodingFlags(hdmiAudioEncodingsFlags);
                    updateIecEncapsulationCapability();
                    updateDirectPcmMultichannelCapability();
                    log.debug("onCreate: hdmi initial audio device");
                }
                if (device.getType() == AudioDeviceInfo.TYPE_LINE_DIGITAL) {
                    hasSpdif = true;
                    spdifAudioEncodingsFlags = device.getEncodings();
                    spdifAudioEncodingFlag = getEncodingFlags(spdifAudioEncodingsFlags);
                    log.debug("onCreate: spdif initial audio device");
                }
                break;
            }
        } else {
            // only set hasSpdif since hasHdmi should be caught by the broadcast receiver and be valid for lower APIs
            hasSpdif = true;
        }
    }

    private void launchSambaDiscovery() {
        if (networkState.hasLocalConnection()) {
            log.debug("launchSambaDiscovery: local connection, launching samba discovery");
            // samba discovery should not be running at this stage, but better safe than sorry
            if (mSambaDiscovery != null) {
                mSambaDiscovery.abort();
            }
            try {
                SambaDiscovery.flushShareNameResolver();
                mSambaDiscovery = new SambaDiscovery(mContext);
                mSambaDiscovery.setMinimumUpdatePeriodInMs(0);
                mSambaDiscovery.start();
            } catch (UnsatisfiedLinkError e) {
                log.warn("launchSambaDiscovery: Failed to initialize SambaDiscovery due to missing or corrupted native library", e);
                mSambaDiscovery = null;
            } catch (Exception e) {
                log.error("launchSambaDiscovery: Unexpected error during SambaDiscovery initialization", e);
                mSambaDiscovery = null;
            }
        } else
            log.debug("launchSambaDiscovery: no local connection, doing nothing");
    }

    public static SambaDiscovery getSambaDiscovery() {
        return mSambaDiscovery;
    }

    protected void handleForeGround(boolean foreground) {
        log.debug("handleForeGround: is app foreground {}", foreground);
        if (networkState == null ) networkState = NetworkState.instance(mContext);
        if (foreground) {
            registerHdmiAudioPlugReceiver();
            registerAudioDeviceCallback();
            if (!isVideStoreImportReceiverRegistered) {
                log.debug("handleForeGround: app now in ForeGround registerReceiver for videoStoreImportReceiver");
                ArchosUtils.addBreadcrumb(SentryLevel.INFO, "CustomApplication.handleForeGround", "app now in ForeGround registerReceiver for videoStoreImportReceiver");
                // programmatically register android scanner finished, lifecycle is handled in handleForeGround
                final IntentFilter intentFilter = new IntentFilter();
                intentFilter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
                intentFilter.addDataScheme("file");
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(videoStoreImportReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(videoStoreImportReceiver, intentFilter);
                }
                isVideStoreImportReceiverRegistered = true;
                ArchosUtils.addBreadcrumb(SentryLevel.INFO, "CustomApplication.handleForeGround", "app now in ForeGround register videoStoreImportReceiver");
            } else {
                log.debug("handleForeGround: app now in ForeGround registerReceiver videoStoreImportReceiver already registered");
                ArchosUtils.addBreadcrumb(SentryLevel.INFO, "CustomApplication.handleForeGround", "app now in ForeGround videoStoreImportReceiver already registered");
            }
            if (!isNetworkStateRegistered) {
                log.debug("handleForeGround: app now in ForeGround NetworkState.registerNetworkCallback");
                networkState.registerNetworkCallback();
                isNetworkStateRegistered = true;
            }
            addNetworkListener();
            launchSambaDiscovery();
            // Trigger an incremental Trakt sync when returning to foreground if signed in and not in private mode
            if (Trakt.isTraktV2Enabled(this, PreferenceManager.getDefaultSharedPreferences(this))) {
                TraktService.sync(this, TraktService.FLAG_SYNC_AUTO);
            }
        } else {
            unRegisterHdmiAudioPlugReceiver();
            unRegisterAudioDeviceCallback();
            if (isVideStoreImportReceiverRegistered) {
                log.debug("handleForeGround: app now in BackGround unregisterReceiver for videoStoreImportReceiver");
                ArchosUtils.addBreadcrumb(SentryLevel.INFO, "CustomApplication.handleForeGround", "app now in Background unregister videoStoreImportReceiver");
                unregisterReceiver(videoStoreImportReceiver);
                isVideStoreImportReceiverRegistered = false;
            } else {
                log.debug("handleForeGround: app now in BackGround, videoStoreImportReceiver already unregistered");
                ArchosUtils.addBreadcrumb(SentryLevel.INFO, "CustomApplication.handleForeGround", "app now in Background videoStoreImportReceiver already unregistered");
            }
            if (isNetworkStateRegistered) {
                log.debug("handleForeGround: app now in BackGround NetworkState.unRegisterNetworkCallback");
                networkState.unRegisterNetworkCallback();
                isNetworkStateRegistered = false;
            }
            removeNetworkListener();
        }
    }

    private void registerHdmiAudioPlugReceiver() {
        final IntentFilter intentFilter = new IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG);
        log.debug("registerHdmiAudioPlugReceiver: registerReceiver for ACTION_HDMI_AUDIO_PLUG");
        registerReceiver(mHdmiAudioPlugReceiver, intentFilter);
        isHDMIPlugReceiverRegistered = true;
    }

    private void unRegisterHdmiAudioPlugReceiver() {
        if (isHDMIPlugReceiverRegistered) unregisterReceiver(mHdmiAudioPlugReceiver);
        isHDMIPlugReceiverRegistered = false;
    }

    private void registerAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT >= 23) {
            mAudioDeviceCallback = new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    for (AudioDeviceInfo device : addedDevices) {
                        if (device.getType() == AudioDeviceInfo.TYPE_HDMI) {
                            hasHdmi = true;
                            hdmiAudioEncodingsFlags = device.getEncodings();
                            hdmiAudioEncodingFlag = getEncodingFlags(hdmiAudioEncodingsFlags);
                            updateIecEncapsulationCapability();
                            updateDirectPcmMultichannelCapability();
                            log.debug("registerAudioDeviceCallback: hdmi detected capabilities {}", getSupportedAudioCodecs(hdmiAudioEncodingFlag));
                        }
                        if (device.getType() == AudioDeviceInfo.TYPE_LINE_DIGITAL) {
                            hasSpdif = true;
                            spdifAudioEncodingsFlags = device.getEncodings();
                            spdifAudioEncodingFlag = getEncodingFlags(spdifAudioEncodingsFlags);
                            log.debug("registerAudioDeviceCallback: spdif detected capabilities {}", getSupportedAudioCodecs(spdifAudioEncodingFlag));
                        }
                        break;
                    }
                }
                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    for (AudioDeviceInfo removedDevice : removedDevices) {
                        if (removedDevice.getType() == AudioDeviceInfo.TYPE_HDMI) {
                            hasHdmi = false;
                            hdmiAudioEncodingFlag = 0;
                            hdmiAudioEncodingsFlags = null;
                            isIecEncapsulationCapable = false;  // Clear IEC capability when HDMI removed
                            isDirectPcmMultichannelCapable = false;
                        }
                        if (removedDevice.getType() == AudioDeviceInfo.TYPE_LINE_DIGITAL) {
                            hasSpdif = false;
                            spdifAudioEncodingFlag = 0;
                            spdifAudioEncodingsFlags = null;
                        }
                        break;
                    }
                }
            };
            mAudioManager.registerAudioDeviceCallback(mAudioDeviceCallback, null);
        } else {
            // only set hasSpdif since hasHdmi should be caught by the broadcast receiver and be valid for lower APIs
            hasSpdif = true;
        }
    }

    private void unRegisterAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT >= 23) {
            mAudioManager.unregisterAudioDeviceCallback(mAudioDeviceCallback);
        }
    }

    private final BroadcastReceiver mHdmiAudioPlugReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            log.debug("mHdmiAudioPlugReceiver:onReceive: {}", intent);
            final String action = intent.getAction();
            if (action == null)
                return;
            if (action.equalsIgnoreCase(AudioManager.ACTION_HDMI_AUDIO_PLUG)) {
                hasHdmi = intent.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0) == 1;
                hdmiAudioEncodingFlag = !hasHdmi ? 0 : getEncodingFlags(intent.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS));
                updateIecEncapsulationCapability();
                updateDirectPcmMultichannelCapability();
                final Integer isAudioPlugged = intent.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0);
                if (isAudioPlugged != null) {
                    // maxAudioChannelCount not exploited for now
                    if (isAudioPlugged == 1) {
                        maxAudioChannelCount = intent.getIntExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, 2);
                    }
                }

                log.debug("mHdmiAudioPlugReceiver: received ACTION_HDMI_AUDIO_PLUG, isAudioPlugged={}, hasHdmi={}, maxAudioChannelCount={}, hdmiAudioEncodingFlag={}, iecCapable={}", isAudioPlugged, hasHdmi, maxAudioChannelCount, hdmiAudioEncodingFlag, isIecEncapsulationCapable);
            }
        }
    };

    public static boolean isHdmiConnected() {
        return hasHdmi;
    }

    public static boolean isPassthroughSupported () {
        return hasHdmi || hasSpdif;
    }

    public static String[] audioEncodings = new String[] {"INVALID", "DEFAULT", "PCM_16BIT", "PCM_8BIT", "PCM_FLOAT",
            "AC3", "E_AC3", "DTS", "DTS_HD",
            "MP3", "AAC_LC", "AAC_HE_V1", "AAC_HE_V2",
            "IEC61937", "DOLBY_TRUEHD", "AAC_ELD", "AAC_XHE",
            "AC4", "E_AC3_JOC", "DOLBY_MAT", "OPUS",
            "PCM_24BIT_PACKED", "PCM_32BIT", "MPEGH_BL_L3", "MPEGH_BL_L4",
            "MPEGH_LC_L3", "MPEGH_LC_L4", "DTS_UHD", "DRA", "DTS_HD_MA", "DTS_UHD_P2", "DSD"
    };

    private long getEncodingFlags(int[] encodings) {
        if (encodings == null)
            return 0;
        long encodingFlags = 0;
        for (int encoding : encodings) {
            if (encoding <= AVOS_ENCODING_MAX) {
                encodingFlags |= 1L << encoding;
                log.debug("getEncodingFlags: hdmi RX supports {}", audioEncodings[encoding]);
            } else {
                log.warn("getEncodingFlags: audio encoding {} not identified!!!", encoding);
            }
        }
        log.debug("getEncodingFlags: encodings={}, encodingFlags={}, allHdmiAudioCodecs={}", allHdmiAudioCodecs, Arrays.toString(encodings), encodingFlags);
        return encodingFlags;
    }

    public static String getSupportedAudioCodecs() {
        return getSupportedAudioCodecs(hdmiAudioEncodingFlag);
    }

    public static String getSupportedAudioCodecs(long audioEncodingFlag) {
        StringBuilder supportedCodecs = new StringBuilder();
        log.debug("getSupportedAudioCodecs: audioEncodingFlag={}", audioEncodingFlag);
        for (int i = 2; i < audioEncodings.length; i++) {
            if ((audioEncodingFlag & (1L << i)) != 0) {
                supportedCodecs.append(audioEncodings[i]).append(", ");
            }
        }
        if (supportedCodecs.length() > 0) {
            supportedCodecs.setLength(supportedCodecs.length() - 2);
        }
        return supportedCodecs.toString();
    }

    public static int getMaxAudioChannelCount() {
        return (int) maxAudioChannelCount;
    }

    public static boolean isIecEncapsulationCapable() {
        return isIecEncapsulationCapable;
    }

    /**
     * Calculate appropriate PCM channel limit based on HDMI capabilities
     *
     * For eARC: Force stereo (2 channels) since eARC doesn't support multichannel PCM
     * For regular HDMI: Use reported maxAudioChannelCount
     * For none: Return 0 (no HDMI connected)
     *
     * @return PCM channel limit to pass to native AVOS
     */
    public static int getEffectiveMaxPcmChannels() {
        if (!hasHdmi) {
            log.debug("getEffectiveMaxPcmChannels: No HDMI, returning 0");
            return 0;
        }

        // If device advertises IEC61937, passthrough will be used for compressed formats.
        // Keep PCM cap to the advertised channel count to avoid guessing.
        if (isIecEncapsulationCapable && maxAudioChannelCount > 0) {
            log.debug("getEffectiveMaxPcmChannels: IEC capable, using reported maxAudioChannelCount={} for PCM", maxAudioChannelCount);
            return (int) maxAudioChannelCount;
        }

        // IEC not available: allow multichannel PCM only if direct playback reports support (API 29+)
        if (isDirectPcmMultichannelCapable && maxAudioChannelCount > 2) {
            int pcmCh = (int) maxAudioChannelCount;
            log.info("getEffectiveMaxPcmChannels: IEC not available but direct PCM multichannel supported, using {} channels", pcmCh);
            return pcmCh;
        }

        // Otherwise, cap to stereo for safety
        log.info("getEffectiveMaxPcmChannels: IEC not available and no direct PCM support, capping to 2 channels (maxAudioChannelCount={})", maxAudioChannelCount);
        return 2;
    }

    public static String getSupportedRefreshRates() {
        return supportedRefreshRates;
    }

    public static void setSupportedRefreshRates(String refreshRates) {
        supportedRefreshRates = refreshRates;
    }

    private void addNetworkListener() {
        if (networkState == null) networkState = NetworkState.instance(mContext);
        if (!isNetworkStateListenerAdded && propertyChangeListener != null) {
            log.trace("addNetworkListener: networkState.addPropertyChangeListener");
            networkState.addPropertyChangeListener(propertyChangeListener);
            isNetworkStateListenerAdded = true;
        }
    }

    private void removeNetworkListener() {
        if (networkState == null) networkState = NetworkState.instance(mContext);
        if (isNetworkStateListenerAdded && propertyChangeListener != null) {
            log.trace("removeListener: networkState.removePropertyChangeListener");
            networkState.removePropertyChangeListener(propertyChangeListener);
            isNetworkStateListenerAdded = false;
        }
    }

    public HttpImageManager getHttpImageManager() {
        return mHttpImageManager;
    }

    private static void updateVersionState(Context context) {
        try {
            //this code gets current version-code (after upgrade it will show new versionCode)
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            novaVersionCode = info.versionCode;
            novaVersionName = info.versionName;
            try {
                novaVersionArray = splitVersion(novaVersionName);
                novaLongVersion = "Nova v" + novaVersionArray[0] + "." + novaVersionArray[1] + "." + novaVersionArray[2] +
                        " (" + novaVersionArray[3] + String.format("%02d", novaVersionArray[4]) + String.format("%02d", novaVersionArray[5]) +
                        "." + String.format("%02d", novaVersionArray[6]) + String.format("%02d", novaVersionArray[7]) + ")";
                novaShortVersion = "v" + novaVersionArray[0] + "." + novaVersionArray[1] + "." + novaVersionArray[2];
            } catch (IllegalArgumentException ie) {
                novaVersionArray = new int[] { 0, 0, 0, 0, 0, 0, 0, 0};
                log.error("updateVersionState: cannot split application version {}", novaVersionName);
                novaLongVersion = "Nova v" + novaVersionName;
            }
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
            int previousVersion = sharedPreferences.getInt("current_versionCode", -1);
            sharedPreferences.edit().putString("nova_version", novaLongVersion).apply();
            String previousVersionName = sharedPreferences.getString("current_versionName", "0.0.0");
            try {
                novaPreviousVersionArray = splitVersion(previousVersionName);
            } catch (IllegalArgumentException ie) {
                novaPreviousVersionArray = new int[] { 0, 0, 0, 0, 0, 0, 0, 0};
                log.error("updateVersionState: cannot split application previous version {}", previousVersionName);
            }
            if (previousVersion > 0) {
                if (previousVersion != novaVersionCode) {
                    // got upgraded, save version in current_versionCode and remember former version in previous_versionCode
                    // and indicated that we got updated in app_updated until used and reset
                    sharedPreferences.edit().putInt("current_versionCode", novaVersionCode).commit();
                    sharedPreferences.edit().putInt("previous_versionCode", previousVersion).commit();
                    novaUpdated = true;
                    sharedPreferences.edit().putBoolean("app_updated", true).commit();
                    sharedPreferences.edit().putString("current_versionName", novaVersionName).commit();
                    sharedPreferences.edit().putString("previous_versionName", previousVersionName).commit();
                    log.debug("updateVersionState: update from {}({}) to {}({})", previousVersionName, previousVersion, novaVersionName, novaVersionCode);
                }
            } else {
                // save first app version
                log.debug("updateVersionState: save first version {}", novaVersionCode);
                sharedPreferences.edit().putInt("current_versionCode", novaVersionCode).commit();
                sharedPreferences.edit().putInt("previous_versionCode", -1).commit();
                sharedPreferences.edit().putString("current_versionName", novaVersionName).commit();
                sharedPreferences.edit().putString("previous_versionName", "0.0.0").commit();
            }
        } catch (PackageManager.NameNotFoundException e) {
            log.error("updateVersionState: caught NameNotFoundException", e);
        }
    }

    // takes version major.minor.revision-YYYYMMDD.HHMMSS and convert it into an integer array
    static int[] splitVersion(String version) throws IllegalArgumentException {
        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)-(\\d\\d\\d\\d)(\\d\\d)(\\d\\d)\\.(\\d\\d)(\\d\\d)?").matcher(version);
        if (!m.matches())
            throw new IllegalArgumentException("Malformed application version");
        return new int[] {
                Integer.parseInt(m.group(1)), // major
                Integer.parseInt(m.group(2)), // minor
                Integer.parseInt(m.group(3)), // version
                Integer.parseInt(m.group(4)), // year
                Integer.parseInt(m.group(5)), // month
                Integer.parseInt(m.group(6)), // day
                Integer.parseInt(m.group(7)), // hour
                Integer.parseInt(m.group(8))  // minute
        };
    }

    public static String getChangelog(Context context) {
        log.debug("getChangelog: {}->{}", novaPreviousVersionArray[0], novaVersionArray[0]);
        if (novaPreviousVersionArray[0] > 0 && novaPreviousVersionArray[0] <= 5 && novaVersionArray[0] > 5)
            return context.getResources().getString(R.string.v5_v6_upgrade_info);
        else return null;
    }

    public static void showChangelogDialog(String changelog, final Activity activity) {
        if (changelog == null) {
            clearUpdatedFlag(activity);
            return;
        } else {
            log.debug("showChangelogDialog: changelog is null, nothing to do.");
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.upgrade_info)
            .setMessage(changelog)
            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    clearUpdatedFlag(activity);
                    dialog.cancel();
                    updateVersionState(activity); // be sure not to display twice
                }
            })
            .show();
    }

    private void setupBouncyCastle() {
        final Provider provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
        if (provider == null) {
            return;
        }
        if (provider.getClass().equals(BouncyCastleProvider.class)) {
            // BC with same package name, shouldn't happen in real life.
            return;
        }
        // Android registers its own BC provider. As it might be outdated and might not include
        // all needed ciphers, we substitute it with a known BC bundled in the app.
        // Android's BC has its package rewritten to "com.android.org.bouncycastle" and because
        // of that it's possible to have another BC implementation loaded in VM.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }

    static boolean DBG = false;

    public void loadLocale() {
        // Warning no log.debug at this stage
        getDefaultLocale();
        if (getApplicationContext() == null) return;
        //log.debug("loadLocale: load locale from preferences: {}", language);
        setLocale(getUiLocale(getApplicationContext()));
    }

    public static void loadLocale(Resources resources) {
        // Warning no log.debug at this stage
        // log.debug("loadLocale: load locale from preferences: {}", language);
        getDefaultLocale(CustomApplication.getAppContext());
        setLocale(getUiLocale(CustomApplication.getAppContext()), resources);
    }

    public static String getUiLocale(Context context) {
        if (context == null) return VideoPreferencesCommon.KEY_UI_LANG_SYSTEM;
        return PreferenceManager.getDefaultSharedPreferences(context).getString(VideoPreferencesCommon.KEY_UI_LANG, VideoPreferencesCommon.KEY_UI_LANG_SYSTEM);
    }

    public void setLocale(String localeCode) {
        setLocale(localeCode, getResources());
    }

    public static void setLocale(String localeCode, Resources resources) {
        // Warning no log.debug at this stage
        Locale locale;
        if (localeCode == null || localeCode.isEmpty() || localeCode.equalsIgnoreCase(VideoPreferencesCommon.KEY_UI_LANG_SYSTEM)) {
            locale = systemLocale; // Use system default language
            if (DBG) Log.d("CustomApplication", "setLocale: use system default language = " + locale);
        } else {
            //log.debug("setLocale: use language {}", lang);
            if (DBG) Log.d("CustomApplication", "setLocale: use localeCode " + localeCode);
            locale = VideoPreferencesCommon.getLocaleFromCode(localeCode);
        }
        Locale.setDefault(locale);
        Configuration config = new Configuration();
        config.setLocale(locale);  // Use setLocale() instead of deprecated locale field
        resources.updateConfiguration(config, resources.getDisplayMetrics());
    }

    private void upgradeActions(Context context) {
        log.info("upgradeActions: check for upgrade actions from version: {}.{}.{} to {}.{}.{}", novaPreviousVersionArray[0], novaPreviousVersionArray[1], novaPreviousVersionArray[2], novaVersionArray[0], novaVersionArray[1], novaVersionArray[2]);

        // if nova is upgraded from 6.4.22 and below disable force_passthrough and android frame timing
        if ((novaPreviousVersionArray[0] < 6) ||
            (novaPreviousVersionArray[0] == 6 && novaPreviousVersionArray[1] < 4) ||
            (novaPreviousVersionArray[0] == 6 && novaPreviousVersionArray[1] == 4 && novaPreviousVersionArray[2] <= 22)) {
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putBoolean(VideoPreferencesCommon.KEY_FORCE_AUDIO_PASSTHROUGH, false)
                    .apply();
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putBoolean(PlayerActivity.KEY_ENABLE_ANDROID_FRAME_TIMING, true)
                    .apply();
        }
        // do not replace lastPlayed row with watchingUpNext one since it is still a little slow on shield
        /*
        if (novaPreviousVersionArray[0] == 6 && novaPreviousVersionArray[1] == 4 && novaPreviousVersionArray[2] < 7) {
            // now watch up next row is the next last played
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putBoolean(VideoPreferencesCommon.KEY_SHOW_WATCHING_UP_NEXT_ROW, true)
                    .commit();
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putBoolean(VideoPreferencesCommon.KEY_SHOW_LAST_PLAYED_ROW, false)
                    .commit();
        }
         */
    }

    public static CustomApplication getApplication() {
        return sInstance;
    }

    public static boolean isForeground() {
        return isForeground;
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        isForeground = true;
        // Handle foreground state
        log.debug("onStart: lifecycle app now in ForeGround");
        handleForeGround(true);
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        // Handle background state
        isForeground = false;
        log.debug("onStop: lifecycle app now in BackGround");
        handleForeGround(false);
    }
}
