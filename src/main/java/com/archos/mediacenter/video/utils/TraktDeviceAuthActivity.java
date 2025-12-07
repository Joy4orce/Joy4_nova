// Copyright 2024 Courville Software
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

import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.archos.mediacenter.utils.trakt.Trakt;
import com.archos.mediacenter.video.R;
import com.archos.mediacenter.video.utils.MiscUtils;
import com.archos.mediacenter.video.utils.oauth.OAuthCallback;
import com.archos.mediacenter.video.utils.oauth.OAuthData;
import com.archos.mediacenter.video.utils.oauth.OAuthDialog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TraktDeviceAuthActivity extends AppCompatActivity {

    private static final Logger log = LoggerFactory.getLogger(TraktDeviceAuthActivity.class);

    private TextView mVerificationUrlText;
    private TextView mUserCodeText;
    private TextView mStatusText;
    private ProgressBar mProgressBar;
    private Button mCancelButton;

    private Trakt.deviceCode mDeviceCode;
    private Handler mPollHandler;
    private Runnable mPollRunnable;
    private long mExpirationTime;
    private boolean mIsPolling = false;
    private boolean mIsTv = false;
    private OAuthDialog mOAuthDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mIsTv = MiscUtils.isAndroidTV(this);
        if (!mIsTv) {
            // Phone/tablet: use embedded OAuth dialog
            startPhoneAuth();
            return;
        }
        setContentView(R.layout.activity_trakt_device_auth);

        // Bind views
        mVerificationUrlText = findViewById(R.id.verification_url);
        mUserCodeText = findViewById(R.id.user_code);
        mStatusText = findViewById(R.id.status_message);
        mProgressBar = findViewById(R.id.progress_bar);
        mCancelButton = findViewById(R.id.cancel_button);

        mCancelButton.setOnClickListener(v -> {
            stopPolling();
            finish();
        });
        // Default focus to cancel so DPAD-OK works immediately
        mCancelButton.requestFocus();

        // Generate device code
        new GenerateDeviceCodeTask().execute();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
        if (mOAuthDialog != null && mOAuthDialog.isShowing()) {
            mOAuthDialog.dismiss();
        }
    }

    private void stopPolling() {
        mIsPolling = false;
        if (mPollHandler != null && mPollRunnable != null) {
            mPollHandler.removeCallbacks(mPollRunnable);
        }
    }

    private void startPolling() {
        if (mDeviceCode == null) {
            log.error("startPolling: device code is null");
            return;
        }

        mIsPolling = true;
        mExpirationTime = System.currentTimeMillis() + (mDeviceCode.expires_in * 1000L);
        mPollHandler = new Handler();

        mPollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!mIsPolling) {
                    return;
                }

                // Check if code expired
                if (System.currentTimeMillis() >= mExpirationTime) {
                    if (log.isDebugEnabled()) log.debug("Device code expired");
                    onAuthenticationFailed(getString(R.string.trakt_device_auth_timeout));
                    return;
                }

                // Poll for token
                new ExchangeDeviceCodeTask().execute(mDeviceCode.device_code);
            }
        };

        // Start first poll after interval
        mPollHandler.postDelayed(mPollRunnable, mDeviceCode.interval * 1000L);
    }

    private void onAuthenticationSuccess(Trakt.accessToken token) {
        if (log.isDebugEnabled()) log.debug("onAuthenticationSuccess");
        stopPolling();

        // Store tokens
        Trakt.setAccessToken(PreferenceManager.getDefaultSharedPreferences(this), token.access_token);
        Trakt.setRefreshToken(PreferenceManager.getDefaultSharedPreferences(this), token.refresh_token);
        Trakt.setAccountLocked(PreferenceManager.getDefaultSharedPreferences(this), false);
        // Disable collection sync to avoid hitting Trakt library limits
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putBoolean(VideoPreferencesCommon.KEY_TRAKT_SYNC_COLLECTION, false)
                .apply();

        if (!mIsTv) {
            setResult(RESULT_OK);
            finish();
            return;
        }

        // Update UI
        mStatusText.setText(R.string.trakt_device_auth_success);
        mProgressBar.setVisibility(View.GONE);

        // Finish activity after short delay
        new Handler().postDelayed(() -> {
            setResult(RESULT_OK);
            finish();
        }, 1500);
    }

    private void onAuthenticationFailed(String message) {
        if (log.isDebugEnabled()) log.debug("onAuthenticationFailed: {}", message);
        stopPolling();

        if (!mIsTv) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        mStatusText.setText(message);
        mProgressBar.setVisibility(View.GONE);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();

        // Change button to retry
        mCancelButton.setText(R.string.trakt_signin);
        mCancelButton.setOnClickListener(v -> {
            // Retry
            mProgressBar.setVisibility(View.VISIBLE);
            mStatusText.setText(R.string.trakt_device_auth_waiting);
            mCancelButton.setText(android.R.string.cancel);
            mCancelButton.setOnClickListener(v2 -> {
                stopPolling();
                finish();
            });
            new GenerateDeviceCodeTask().execute();
        });
    }

    private void startPhoneAuth() {
        if (log.isDebugEnabled()) log.debug("startPhoneAuth: showing OAuth dialog");
        try {
            OAuthData oauthData = new OAuthData();
            mOAuthDialog = new OAuthDialog(this, new OAuthCallback() {
                @Override
                public void onFinished(OAuthData data) {
                    if (data != null && data.code != null) {
                        if (log.isDebugEnabled()) log.debug("startPhoneAuth: received auth code, exchanging");
                        new ExchangeAuthCodeTask().execute(data.code);
                    } else {
                        log.warn("startPhoneAuth: auth cancelled or no code returned");
                        Toast.makeText(TraktDeviceAuthActivity.this, R.string.trakt_device_auth_error, Toast.LENGTH_LONG).show();
                        finish();
                    }
                }
            }, oauthData, Trakt.getAuthorizationRequest(PreferenceManager.getDefaultSharedPreferences(this)));
            mOAuthDialog.show();
        } catch (Exception e) {
            log.error("startPhoneAuth: failed to start auth dialog", e);
            Toast.makeText(this, R.string.trakt_device_auth_error, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /**
     * AsyncTask to generate device code in background
     */
    private class GenerateDeviceCodeTask extends AsyncTask<Void, Void, Trakt.deviceCode> {
        private boolean mAccountLocked = false;
        @Override
        protected Trakt.deviceCode doInBackground(Void... params) {
            try {
                return Trakt.generateDeviceCode();
            } catch (Trakt.AccountLockedError e) {
                mAccountLocked = true;
                return null;
            }
        }

        @Override
        protected void onPostExecute(Trakt.deviceCode result) {
            if (result != null) {
                mDeviceCode = result;
                if (log.isDebugEnabled()) log.debug("Device code generated: user_code={}", result.user_code);

                // Update UI with codes
                mVerificationUrlText.setText(result.verification_url);
                mUserCodeText.setText(result.user_code);

                // Start polling
                startPolling();
            } else {
                log.error("Failed to generate device code");
                if (mAccountLocked) {
                    onAuthenticationFailed(getString(R.string.trakt_account_locked));
                } else {
                    onAuthenticationFailed(getString(R.string.trakt_device_auth_error));
                }
            }
        }
    }

    /**
     * AsyncTask to exchange device code for access token
     */
    private class ExchangeDeviceCodeTask extends AsyncTask<String, Void, Trakt.accessToken> {
        private boolean mAccountLocked = false;
        @Override
        protected Trakt.accessToken doInBackground(String... params) {
            String deviceCode = params[0];
            try {
                return Trakt.exchangeDeviceCodeForAccessToken(deviceCode);
            } catch (Trakt.AccountLockedError e) {
                mAccountLocked = true;
                return null;
            }
        }

        @Override
        protected void onPostExecute(Trakt.accessToken result) {
            if (result != null) {
                // Success! User authorized
                if (log.isDebugEnabled()) log.debug("Access token received");
                onAuthenticationSuccess(result);
            } else {
                // Still pending or error - continue polling if not expired
                if (mAccountLocked) {
                    onAuthenticationFailed(getString(R.string.trakt_account_locked));
                } else if (mIsPolling && System.currentTimeMillis() < mExpirationTime) {
                    mPollHandler.postDelayed(mPollRunnable, mDeviceCode.interval * 1000L);
                } else if (!mIsPolling) {
                    if (log.isDebugEnabled()) log.debug("Polling stopped");
                } else {
                    if (log.isDebugEnabled()) log.debug("Code expired during polling");
                    if (mAccountLocked) {
                        onAuthenticationFailed(getString(R.string.trakt_account_locked));
                    } else {
                        onAuthenticationFailed(getString(R.string.trakt_device_auth_timeout));
                    }
                }
            }
        }
    }

    /**
     * AsyncTask to exchange OAuth authorization code (phone/tablet flow)
     */
    private class ExchangeAuthCodeTask extends AsyncTask<String, Void, Trakt.accessToken> {
        private boolean mAccountLocked = false;
        @Override
        protected Trakt.accessToken doInBackground(String... params) {
            String code = params[0];
            try {
                return Trakt.getAccessToken(code);
            } catch (Trakt.AccountLockedError e) {
                mAccountLocked = true;
                return null;
            }
        }

        @Override
        protected void onPostExecute(Trakt.accessToken result) {
            if (result != null) {
                if (log.isDebugEnabled()) log.debug("ExchangeAuthCodeTask: access token received");
                onAuthenticationSuccess(result);
            } else {
                log.warn("ExchangeAuthCodeTask: token exchange failed");
                if (mAccountLocked) {
                    Toast.makeText(TraktDeviceAuthActivity.this, R.string.trakt_account_locked, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(TraktDeviceAuthActivity.this, R.string.trakt_device_auth_error, Toast.LENGTH_LONG).show();
                }
                finish();
            }
        }
    }
}
