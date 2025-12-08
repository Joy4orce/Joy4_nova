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
import android.view.Gravity;
import android.widget.FrameLayout;

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
        public static final int FULL_WIDTH = 1;
        /*
         *  for 2:35 video on 4/3 screen:
         *  intermediate surface height in order to don't crop too much video
         */
        public static final int FULL_SCREEN = 2;
        public static final int FORCE43 = 3;
        public static final int FORCE169 = 4;
        public static final int FORCE185 = 5;
        public static final int FORCE239 = 6;
        public static final int AUTO = 7;
        
        public static final double VIDEO_FORMAT_AUTO_THRES = 0.7;

        private final int[] mode = {ORIGINAL, FULL_WIDTH, FULL_SCREEN, FORCE43, FORCE169, FORCE185, FORCE239, AUTO};
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
    private VideoFormat mVideoFormat = new VideoFormat(7);
    private VideoFormat mAutoVideoFormat = new VideoFormat(8);
    private int         mSurfaceWidth = 0;
    private int         mSurfaceHeight = 0;
    
    private int mEffectMode = VideoEffect.getDefaultMode();
    private int mEffectType = VideoEffect.getDefaultType();

    private int mCutoutLeft = 0;
    private int mCutoutTop = 0;
    private int mCutoutRight = 0;
    private int mCutoutBottom = 0;
    private int mMarginLeft = 0;
    private int mMarginTop = 0;

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
        if (log.isDebugEnabled()) log.debug("setGLSupportEnabled: {}", enable);
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
        if (log.isDebugEnabled()) log.debug("supportOpenGLVideoEffect: {}", (mEffectView == mView) && (VideoEffect.openGLRequested(mEffectType)));
        return (mEffectView == mView) && (VideoEffect.openGLRequested(mEffectType));
    }

    public void setTextureCallback(TextureView.SurfaceTextureListener callback) {
        if (mEffectView != null)
            mEffectView.setSurfaceTextureListener(callback);
    }

    public void setHdmiPlugged(boolean plugged, int hdmiWidth, int hdmiHeight) {
        if (log.isDebugEnabled()) log.debug("setHdmiPlugged: plugged={}, hdmi=({},{})", plugged, hdmiWidth, hdmiHeight);
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
        if (log.isDebugEnabled()) log.debug("getCurrentFormat: {}", getVideoFormat().getFmt());
        return getVideoFormat().getFmt();
    }
    private VideoFormat getVideoFormat() {
        if (!mHdmiPlugged && ((mVideoWidth / (double) mVideoHeight) - (mLcdWidth / (double) mLcdHeight) > VideoFormat.VIDEO_FORMAT_AUTO_THRES)) {
            // on special screen sizes that are closer to 4:3 then enable the "optimized" aspect ratio
            if (log.isDebugEnabled()) log.debug("getVideoFormat: return mAutoVideoFormat");
            return mAutoVideoFormat;
        } else {
            if (log.isDebugEnabled()) log.debug("getVideoFormat: return mVideoFormat");
            return mVideoFormat;
        }
    }

    public void switchVideoFormat() {
        if (log.isDebugEnabled()) log.debug("switchVideoFormat");
        getVideoFormat().switchFmt();
        updateSurface();
        if (mSurfaceListener != null) {
            mSurfaceListener.onSwitchVideoFormat(mVideoFormat.getFmt(), mAutoVideoFormat.getFmt());
        }
    }
    public void setVideoFormat(int fmt) {
        if (log.isDebugEnabled()) log.debug("setVideoFormat fmt={}", fmt);
        getVideoFormat().setFmt(fmt);
        updateSurface();
        if (mSurfaceListener != null) {
            mSurfaceListener.onSwitchVideoFormat(mVideoFormat.getFmt(), mAutoVideoFormat.getFmt());
        }
    }
    public void setVideoFormat(int fmt, int autoFmt) {
        if (log.isDebugEnabled()) log.debug("setVideoFormat fmt={}, autoFmt={}", fmt, autoFmt);
        mVideoFormat.setFmt(fmt);
        mAutoVideoFormat.setFmt(autoFmt);
        updateSurface();
    }

    public int getNextVideoFormat() {
        return getVideoFormat().getNextFmt();
    }

    public int getCurrentVideoFormat() {
        return getVideoFormat().getFmt();
    }

    public void setEffectMode(int mode) {
        mEffectMode = mode;
        updateSurface();
    }
    
    public void setEffectType(int type) {
        mEffectType = type;
        updateSurface();
    }
    
    public void setProjectorMode(boolean projectorMode){
        //Get the layout paramters for the Views.
        FrameLayout.LayoutParams paramsEffect = (FrameLayout.LayoutParams) mEffectView.getLayoutParams();
        FrameLayout.LayoutParams paramsSurface = (FrameLayout.LayoutParams) mSurfaceView.getLayoutParams();

        // Set gravity to top and center horizontally if we are in projector mode.
        paramsEffect.gravity = projectorMode ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER;
        paramsSurface.gravity = projectorMode ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER;

        // Set the new layout parameters
        mSurfaceView.setLayoutParams(paramsSurface);
        mEffectView.setLayoutParams(paramsEffect);
    }
    
    synchronized private void updateSurface() {
        if (log.isDebugEnabled()) log.debug("updateSurface");
        // get screen size
        int dw, dh, vw, vh, fmt;
        float cropW = 1.0f;
        float cropH = 1.0f;
        double par = mVideoAspect;

        if (mHdmiPlugged) {
            dw = mHdmiWidth;
            dh = mHdmiHeight;
            if (log.isDebugEnabled()) log.debug("CONFIG updateSurface: hdmi plugged d=({},{})", dw, dh);
        } else {
            dw = mLcdWidth;
            dh = mLcdHeight;
            if (log.isDebugEnabled()) log.debug("CONFIG updateSurface: lcd plugged d=({},{})", dw, dh);
        }

        // display width and height without cutout
        // When HDMI is plugged, do not apply phone's cutout metrics to external display
        int dcw = mHdmiPlugged ? dw : (dw - mCutoutLeft - mCutoutRight);
        int dch = mHdmiPlugged ? dh : (dh - mCutoutTop - mCutoutBottom);
        vw = mVideoWidth;
        vh = mVideoHeight;

        if (log.isDebugEnabled()) log.debug("CONFIG updateSurface: v=({},{})", vw, vh);

        if (mMediaPlayer == null) log.warn("updateSurface: mMediaPlayer is null!");
        if (vw <= 0 || vh <= 0 || dcw <= 0 || dch <= 0 || mMediaPlayer == null)
            return;
        fmt = getVideoFormat().getFmt();

        if (mEffectEnable) {
            fmt = VideoFormat.FULL_WIDTH; //only FULL_WIDTH FORMAT is currently supported in OpenGL rendering
        }

        // calculate aspect ratio
        double sar = (double) vw / (double) vh; // sar = source aspect ratio (video)

        //OK, so it was Married with Children having bad sources, not NoVas fault!
        
        //Do the Aspect Ratio Override if required.
        double ar = switch (fmt) {
            case VideoFormat.FORCE43 -> 4f / 3f;
            case VideoFormat.FORCE169 -> 16f / 9f;
            case VideoFormat.FORCE185 -> 1.85f;
            case VideoFormat.FORCE239 -> 2.39f;
            default -> par * sar;
        };

        //Use the aspect ratio from the decoder if we have one, otherwise calculate one ourselves.
        
        //Get the Display aspect ratio, with and without cutouts.
        double dar = (double) dw / (double) dh; // display aspect ratio
        double dcar = (double) dcw / (double) dch; // display aspect ratio without cutout

        if (log.isDebugEnabled()) log.debug("CONFIG updateSurface: sar={}, ar={}, dar={}, dcar={}", sar, ar, dar, dcar);

        //cropW = cropH = 1.0f;
        switch (fmt) {
            case VideoFormat.ORIGINAL, VideoFormat.FORCE43, VideoFormat.FORCE169, VideoFormat.FORCE185, VideoFormat.FORCE239:
                if (dcar < ar) {
                    //4:3 movie on 16:9 screen or 16:9 movie on portrait screen
                    dch = (int) (dcw/ (ar));
                    if (log.isDebugEnabled()) log.debug("CONFIG updateSurface: VideoFormat.ORIGINAL dcar<ar dch={}", dch);
                } else {
                    //16:9 movie on 4:3 screen
                    dcw = (int) (dch * ar);
                    if (log.isDebugEnabled()) log.debug("CONFIG updateSurface: VideoFormat.ORIGINAL dcar>=ar dcw={}", dcw);
                }
                break;
            case VideoFormat.FULL_WIDTH:
                //Height can go over the screen top, but set width.
                dch = (int) (dcw/ (ar));
                break;
            case VideoFormat.FULL_SCREEN: { // display on full screen resolution stretched: keep dcw and dch
                //cropW = 1.0f;
                //cropH = 1.0f;
                //if (log.isDebugEnabled()) log.debug("CONFIG updateSurface: VideoFormat.FULL_SCREEN dc=({},{}), crop=({},{})", dcw, dch, cropW, cropH);
                break;
            }
            case VideoFormat.AUTO: {
                //cropW = 1.0f;
                //cropH = 1.0f;
                if (dcar > ar) {
                    dcw = dcw + (((int) (dch * ar)) - dcw) / 2;
                    cropH = (float) dch / (float) (dcw / ar);
                    if (log.isDebugEnabled()) log.debug("CONFIG updateSurface: VideoFormat.AUTO dcar>ar dc=({},{})", dcw, dch);
                } else {
                    dch = dch + (((int) (dcw / ar)) - dch) / 2;
                    cropW = (float) dcw / (float) (dch * ar);
                    if (log.isDebugEnabled()) log.debug("CONFIG updateSurface: VideoFormat.AUTO dcar<=ar dc=({},{})", dcw, dch);
                }
                break;
            }
        }

        if (((mEffectMode & VideoEffect.TB_MODE)!=0) && (ar <= 1.5)) dcw *= 2;
        if (((mEffectMode & VideoEffect.SBS_MODE)!=0) && (ar >= 3.0)) dch *= 2;

        if (log.isDebugEnabled()) log.debug("CONFIG updateSurface: setFixedSize({},{})", vw, vh);

        if (mSurfaceView != null) mSurfaceView.getHolder().setFixedSize(vw, vh);

        dcw = Math.round(dcw  / cropW);
        dch = Math.round(dch / cropH);

        if (log.isDebugEnabled()) log.debug("CONFIG updateSurface: setLayoutParams({},{})", dcw, dch);

        // margins to avoid cutout
        // When HDMI is plugged, do not apply phone's cutout margins to external display
        mMarginLeft = mHdmiPlugged ? 0 : (int)((mCutoutLeft - mCutoutRight)/ 2.0f);
        mMarginTop = mHdmiPlugged ? 0 : (int)((mCutoutTop - mCutoutBottom)/ 2.0f);

        ViewGroup.LayoutParams lp = mView.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams marginParams) {
            if (log.isDebugEnabled()) log.debug("MARC works with MarginLayoutParams"); // TODO MARC it works!!!
            lp.width = dcw;
            lp.height = dch;
            // video view is centered on the screen, in order to avoid cutout it needs to be shifted slightly
            marginParams.setMargins(mMarginLeft, mMarginTop, 0, 0);
            mView.setLayoutParams(marginParams);
        } else {
            if (log.isDebugEnabled()) log.debug("MARC works with LayoutParams NO MARGIN");
            lp.width = dcw;
            lp.height = dch;
            mView.setLayoutParams(lp);
        }
        mView.invalidate();

        mSurfaceWidth = dcw;
        mSurfaceHeight = dch;
        if (log.isDebugEnabled()) log.debug("CONFIG updateSurface: ({},{})->({},{}) / formatCrop: ({},{}) / mEffectMode: {}", vw, vh, dcw, dch, cropW, cropH, mEffectMode);
    }

    public int getViewWidth() { return mSurfaceWidth; }
    public int getViewHeight() { return mSurfaceHeight; }
    public int getMarginLeft() { return mMarginLeft; }
    public int getMarginTop() { return mMarginTop; }
}
