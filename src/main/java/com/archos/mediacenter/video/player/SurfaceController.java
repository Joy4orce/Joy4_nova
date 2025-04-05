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

package com.archos.mediacenter.video.player;

import com.archos.mediacenter.video.R;

import com.archos.medialib.IMediaPlayer;

import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SurfaceController {

    private static final Logger log = LoggerFactory.getLogger(SurfaceController.class);

    private boolean mEffectEnable = false;

    public void setAlpha(float i) {
        mView.setAlpha(i);
    }

    public class VideoFormat {
        public static final int ORIGINAL = 0;
        public static final int FULLSCREEN = 1;
        /*
         *  for 2:35 video on 4/3 screen:
         *  intermediate surface height in order to don't crop too much video
         */
        public static final int STRETCHED = 2;
        public static final int AUTO = 3;
        public static final double VIDEO_FORMAT_AUTO_THRES = 0.7;

        private final int[] mode = {ORIGINAL, FULLSCREEN, STRETCHED, AUTO};
        private final int max;
        private int idx;
        public VideoFormat(int max) {
            this.max = max;
            this.idx = 0;
        }

        private int getFmt() {
            return mode[idx];
        }
        private void setFmt(int fmt) {
            for (int i = 0; i < max; ++i) {
                if (mode[i] == fmt) {
                    idx = i;
                    return;
                }
            }
            idx = 0;
        }
        private int switchFmt() {
            idx = (idx + 1) % max;
            return mode[idx];
        }

        private int getNextFmt() {
            return mode[(idx + 1) % max];
        }
        public int getMax(){
            return max;
        }
    }
    public interface Listener {
        void onSwitchVideoFormat(int fmt, int autoFmt);
    }

    private View mView;
    private SurfaceView mSurfaceView = null;
    private TextureView mEffectView = null;
    private IMediaPlayer mMediaPlayer = null;
    private SurfaceController.Listener      mSurfaceListener;
    private int         mLcdWidth = 0;
    private int         mLcdHeight = 0;
    private boolean     mHdmiPlugged = false;
    private int         mHdmiWidth = 0;
    private int         mHdmiHeight = 0;
    private int         mVideoWidth = 0;
    private int         mVideoHeight = 0;
    private double      mVideoAspect = 1.0f;
    private VideoFormat mVideoFormat = new VideoFormat(3);
    private VideoFormat mAutoVideoFormat = new VideoFormat(4);
    private int         mSurfaceWidth = 0;
    private int         mSurfaceHeight = 0;
    
    private int mEffectMode = VideoEffect.getDefaultMode();
    private int mEffectType = VideoEffect.getDefaultType();

    private int mCutoutLeft = 0;
    private int mCutoutTop = 0;
    private int mCutoutRight = 0;
    private int mCutoutBottom = 0;

    public SurfaceController(View rootView) {
        ViewGroup mLp = (ViewGroup)rootView;
 
        mEffectView =  (TextureView) mLp.findViewById(R.id.gl_surface_view);
        mSurfaceView =  (SurfaceView) mLp.findViewById(R.id.surface_view);
        if (mEffectEnable) {
            mView = mEffectView;
            mSurfaceView.setVisibility(View.GONE);
         } else {
             mView = mSurfaceView;
             mEffectView.setVisibility(View.GONE);
        }
    }
  
    public void setGLSupportEnabled(boolean enable){
        log.debug("setGLSupportEnabled: {}", enable);
        if (mEffectEnable == enable) return;
        mView.setVisibility(View.GONE);
        if (enable) {
            //Need openGL, let's use TextureView
            mView = mEffectView;
         } else {
             //Do not need openGL, let's use SurfaceView
             mView = mSurfaceView;
        }
        mView.setVisibility(View.VISIBLE);
        mEffectEnable = enable;
    	updateSurface();
    }
    synchronized public void setMediaPlayer(IMediaPlayer player) {
        mMediaPlayer = player;
        updateSurface();
    }

    public void setSurfaceCallback(SurfaceHolder.Callback callback) {
        if (mSurfaceView != null)
            mSurfaceView.getHolder().addCallback(callback);
    }
    
    public boolean supportOpenGLVideoEffect() {
        log.debug("supportOpenGLVideoEffect: {}", (mEffectView == mView) && (VideoEffect.openGLRequested(mEffectType)));
        return (mEffectView == mView) && (VideoEffect.openGLRequested(mEffectType));
    }

    public void setTextureCallback(TextureView.SurfaceTextureListener callback) {
        if (mEffectView != null)
            mEffectView.setSurfaceTextureListener(callback);
    }

    public void setHdmiPlugged(boolean plugged, int hdmiWidth, int hdmiHeight) {
        log.debug("setHdmiPlugged: plugged={}, hdmi=({},{})", plugged, hdmiWidth, hdmiHeight);
        if (plugged != mHdmiPlugged) {
            mHdmiPlugged = plugged;
            mHdmiWidth = hdmiWidth;
            mHdmiHeight = hdmiHeight;
            updateSurface();
        }
    }

    public void setScreenSize(int lcdWidth, int lcdHeight) {
        mLcdWidth = lcdWidth;
        mLcdHeight = lcdHeight;
        updateSurface();
    }

    public void setCutoutMetrics(int cutoutLeft, int cutoutTop, int cutoutRight, int cutoutBottom) {
        if (mCutoutLeft != cutoutLeft || mCutoutTop != cutoutTop || mCutoutRight != cutoutRight || mCutoutBottom != cutoutBottom) {
            mCutoutLeft = cutoutLeft;
            mCutoutTop = cutoutTop;
            mCutoutRight = cutoutRight;
            mCutoutBottom = cutoutBottom;
            updateSurface();
        }
    }

    public void setVideoSize(int videoWidth, int videoHeight, double aspect) {
        if (mVideoWidth != videoWidth || mVideoHeight != videoHeight || mVideoAspect != aspect) {
            mVideoWidth = videoWidth;
            mVideoHeight = videoHeight;
            mVideoAspect = aspect;
            updateSurface();
        }
    }

    public void setListener(SurfaceController.Listener listener) {
        mSurfaceListener = listener;
    }
    public int getMax(){
        return getVideoFormat().getMax();
    }
    public int getCurrentFormat(){
        log.debug("getCurrentFormat: {}", getVideoFormat().getFmt());
        return getVideoFormat().getFmt();
    }
    private VideoFormat getVideoFormat() {
        if (!mHdmiPlugged && ((mVideoWidth / (double) mVideoHeight) - (mLcdWidth / (double) mLcdHeight) > VideoFormat.VIDEO_FORMAT_AUTO_THRES)) {
            // on special screen sizes that are closer to 4:3 then enable the "optimized" aspect ratio
            log.debug("getVideoFormat: return mAutoVideoFormat");
            return mAutoVideoFormat;
        } else {
            log.debug("getVideoFormat: return mVideoFormat");
            return mVideoFormat;
        }
    }

    public void switchVideoFormat() {
        log.debug("switchVideoFormat");
        getVideoFormat().switchFmt();
        updateSurface();
        if (mSurfaceListener != null) {
            mSurfaceListener.onSwitchVideoFormat(mVideoFormat.getFmt(), mAutoVideoFormat.getFmt());
        }
    }
    public void setVideoFormat(int fmt) {
        log.debug("setVideoFormat fmt=" + fmt);
        getVideoFormat().setFmt(fmt);
        updateSurface();
        if (mSurfaceListener != null) {
            mSurfaceListener.onSwitchVideoFormat(mVideoFormat.getFmt(), mAutoVideoFormat.getFmt());
        }
    }
    public void setVideoFormat(int fmt, int autoFmt) {
        log.debug("setVideoFormat fmt={}, autoFmt={}", fmt, autoFmt);
        mVideoFormat.setFmt(fmt);
        mAutoVideoFormat.setFmt(autoFmt);
        updateSurface();
    }

    public int getNextVideoFormat() {
        return getVideoFormat().getNextFmt();
    }

    public void setEffectMode(int mode) {
        mEffectMode = mode;
        updateSurface();
    }
    
    public void setEffectType(int type) {
        mEffectType = type;
        updateSurface();
    }
    
    synchronized private void updateSurface() {
        log.debug("updateSurface");
        // get screen size
        int dw, dh, vw, vh, fmt;
        float cropW = 1.0f;
        float cropH = 1.0f;
        double par = mVideoAspect;

        if (mHdmiPlugged) {
            dw = mHdmiWidth;
            dh = mHdmiHeight;
            log.debug("CONFIG updateSurface: hdmi plugged d=({},{})", dw, dh);
        } else {
            dw = mLcdWidth;
            dh = mLcdHeight;
            log.debug("CONFIG updateSurface: lcd plugged d=({},{})", dw, dh);
        }

        // display width and height without cutout
        int dcw = dw - mCutoutLeft - mCutoutRight;
        int dch = dh - mCutoutTop - mCutoutBottom;
        vw = mVideoWidth;
        vh = mVideoHeight;

        log.debug("CONFIG updateSurface: v=({},{})", vw, vh);

        if (mMediaPlayer == null) log.warn("updateSurface: mMediaPlayer is null!");
        if (vw <= 0 || vh <= 0 || dcw <= 0 || dch <= 0 || mMediaPlayer == null)
            return;
        fmt = getVideoFormat().getFmt();

        if (mEffectEnable) {
            fmt = VideoFormat.FULLSCREEN; //only FULLSCREEN FORMAT is currently supported in OpenGL rendering
        }

        // calculate aspect ratio
        double sar = (double) vw / (double) vh; // sar = source aspect ratio (video)
        double ar = par * sar; // ar = aspect ratio of the video
        double dar = (double) dw / (double) dh; // display aspect ratio
        double dcar = (double) dcw / (double) dch; // display aspect ratio without cutout

        log.debug("CONFIG updateSurface: sar={}, ar={}, dar={}, dcar={}", sar, ar, dar, dcar);

        cropW = cropH = 1.0f;
        switch (fmt) {
            case VideoFormat.ORIGINAL:
                if (dcar < ar) {
                    //4:3 movie on 16:9 screen or 16:9 movie on portrait screen
                    dch = (int) (dcw/ (ar));
                    log.debug("CONFIG updateSurface: VideoFormat.ORIGINAL dcar<ar dch={}", dch);
                } else {
                    //16:9 movie on 4:3 screen
                    dcw = (int) (dch * ar);
                    log.debug("CONFIG updateSurface: VideoFormat.ORIGINAL dcar>=ar dcw={}", dcw);
                }
                break;
            case VideoFormat.FULLSCREEN:
                if (dcar < ar) {
                    //4:3 movie on 16:9 screen
                    cropW = (float)dcar / (float)ar;
                    cropH = 1.0f;
                    log.debug("CONFIG updateSurface: VideoFormat.FULLSCREEN dcar<ar 4:3 movie on 16:9 screen dc=({},{}), crop=({},{})", dcw, dch, cropW, cropH);
                } else {
                    //16:9 movie on 4:3 screen
                    cropH = (float)ar / (float)dcar;
                    cropW = 1.0f;
                    log.debug("CONFIG updateSurface: VideoFormat.FULLSCREEN dcar>=ar 16:9 movie on 4:3 screen dc=({},{}), crop=({},{})", dcw, dch, cropW, cropH);
                }
                break;
            case VideoFormat.AUTO: {
                cropW = 1.0f;
                cropH = 1.0f;
                if (dcar > ar) {
                    dcw = dcw + (((int) (dch * ar)) - dcw) / 2;
                    cropH = (float) dch / (float) (dcw / ar);
                    log.debug("CONFIG updateSurface: VideoFormat.AUTO dcar>ar dc=({},{})", dcw, dch);
                } else {
                    dch = dch + (((int) (dcw / ar)) - dch) / 2;
                    cropW = (float) dcw / (float) (dch * ar);
                    log.debug("CONFIG updateSurface: VideoFormat.AUTO dcar<=ar dc=({},{})", dcw, dch);
                }
                break;
            }
            case VideoFormat.STRETCHED: { // display on full screen resolution stretched: keep dcw and dch
                cropW = 1.0f;
                cropH = 1.0f;
                log.debug("CONFIG updateSurface: VideoFormat.STRETCHED dc=({},{}), crop=({},{})", dcw, dch, cropW, cropH);
                break;
            }
        }

        if (((mEffectMode & VideoEffect.TB_MODE)!=0) && (ar <= 1.5)) dcw *= 2;
        if (((mEffectMode & VideoEffect.SBS_MODE)!=0) && (ar >= 3.0)) dch *= 2;

        log.debug("CONFIG updateSurface: setFixedSize({},{})", vw, vh);

        if (mSurfaceView != null) mSurfaceView.getHolder().setFixedSize(vw, vh);

        dcw = Math.round(dcw  / cropW);
        dch = Math.round(dch / cropH);

        log.debug("CONFIG updateSurface: setLayoutParams({},{})", dcw, dch);

        // set the view size to display size but keep the cutout margins
        ViewGroup.LayoutParams lp = mView.getLayoutParams();
        lp.width = dcw;
        lp.height = dch;
        // Note: do not set margins of mCutoutLeft, mCutoutTop, mCutoutRight, mCutoutBottom here, delegate to PlayerActivity
        mView.setLayoutParams(lp);
        mView.invalidate();
        mSurfaceWidth = dcw;
        mSurfaceHeight = dch;
        log.debug("CONFIG updateSurface: ({},{})->({},{}) / formatCrop: ({},{}) / mEffectMode: {}", vw, vh, dcw, dch, cropW, cropH, mEffectMode);
    }

    public int getViewWidth() { return mSurfaceWidth; }
    public int getViewHeight() { return mSurfaceHeight; }
}
