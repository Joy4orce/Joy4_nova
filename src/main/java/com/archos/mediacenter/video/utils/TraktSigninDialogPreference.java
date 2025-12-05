// Copyright 2017 Archos SA
// Copyright 2020 Courville Software
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

package com.archos.mediacenter.video.utils;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.util.AttributeSet;

import androidx.preference.Preference;
import androidx.preference.PreferenceManager;

import com.archos.mediacenter.utils.trakt.Trakt;
import com.archos.mediacenter.video.R;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TraktSigninDialogPreference extends Preference {

    private static final Logger log = LoggerFactory.getLogger(TraktSigninDialogPreference.class);
    private static final int REQUEST_CODE_DEVICE_AUTH = 1001;

    private DialogInterface.OnDismissListener mOnDismissListener;

    public TraktSigninDialogPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TraktSigninDialogPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        this.setKey(Trakt.KEY_TRAKT_USER);
    }

    public SharedPreferences getSharedPreferences(){
        if(super.getSharedPreferences()==null)
            return PreferenceManager.getDefaultSharedPreferences(getContext()); //when used by non-preference activity
        else
            return super.getSharedPreferences();
    }

    @Override
    protected void onClick() {
        performDeviceAuth();
    }

    /**
     * Public method to trigger device authentication (called by other activities)
     */
    public void performDeviceAuth() {
        log.debug("performDeviceAuth: TraktSigninDialogPreference clicked!");

        Activity activity = getActivityFromContext(getContext());
        if (activity == null) {
            log.error("performDeviceAuth: could not resolve Activity from context type {}", getContext() != null ? getContext().getClass().getName() : "null");
            return;
        }

        if (activity.isFinishing() || activity.isDestroyed()) {
            log.debug("performDeviceAuth: activity is finishing or destroyed");
            return;
        }

        log.debug("performDeviceAuth: launching TraktDeviceAuthActivity for device flow authentication");

        // Launch device authentication activity
        Intent intent = new Intent(activity, TraktDeviceAuthActivity.class);
        activity.startActivityForResult(intent, REQUEST_CODE_DEVICE_AUTH);
    }

    /**
     * Call this method from the parent Activity's onActivityResult to handle auth completion
     */
    public void onActivityResult(int requestCode, int resultCode) {
        if (requestCode == REQUEST_CODE_DEVICE_AUTH) {
            if (resultCode == Activity.RESULT_OK) {
                log.debug("onActivityResult: device auth successful");
                // Notify that the preference has changed so UI updates
                notifyChanged();
                if (mOnDismissListener != null) {
                    mOnDismissListener.onDismiss(null);
                }
            } else {
                log.debug("onActivityResult: device auth cancelled or failed");
            }
        }
    }

    public void setOnDismissListener(DialogInterface.OnDismissListener onDismissListener){
        mOnDismissListener = onDismissListener;
    }

    public void showDialog(boolean show) {
        if(show) {
            log.debug("showDialog: trigger onClick");
            this.onClick();
        }
    }

    /**
     * Legacy method for dialog management - no-op since we now use Activity
     * @deprecated Device flow uses Activity instead of Dialog
     */
    @Deprecated
    public boolean isDialogShowing() {
        return false;
    }

    /**
     * Legacy method for dialog management - no-op since we now use Activity
     * @deprecated Device flow uses Activity instead of Dialog
     */
    @Deprecated
    public void dismissDialog() {
        // No-op: Activity lifecycle is managed by Android system
    }

    private Activity getActivityFromContext(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

}
