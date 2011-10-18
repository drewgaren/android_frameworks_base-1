/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.policy.impl;

import com.android.internal.R;
import com.android.internal.telephony.IccCard;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.RotarySelector;
import com.android.internal.widget.SlidingTab;
import com.android.internal.widget.RingSelector;
import com.android.internal.widget.RingSelector.OnRingTriggerListener;
import com.android.internal.widget.DigitalClock;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.ColorStateList;
import android.media.AudioManager;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.os.SystemProperties;
import android.provider.Settings;
import java.util.Date;
import java.io.File;
import java.net.URISyntaxException;

/**
 * The screen within {@link LockPatternKeyguardView} that shows general
 * information about the device depending on its state, and how to get
 * past it, as applicable.
 */
class LockScreen extends LinearLayout implements KeyguardScreen, KeyguardUpdateMonitor.InfoCallback,
        KeyguardUpdateMonitor.SimStateCallback, SlidingTab.OnTriggerListener,
        RotarySelector.OnDialTriggerListener, RingSelector.OnRingTriggerListener {

    private static final boolean DBG = false;
    private static final String TAG = "LockScreen";
    private static final String ENABLE_MENU_KEY_FILE = "/data/local/enable_menu_key";

    private Status mStatus = Status.Normal;

    private final LockPatternUtils mLockPatternUtils;
    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final KeyguardScreenCallback mCallback;

    private TextView mCarrier;
    private SlidingTab mTabSelector;
    private RotarySelector mRotarySelector;
    private RingSelector mRingSelector;
    private DigitalClock mClock;
    private TextView mDate;
    private TextView mStatus1;
    private TextView mStatus2;
    private TextView mScreenLocked;
    private TextView mEmergencyCallText;
    private Button mEmergencyCallButton;
    private String mCarrierCap;
    private ImageButton mPlayIcon;
    private ImageButton mPauseIcon;
    private ImageButton mRewindIcon;
    private ImageButton mForwardIcon;
    private AudioManager am = (AudioManager)getContext().getSystemService(Context.AUDIO_SERVICE);
    private boolean wasActive = am.isMusicActive();
    // current configuration state of keyboard and display
    private int mKeyboardHidden;
    private int mCreationOrientation;

    // are we showing battery information?
    private boolean mShowingBatteryInfo = false;

    // last known plugged in state
    private boolean mPluggedIn = false;

    // last known battery level
    private int mBatteryLevel = 100;

    private String mNextAlarm = null;
    private Drawable mAlarmIcon = null;
    private String mCharging = null;
    private Drawable mChargingIcon = null;

    private boolean mSilentMode;
    private AudioManager mAudioManager;
    private String mDateFormatString;
    private java.text.DateFormat mTimeFormat;
    private boolean mEnableMenuKeyInLockScreen;
    
    private static final int LOCK_STYLE_TABS = 1;
    private static final int LOCK_STYLE_ROTARY = 2;
    private static final int LOCK_STYLE_RING = 3;
    
    private String[] mCustomRingAppActivities = new String[] {
            Settings.System.getString(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_CUSTOM_RING_APP_ACTIVITIES[0]),
            Settings.System.getString(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_CUSTOM_RING_APP_ACTIVITIES[1]),
            Settings.System.getString(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_CUSTOM_RING_APP_ACTIVITIES[2]),
            Settings.System.getString(mContext.getContentResolver(),
                    Settings.System.LOCKSCREEN_CUSTOM_RING_APP_ACTIVITIES[3])
    };
    
    private int mLockscreenStyle = (Settings.System.getInt(mContext.getContentResolver(),
    		Settings.System.LOCKSCREEN_STYLE, LOCK_STYLE_ROTARY));
    
    private int mLockscreenRingLocation = (Settings.System.getInt(mContext.getContentResolver(),
    		Settings.System.LOCKSCREEN_RING_LOCATION, 0));
    
    private boolean mUseTabsLockscreen = (mLockscreenStyle == LOCK_STYLE_TABS);
    
    private boolean mUseRotaryLockscreen = (mLockscreenStyle == LOCK_STYLE_ROTARY);

    private boolean mUseRingLockscreen = (mLockscreenStyle == LOCK_STYLE_RING);
    
    private Bitmap[] mCustomRingAppIcons = new Bitmap[4];
    
    private boolean mTrackballUnlockScreen = (Settings.System.getInt(mContext.getContentResolver(),
            Settings.System.TRACKBALL_UNLOCK_SCREEN, 0) == 1);
    
    /**
     * The status of this lock screen.
     */
    enum Status {
        /**
         * Normal case (sim card present, it's not locked)
         */
        Normal(true),

        /**
         * The sim card is 'network locked'.
         */
        NetworkLocked(true),

        /**
         * The sim card is missing.
         */
        SimMissing(false),

        /**
         * The sim card is missing, and this is the device isn't provisioned, so we don't let
         * them get past the screen.
         */
        SimMissingLocked(false),

        /**
         * The sim card is PUK locked, meaning they've entered the wrong sim unlock code too many
         * times.
         */
        SimPukLocked(false),

        /**
         * The sim card is locked.
         */
        SimLocked(true);

        private final boolean mShowStatusLines;

        Status(boolean mShowStatusLines) {
            this.mShowStatusLines = mShowStatusLines;
        }

        /**
         * @return Whether the status lines (battery level and / or next alarm) are shown while
         *         in this state.  Mostly dictated by whether this is room for them.
         */
        public boolean showStatusLines() {
            return mShowStatusLines;
        }
    }

    /**
     * In general, we enable unlocking the insecure key guard with the menu key. However, there are
     * some cases where we wish to disable it, notably when the menu button placement or technology
     * is prone to false positives.
     *
     * @return true if the menu key should be enabled
     */
    private boolean shouldEnableMenuKey() {
        final Resources res = getResources();
        final boolean configDisabled = res.getBoolean(R.bool.config_disableMenuKeyInLockScreen);
        final boolean isMonkey = SystemProperties.getBoolean("ro.monkey", false);
        final boolean fileOverride = (new File(ENABLE_MENU_KEY_FILE)).exists();
        return !configDisabled || isMonkey || fileOverride;
    }

    /**
     * @param context Used to setup the view.
     * @param configuration The current configuration. Used to use when selecting layout, etc.
     * @param lockPatternUtils Used to know the state of the lock pattern settings.
     * @param updateMonitor Used to register for updates on various keyguard related
     *    state, and query the initial state at setup.
     * @param callback Used to communicate back to the host keyguard view.
     */
    LockScreen(Context context, Configuration configuration, LockPatternUtils lockPatternUtils,
            KeyguardUpdateMonitor updateMonitor,
            KeyguardScreenCallback callback) {
        super(context);
        mLockPatternUtils = lockPatternUtils;
        mUpdateMonitor = updateMonitor;
        mCallback = callback;

        mEnableMenuKeyInLockScreen = shouldEnableMenuKey();

        mCreationOrientation = configuration.orientation;

        mKeyboardHidden = configuration.hardKeyboardHidden;
        
        if (LockPatternKeyguardView.DEBUG_CONFIGURATION) {
            Log.v(TAG, "***** CREATING LOCK SCREEN", new RuntimeException());
            Log.v(TAG, "Cur orient=" + mCreationOrientation
                    + " res orient=" + context.getResources().getConfiguration().orientation);
        }

        final LayoutInflater inflater = LayoutInflater.from(context);
        if (DBG) Log.v(TAG, "Creation orientation = " + mCreationOrientation);
        if (mCreationOrientation != Configuration.ORIENTATION_LANDSCAPE) {
            inflater.inflate(R.layout.keyguard_screen_tab_unlock, this, true);
        } else {
            inflater.inflate(R.layout.keyguard_screen_tab_unlock_land, this, true);
        }

        mCarrier = (TextView) findViewById(R.id.carrier);
        mCarrier.setSelected(true);
        mCarrier.setTextColor(0xffffffff);

        mClock = (DigitalClock) findViewById(R.id.time);
        mDate = (TextView) findViewById(R.id.date);
        mStatus1 = (TextView) findViewById(R.id.status1);
        mStatus2 = (TextView) findViewById(R.id.status2);

        mPlayIcon = (ImageButton) findViewById(R.id.musicControlPlay);
        mPauseIcon = (ImageButton) findViewById(R.id.musicControlPause); 
        mRewindIcon = (ImageButton) findViewById(R.id.musicControlPrevious); 
        mForwardIcon = (ImageButton) findViewById(R.id.musicControlNext); 
        if (wasActive) {
            if(am.isMusicActive()) {
                mPauseIcon.setVisibility(View.VISIBLE);
                mPlayIcon.setVisibility(View.GONE);
                mRewindIcon.setVisibility(View.VISIBLE);
                mForwardIcon.setVisibility(View.VISIBLE);
            } else {
                mPlayIcon.setVisibility(View.VISIBLE);
                mPauseIcon.setVisibility(View.GONE);
                mRewindIcon.setVisibility(View.GONE);
                mForwardIcon.setVisibility(View.GONE);
            }
        } else {
            mPlayIcon.setVisibility(View.GONE);
            mPauseIcon.setVisibility(View.GONE);
            mRewindIcon.setVisibility(View.GONE);
            mForwardIcon.setVisibility(View.GONE);
        }

        mScreenLocked = (TextView) findViewById(R.id.screenLocked);

        mRotarySelector = (RotarySelector) findViewById(R.id.rotary_selector);

        mRingSelector = (RingSelector) findViewById(R.id.ring_selector);
        
        float density = getResources().getDisplayMetrics().density;
        int ringAppIconSize = context.getResources().getInteger(R.integer.config_ringSecIconSizeDIP);
        for (int q = 0; q < 4; q++) {
            if (mCustomRingAppActivities[q] != null) {
                mRingSelector.showSecRing(q);
                try {
                    Intent i = Intent.parseUri(mCustomRingAppActivities[q], 0);
                    PackageManager pm = context.getPackageManager();
                    ActivityInfo ai = i.resolveActivityInfo(pm, PackageManager.GET_ACTIVITIES);
                    if (ai != null) {
                        Bitmap iconBmp = ((BitmapDrawable) ai.loadIcon(pm)).getBitmap();
                        mCustomRingAppIcons[q] = Bitmap.createScaledBitmap(iconBmp,
                                (int) (density * ringAppIconSize), (int) (density * ringAppIconSize), true);
                        mRingSelector.setSecRingResources(q, mCustomRingAppIcons[q], R.drawable.jog_ring_secback_normal);
                    }
                } catch (URISyntaxException e) {
                }
            } else {
                mRingSelector.hideSecRing(q);
            }
        }
        
        mRingSelector.setSecRingResources(4, R.drawable.ic_jog_dial_unlock,
        		R.drawable.jog_ring_toggle);
        mRingSelector.setSecRingResources(5, R.drawable.jog_tab_bar_right_sound_on,
        		R.drawable.jog_ring_toggle);
        
        //TODO set to true for now
        mRingSelector.enableMiddleRing(true);
        Log.d(TAG, " ringAlignment: " + mLockscreenRingLocation);
        mRingSelector.setRingAlignment(mLockscreenRingLocation);
        
        mTabSelector = (SlidingTab) findViewById(R.id.tab_selector);
        mTabSelector.setHoldAfterTrigger(true, false);
        mTabSelector.setLeftHintText(R.string.lockscreen_unlock_label);

        mEmergencyCallText = (TextView) findViewById(R.id.emergencyCallText);
        mEmergencyCallButton = (Button) findViewById(R.id.emergencyCallButton);
        mEmergencyCallButton.setText(R.string.lockscreen_emergency_call);

        mLockPatternUtils.updateEmergencyCallButtonState(mEmergencyCallButton);
        mEmergencyCallButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mCallback.takeEmergencyCallAction();
            }
        });

        mPlayIcon.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
            	mCallback.pokeWakelock();
                if(!am.isMusicActive() && wasActive) {
                    mPauseIcon.setVisibility(View.VISIBLE);
                    mPlayIcon.setVisibility(View.GONE);
                    mRewindIcon.setVisibility(View.VISIBLE);
                    mForwardIcon.setVisibility(View.VISIBLE);
                    Intent intent;
                    intent = new Intent("com.android.music.musicservicecommand.togglepause");
                    getContext().sendBroadcast(intent);
                }
            }
        });

        mPauseIcon.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
            	mCallback.pokeWakelock();
                if(am.isMusicActive()) {
                    mPlayIcon.setVisibility(View.VISIBLE);
                    mPauseIcon.setVisibility(View.GONE);
                    mRewindIcon.setVisibility(View.GONE);
                    mForwardIcon.setVisibility(View.GONE);
                    Intent intent;
                    intent = new Intent("com.android.music.musicservicecommand.togglepause");
                    getContext().sendBroadcast(intent);
                }
            }  
        });

        mRewindIcon.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
            	mCallback.pokeWakelock();
                Intent intent;
                intent = new Intent("com.android.music.musicservicecommand.previous");
                getContext().sendBroadcast(intent);
             }
        });

        mForwardIcon.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
            	mCallback.pokeWakelock();
                Intent intent;
                intent = new Intent("com.android.music.musicservicecommand.next");
                getContext().sendBroadcast(intent);
             }
        });


        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        updateMonitor.registerInfoCallback(this);
        updateMonitor.registerSimStateCallback(this);

        mAudioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        mSilentMode = isSilentMode();

        mRotarySelector.setLeftHandleResource(R.drawable.ic_jog_dial_unlock);

        mTabSelector.setLeftTabResources(
                R.drawable.ic_jog_dial_unlock,
                R.drawable.jog_tab_target_green,
                R.drawable.jog_tab_bar_left_unlock,
                R.drawable.jog_tab_left_unlock);

        mRingSelector.setMiddleRingResources(
                R.drawable.jog_tab_target_gray,
                R.drawable.jog_ring_big);
        
        updateRightTabResources();

        mRotarySelector.setOnDialTriggerListener(this);
        mTabSelector.setOnTriggerListener(this);
        mRingSelector.setOnRingTriggerListener(this);
        resetStatusInfo(updateMonitor);
    }

    private boolean isSilentMode() {
        return mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL;
    }

    private void updateRightTabResources() {
        boolean vibe = mSilentMode
            && (mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE);

        int iconId = mSilentMode ? (vibe ? R.drawable.ic_jog_dial_vibrate_on
                : R.drawable.ic_jog_dial_sound_off) : R.drawable.ic_jog_dial_sound_on;
        int targetId = mSilentMode ? R.drawable.jog_tab_target_yellow
                : R.drawable.jog_tab_target_gray;

        mRotarySelector.setRightHandleResource(iconId);

        mTabSelector.setRightTabResources(iconId, targetId,
                mSilentMode ? R.drawable.jog_tab_bar_right_sound_on
                            : R.drawable.jog_tab_bar_right_sound_off,
                mSilentMode ? R.drawable.jog_tab_right_sound_on
                            : R.drawable.jog_tab_right_sound_off);
        
        mRingSelector.setSecRingResources(5, iconId,
        		R.drawable.jog_ring_toggle);
        
    }

    private void resetStatusInfo(KeyguardUpdateMonitor updateMonitor) {
        mShowingBatteryInfo = updateMonitor.shouldShowBatteryInfo();
        mPluggedIn = updateMonitor.isDevicePluggedIn();
        mBatteryLevel = updateMonitor.getBatteryLevel();

        mStatus = getCurrentStatus(updateMonitor.getSimState());
        updateLayout(mStatus);

        refreshBatteryStringAndIcon();
        refreshAlarmDisplay();

        mTimeFormat = DateFormat.getTimeFormat(getContext());
        mDateFormatString = getContext().getString(R.string.full_wday_month_day_no_year);
        refreshTimeAndDateDisplay();
        updateStatusLines();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER && mTrackballUnlockScreen) 
        		|| (keyCode == KeyEvent.KEYCODE_MENU && mEnableMenuKeyInLockScreen)) {

            mCallback.goToUnlockScreen();
        }
        return false;
    }

    /** {@inheritDoc} */
    public void onTrigger(View v, int whichHandle) {
        if (whichHandle == SlidingTab.OnTriggerListener.LEFT_HANDLE) {
            mCallback.goToUnlockScreen();
        } else if (whichHandle == SlidingTab.OnTriggerListener.RIGHT_HANDLE) {
            // toggle silent mode
            toggleSilentMode();
            updateRightTabResources();

            String message = mSilentMode ?
                    getContext().getString(R.string.global_action_silent_mode_on_status) :
                    getContext().getString(R.string.global_action_silent_mode_off_status);

            final int toastIcon = mSilentMode
                ? R.drawable.ic_lock_ringer_off
                : R.drawable.ic_lock_ringer_on;

            final int toastColor = mSilentMode
                ? getContext().getResources().getColor(R.color.keyguard_text_color_soundoff)
                : getContext().getResources().getColor(R.color.keyguard_text_color_soundon);
            toastMessage(mScreenLocked, message, toastColor, toastIcon);
            mCallback.pokeWakelock();
        }
    }

    /** {@inheritDoc} */
    public void onDialTrigger(View v, int whichHandle) {
        if (whichHandle == RotarySelector.OnDialTriggerListener.LEFT_HANDLE) {
            mCallback.goToUnlockScreen();
        } else if (whichHandle == RotarySelector.OnDialTriggerListener.RIGHT_HANDLE) {
            // toggle silent mode
            toggleSilentMode();
            updateRightTabResources();

            String message = mSilentMode ? getContext().getString(
                    R.string.global_action_silent_mode_on_status) : getContext().getString(
                    R.string.global_action_silent_mode_off_status);

            final int toastIcon = mSilentMode ? R.drawable.ic_lock_ringer_off
                    : R.drawable.ic_lock_ringer_on;

            final int toastColor = mSilentMode ? getContext().getResources().getColor(
                    R.color.keyguard_text_color_soundoff) : getContext().getResources().getColor(
                    R.color.keyguard_text_color_soundon);
            toastMessage(mScreenLocked, message, toastColor, toastIcon);
            mCallback.pokeWakelock();
        }
    }

    /** {@inheritDoc} */
    public void onRingTrigger(View v, int whichRing, int whichApp) {
        if (whichRing == RingSelector.OnRingTriggerListener.MIDDLE_RING) {
        	if (whichApp >= 4) {
            	if (whichApp == 4) {
            		mCallback.goToUnlockScreen();
            	} else {
                    toggleSilentMode();
                    updateRightTabResources();
                    mCallback.pokeWakelock();
            	}
        	} else {
                if (mCustomRingAppActivities[whichApp] != null) {
                    runActivity(mCustomRingAppActivities[whichApp]);
                }
        	}

        }
    }
    
    /** {@inheritDoc} */
    public void onGrabbedStateChange(View v, int grabbedState) {
        if (grabbedState == SlidingTab.OnTriggerListener.RIGHT_HANDLE) {
            mSilentMode = isSilentMode();
            mTabSelector.setRightHintText(mSilentMode ? R.string.lockscreen_sound_on_label
                    : R.string.lockscreen_sound_off_label);
        }
        mCallback.pokeWakelock();
        
        if (mUseRingLockscreen) {
            if (grabbedState == OnRingTriggerListener.MIDDLE_RING) { // hide everything else when ring is pressed.
                mCarrier.setVisibility(View.GONE);
                mClock.setVisibility(View.GONE);
                mDate.setVisibility(View.GONE);
                mStatus1.setVisibility(View.GONE);
                mStatus2.setVisibility(View.GONE);
                mPlayIcon.setVisibility(View.GONE);
                mPauseIcon.setVisibility(View.GONE); 
                mRewindIcon.setVisibility(View.GONE); 
                mForwardIcon.setVisibility(View.GONE);
            } else if (grabbedState == OnRingTriggerListener.NO_RING) {
                mCarrier.setVisibility(View.VISIBLE);
                mClock.setVisibility(View.VISIBLE);
                mDate.setVisibility(View.VISIBLE);
                mStatus1.setVisibility(View.VISIBLE);
                mStatus2.setVisibility(View.VISIBLE);
                if(am.isMusicActive()) {
                    mPauseIcon.setVisibility(View.VISIBLE);
                    mPlayIcon.setVisibility(View.GONE);
                    mRewindIcon.setVisibility(View.VISIBLE);
                    mForwardIcon.setVisibility(View.VISIBLE);
                } else if (wasActive) {
                    mPlayIcon.setVisibility(View.VISIBLE);
                    mPauseIcon.setVisibility(View.GONE);
                    mRewindIcon.setVisibility(View.GONE);
                    mForwardIcon.setVisibility(View.GONE);
                }
            }
            mCallback.pokeWakelock();
        }
    }

    /**
     * Displays a message in a text view and then restores the previous text.
     * @param textView The text view.
     * @param text The text.
     * @param color The color to apply to the text, or 0 if the existing color should be used.
     * @param iconResourceId The left hand icon.
     */
    private void toastMessage(final TextView textView, final String text, final int color, final int iconResourceId) {
        if (mPendingR1 != null) {
            textView.removeCallbacks(mPendingR1);
            mPendingR1 = null;
        }
        if (mPendingR2 != null) {
            mPendingR2.run(); // fire immediately, restoring non-toasted appearance
            textView.removeCallbacks(mPendingR2);
            mPendingR2 = null;
        }

        final String oldText = textView.getText().toString();
        final ColorStateList oldColors = textView.getTextColors();

        mPendingR1 = new Runnable() {
            public void run() {
                textView.setText(text);
                if (color != 0) {
                    textView.setTextColor(color);
                }
                textView.setCompoundDrawablesWithIntrinsicBounds(iconResourceId, 0, 0, 0);
            }
        };

        textView.postDelayed(mPendingR1, 0);
        mPendingR2 = new Runnable() {
            public void run() {
                textView.setText(oldText);
                textView.setTextColor(oldColors);
                textView.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
            }
        };
        textView.postDelayed(mPendingR2, 3500);
    }
    private Runnable mPendingR1;
    private Runnable mPendingR2;

    private void refreshAlarmDisplay() {
        mNextAlarm = mLockPatternUtils.getNextAlarm();
        if (mNextAlarm != null) {
            mAlarmIcon = getContext().getResources().getDrawable(R.drawable.ic_lock_idle_alarm);
        }
        updateStatusLines();
    }

    /** {@inheritDoc} */
    public void onRefreshBatteryInfo(boolean showBatteryInfo, boolean pluggedIn,
            int batteryLevel) {
        if (DBG) Log.d(TAG, "onRefreshBatteryInfo(" + showBatteryInfo + ", " + pluggedIn + ")");
        mShowingBatteryInfo = showBatteryInfo;
        mPluggedIn = pluggedIn;
        mBatteryLevel = batteryLevel;

        refreshBatteryStringAndIcon();
        updateStatusLines();
    }

    private void refreshBatteryStringAndIcon() {
        if (!mShowingBatteryInfo) {
            mCharging = null;
            return;
        }

        if (mChargingIcon == null) {
            mChargingIcon =
                    getContext().getResources().getDrawable(R.drawable.ic_lock_idle_charging);
        }

        if (mPluggedIn) {
            if (mBatteryLevel >= 100) {
                mCharging = getContext().getString(R.string.lockscreen_charged);
            } else {
                mCharging = getContext().getString(R.string.lockscreen_plugged_in, mBatteryLevel);
            }
        } else {
            mCharging = getContext().getString(R.string.lockscreen_low_battery);
        }
    }

    /** {@inheritDoc} */
    public void onTimeChanged() {
        refreshTimeAndDateDisplay();
    }

    private void refreshTimeAndDateDisplay() {
        mRotarySelector.invalidate();
        mDate.setText(DateFormat.format(mDateFormatString, new Date()));
    }

    private void updateStatusLines() {
        if (!mStatus.showStatusLines()
                || (mCharging == null && mNextAlarm == null)) {
            mStatus1.setVisibility(View.INVISIBLE);
            mStatus2.setVisibility(View.INVISIBLE);
        } else if (mCharging != null && mNextAlarm == null) {
            // charging only
            mStatus1.setVisibility(View.VISIBLE);
            mStatus2.setVisibility(View.INVISIBLE);

            mStatus1.setText(mCharging);
            mStatus1.setCompoundDrawablesWithIntrinsicBounds(mChargingIcon, null, null, null);
        } else if (mNextAlarm != null && mCharging == null) {
            // next alarm only
            mStatus1.setVisibility(View.VISIBLE);
            mStatus2.setVisibility(View.INVISIBLE);

            mStatus1.setText(mNextAlarm);
            mStatus1.setCompoundDrawablesWithIntrinsicBounds(mAlarmIcon, null, null, null);
        } else if (mCharging != null && mNextAlarm != null) {
            // both charging and next alarm
            mStatus1.setVisibility(View.VISIBLE);
            mStatus2.setVisibility(View.VISIBLE);

            mStatus1.setText(mCharging);
            mStatus1.setCompoundDrawablesWithIntrinsicBounds(mChargingIcon, null, null, null);
            mStatus2.setText(mNextAlarm);
            mStatus2.setCompoundDrawablesWithIntrinsicBounds(mAlarmIcon, null, null, null);
        }
    }

    /** {@inheritDoc} */
    public void onRefreshCarrierInfo(CharSequence plmn, CharSequence spn) {
        if (DBG) Log.d(TAG, "onRefreshCarrierInfo(" + plmn + ", " + spn + ")");
        updateLayout(mStatus);
    }

    /**
     * Determine the current status of the lock screen given the sim state and other stuff.
     */
    private Status getCurrentStatus(IccCard.State simState) {
        boolean missingAndNotProvisioned = (!mUpdateMonitor.isDeviceProvisioned()
                && simState == IccCard.State.ABSENT);
        if (missingAndNotProvisioned) {
            return Status.SimMissingLocked;
        }

        switch (simState) {
            case ABSENT:
                return Status.SimMissing;
            case NETWORK_LOCKED:
                return Status.SimMissingLocked;
            case NOT_READY:
                return Status.SimMissing;
            case PIN_REQUIRED:
                return Status.SimLocked;
            case PUK_REQUIRED:
                return Status.SimPukLocked;
            case READY:
                return Status.Normal;
            case UNKNOWN:
                return Status.SimMissing;
        }
        return Status.SimMissing;
    }

    /**
     * Update the layout to match the current status.
     */
    private void updateLayout(Status status) {
        // The emergency call button no longer appears on this screen.
        if (DBG) Log.d(TAG, "updateLayout: status=" + status);

        mEmergencyCallButton.setVisibility(View.GONE); // in almost all cases

        switch (status) {
            case Normal:
            	
            	mCarrierCap = Settings.System.getString(getContext().getContentResolver(), Settings.System.CARRIER_CAP);
            	
            	if (mCarrierCap != null){
            		
            		mCarrier.setText(mCarrierCap);

            	} else {
          
            		mCarrierCap = getContext().getString(R.string.lockscreen_carrier_default);
            		mCarrier.setText(mCarrierCap);
            	
            	}
            	
                // Empty now, but used for sliding tab feedback
                mScreenLocked.setText("");

                // layout
                setUnlockWidgetsState(true);
                mScreenLocked.setVisibility(View.VISIBLE);
                mEmergencyCallText.setVisibility(View.GONE);
                break;
            case NetworkLocked:
                // The carrier string shows both sim card status (i.e. No Sim Card) and
                // carrier's name and/or "Emergency Calls Only" status
                mCarrier.setText(
                        getCarrierString(
                                mUpdateMonitor.getTelephonyPlmn(),
                                getContext().getText(R.string.lockscreen_network_locked_message)));
                mScreenLocked.setText(R.string.lockscreen_instructions_when_pattern_disabled);

                // layout
                setUnlockWidgetsState(true);
                mScreenLocked.setVisibility(View.VISIBLE);
                mEmergencyCallText.setVisibility(View.GONE);
                break;
            case SimMissing:
                // text
                mCarrier.setText(R.string.lockscreen_missing_sim_message_short);
                mScreenLocked.setText(R.string.lockscreen_missing_sim_instructions);

                // layout
                setUnlockWidgetsState(true);
                mScreenLocked.setVisibility(View.VISIBLE);
                mEmergencyCallText.setVisibility(View.VISIBLE);
                // do not need to show the e-call button; user may unlock
                break;
            case SimMissingLocked:
                // text
                mCarrier.setText(
                        getCarrierString(
                                mUpdateMonitor.getTelephonyPlmn(),
                                getContext().getText(R.string.lockscreen_missing_sim_message_short)));
                mScreenLocked.setText(R.string.lockscreen_missing_sim_instructions);

                // layout
                setUnlockWidgetsState(true);
                mScreenLocked.setVisibility(View.VISIBLE);
                mEmergencyCallText.setVisibility(View.VISIBLE);
                mEmergencyCallButton.setVisibility(View.VISIBLE);
                break;
            case SimLocked:
                // text
                mCarrier.setText(
                        getCarrierString(
                                mUpdateMonitor.getTelephonyPlmn(),
                                getContext().getText(R.string.lockscreen_sim_locked_message)));

                // layout
                setUnlockWidgetsState(true);
                mScreenLocked.setVisibility(View.INVISIBLE);
                mEmergencyCallText.setVisibility(View.GONE);
                break;
            case SimPukLocked:
                // text
                mCarrier.setText(
                        getCarrierString(
                                mUpdateMonitor.getTelephonyPlmn(),
                                getContext().getText(R.string.lockscreen_sim_puk_locked_message)));
                mScreenLocked.setText(R.string.lockscreen_sim_puk_locked_instructions);

                // layout
                setUnlockWidgetsState(true);
                mScreenLocked.setVisibility(View.VISIBLE);
                mEmergencyCallText.setVisibility(View.VISIBLE);
                mEmergencyCallButton.setVisibility(View.VISIBLE);
                break;
        }
    }

    private void setUnlockWidgetsState(boolean show) {
        if (show) {
            if (mUseRotaryLockscreen) {
                mRotarySelector.setVisibility(View.VISIBLE);
                mTabSelector.setVisibility(View.GONE);
                mRingSelector.setVisibility(View.GONE);
            } else if (mUseRingLockscreen) {
                mRingSelector.setVisibility(View.VISIBLE);
                mRotarySelector.setVisibility(View.GONE);
                mTabSelector.setVisibility(View.GONE);
            } else {
                mRotarySelector.setVisibility(View.GONE);
                mRingSelector.setVisibility(View.GONE);
                mTabSelector.setVisibility(View.VISIBLE);
            }
        } else {
            mRotarySelector.setVisibility(View.GONE);
            mRingSelector.setVisibility(View.GONE);
            mTabSelector.setVisibility(View.GONE);
        }
    }
    
    static CharSequence getCarrierString(CharSequence telephonyPlmn, CharSequence telephonySpn) {
        if (telephonyPlmn != null && telephonySpn == null) {
            return telephonyPlmn;
        } else if (telephonyPlmn != null && telephonySpn != null) {
            return telephonyPlmn + "|" + telephonySpn;
        } else if (telephonyPlmn == null && telephonySpn != null) {
            return telephonySpn;
        } else {
            return "";
        }
    }

    public void onSimStateChanged(IccCard.State simState) {
        if (DBG) Log.d(TAG, "onSimStateChanged(" + simState + ")");
        mStatus = getCurrentStatus(simState);
        updateLayout(mStatus);
        updateStatusLines();
    }

    void updateConfiguration() {
        Configuration newConfig = getResources().getConfiguration();
        if (newConfig.orientation != mCreationOrientation) {
            mCallback.recreateMe(newConfig);
        } else if (newConfig.hardKeyboardHidden != mKeyboardHidden) {
            mKeyboardHidden = newConfig.hardKeyboardHidden;
            final boolean isKeyboardOpen = mKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
            if (mUpdateMonitor.isKeyguardBypassEnabled() && isKeyboardOpen) {
                mCallback.goToUnlockScreen();
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (LockPatternKeyguardView.DEBUG_CONFIGURATION) {
            Log.v(TAG, "***** LOCK ATTACHED TO WINDOW");
            Log.v(TAG, "Cur orient=" + mCreationOrientation
                    + ", new config=" + getResources().getConfiguration());
        }
        updateConfiguration();
    }

    /** {@inheritDoc} */
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (LockPatternKeyguardView.DEBUG_CONFIGURATION) {
            Log.w(TAG, "***** LOCK CONFIG CHANGING", new RuntimeException());
            Log.v(TAG, "Cur orient=" + mCreationOrientation
                    + ", new config=" + newConfig);
        }
        updateConfiguration();
    }

    /** {@inheritDoc} */
    public boolean needsInput() {
        return false;
    }

    /** {@inheritDoc} */
    public void onPause() {

    }

    /** {@inheritDoc} */
    public void onResume() {
        resetStatusInfo(mUpdateMonitor);
        mLockPatternUtils.updateEmergencyCallButtonState(mEmergencyCallButton);
    }

    /** {@inheritDoc} */
    public void cleanUp() {
        mUpdateMonitor.removeCallback(this); // this must be first
    }

    /** {@inheritDoc} */
    public void onRingerModeChanged(int state) {
        boolean silent = AudioManager.RINGER_MODE_NORMAL != state;
        if (silent != mSilentMode) {
            mSilentMode = silent;
            updateRightTabResources();
        }
    }

    public void onPhoneStateChanged(String newState) {
        mLockPatternUtils.updateEmergencyCallButtonState(mEmergencyCallButton);
    }

    private void toggleSilentMode() {
            // tri state silent<->vibrate<->ring if silent mode is enabled, otherwise toggle silent mode
            final boolean mVolumeControlSilent = true;
            mSilentMode = mVolumeControlSilent
                ? ((mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE) || !mSilentMode)
                : !mSilentMode;
            if (mSilentMode) {
                final boolean vibe = mVolumeControlSilent
                ? (mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_VIBRATE)
                : (Settings.System.getInt(
                    getContext().getContentResolver(),
                    Settings.System.VIBRATE_IN_SILENT, 1) == 1);

                mAudioManager.setRingerMode(vibe
                    ? AudioManager.RINGER_MODE_VIBRATE
                    : AudioManager.RINGER_MODE_SILENT);
            } else {
                mAudioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            }
    }
    
    private void runActivity(String uri) {
        try {
            Intent i = Intent.parseUri(uri, 0);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            mCallback.goToUnlockScreen();
            mContext.startActivity(i);

        } catch (URISyntaxException e) {
        } catch (ActivityNotFoundException e) {
        }
    }
    
}
