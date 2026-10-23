/*
 * Copyright (C) 2026 The Lunaris Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.lunaris.unifiedstatus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.Set;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;

/** Native SystemUI rendering of the Unified Status battery and system indicator. */
public class UnifiedStatusIndicatorView extends View implements DarkIconDispatcher.DarkReceiver {
    public static final String SETTING = "unified_status_indicator";
    private static final String WIFI_STANDARD_SETTING = "wifi_standard_icon";
    private static final float RING_RADIUS_UNITS = 17.16f;
    private static final float BATTERY_LEFT_START = 148.7f;
    private static final float BATTERY_LEFT_SWEEP = 101.3f;
    private static final float BATTERY_RIGHT_START = 290f;
    private static final float BATTERY_RIGHT_SWEEP = 109.1f;
    private static final float[][] NETWORK_DOT_POSITIONS = {
            {-9f, 14.23f}, {-3.17f, 16.57f}, {2.83f, 16.73f}, {8.67f, 14.77f}
    };
    private static final Set<UnifiedStatusIndicatorView> INSTANCES =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static Drawable sActiveModeIcon;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRing = new RectF();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final WifiManager mWifiManager;
    private final TelephonyManager mTelephonyManager;
    private TelephonyManager mDataTelephonyManager;
    private final SubscriptionManager mSubscriptionManager;
    private final ConnectivityManager mConnectivityManager;
    private int mBatteryLevel = 50;
    private int mCellLevel = 0;
    private int mWifiLevel = 0;
    private int mWifiStandard = -1;
    private int mNetworkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
    private boolean mWifiConnected;
    private boolean mWifiOn;
    private boolean mWifiStandardEnabled;
    private boolean mCellAvailable;
    private boolean mAirplaneMode;
    private boolean mMobileDataEnabled = true;
    private boolean mNrConnected;
    private boolean mCharging;
    private boolean mDnd;
    private boolean mShowDnd = true;
    private boolean mShowWifi = true;
    private boolean mShowMobile = true;
    private boolean mShowCellSignal = true;
    private boolean mShowAirplane = true;
    private boolean mListening;
    private boolean mPresentationAllowed = true;
    private View mSiblingBatteryView;
    private int mSiblingBatteryVisibility = VISIBLE;
    private boolean mSiblingBatteryVisibilitySaved;
    private final Map<View, Integer> mComposeBatteryVisibilities = new IdentityHashMap<>();
    private boolean mUnifiedStatusEnabled;
    private ViewTreeObserver mSiblingTreeObserver;
    private final ViewTreeObserver.OnGlobalLayoutListener mSiblingLayoutListener =
            () -> updateUnifiedStatusBatteryVisibility(mUnifiedStatusEnabled);
    private int mIconTint = Color.WHITE;
    private Drawable mActiveModeIcon;

    private final ContentObserver mSettingObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            updateEnabled();
        }
    };

    private final ContentObserver mAirplaneObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            updateAirplaneState();
        }
    };

    private final ContentObserver mWifiStandardObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            updateWifiStandardSetting();
        }
    };

    private final ContentObserver mIconHideListObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            updateIconVisibilitySettings();
        }
    };

    private final BroadcastReceiver mStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                mBatteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, mBatteryLevel);
                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);
                mCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL;
            } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action) ||
                    WifiManager.RSSI_CHANGED_ACTION.equals(action) ||
                    WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action) ||
                    ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                updateNetworkState();
            } else if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(action)) {
                updateAirplaneState();
            } else if (NotificationFilterChanged.ACTION.equals(action)) {
                updateDndState();
            }
            invalidate();
        }
    };

    private final PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            mCellLevel = signalStrength == null ? 0 : signalStrength.getLevel();
            invalidate();
        }

        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            mCellAvailable = serviceState != null &&
                    serviceState.getState() == ServiceState.STATE_IN_SERVICE;
            mNrConnected = serviceState != null &&
                    serviceState.getNrState() == NetworkRegistrationInfo.NR_STATE_CONNECTED;
            updateNetworkType();
        }

        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            updateNetworkType();
        }

    };

    private final SubscriptionManager.OnSubscriptionsChangedListener mSubscriptionsChangedListener =
            new SubscriptionManager.OnSubscriptionsChangedListener() {
                @Override
                public void onSubscriptionsChanged() {
                    registerDataTelephonyListener();
                    updateNetworkType();
                }
            };

    private final ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    updateNetworkState();
                }

                @Override
                public void onLost(Network network) {
                    updateNetworkState();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    updateNetworkState();
                }
            };

    public UnifiedStatusIndicatorView(Context context) {
        this(context, null);
    }

    public UnifiedStatusIndicatorView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public UnifiedStatusIndicatorView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mWifiManager = context.getSystemService(WifiManager.class);
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mDataTelephonyManager = mTelephonyManager;
        mSubscriptionManager = context.getSystemService(SubscriptionManager.class);
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(context.getString(R.string.unified_status_indicator_content_description));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        synchronized (INSTANCES) {
            INSTANCES.add(this);
            mActiveModeIcon = copyDrawable(sActiveModeIcon);
        }
        mSiblingTreeObserver = getViewTreeObserver();
        mSiblingTreeObserver.addOnGlobalLayoutListener(mSiblingLayoutListener);
        getContext().getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(SETTING), false, mSettingObserver,
                android.os.UserHandle.USER_ALL);
        getContext().getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), false,
                mAirplaneObserver);
        getContext().getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(WIFI_STANDARD_SETTING), false,
                mWifiStandardObserver, android.os.UserHandle.USER_ALL);
        getContext().getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(StatusBarIconController.ICON_HIDE_LIST), false,
                mIconHideListObserver, android.os.UserHandle.USER_ALL);
        updateIconVisibilitySettings();
        updateWifiStandardSetting();
        updateAirplaneState();
        updateEnabled();
    }

    @Override
    protected void onDetachedFromWindow() {
        synchronized (INSTANCES) {
            INSTANCES.remove(this);
        }
        getContext().getContentResolver().unregisterContentObserver(mSettingObserver);
        getContext().getContentResolver().unregisterContentObserver(mAirplaneObserver);
        getContext().getContentResolver().unregisterContentObserver(mWifiStandardObserver);
        getContext().getContentResolver().unregisterContentObserver(mIconHideListObserver);
        if (mSiblingTreeObserver != null && mSiblingTreeObserver.isAlive()) {
            mSiblingTreeObserver.removeOnGlobalLayoutListener(mSiblingLayoutListener);
        }
        mSiblingTreeObserver = null;
        stopListening();
        super.onDetachedFromWindow();
    }

    public void setPresentationAllowed(boolean allowed) {
        mPresentationAllowed = allowed;
        updateEnabled();
    }

    public void setIconTint(int tint) {
        mIconTint = tint;
        invalidate();
    }

    /** Shares the same active Modes icon that PhoneStatusBarPolicy publishes in the zen slot. */
    public static void setActiveModeIcon(Drawable icon) {
        synchronized (INSTANCES) {
            sActiveModeIcon = icon;
            for (UnifiedStatusIndicatorView view : INSTANCES) {
                view.mActiveModeIcon = copyDrawable(icon);
                view.invalidate();
            }
        }
    }

    private static Drawable copyDrawable(Drawable drawable) {
        if (drawable == null) return null;
        Drawable.ConstantState state = drawable.getConstantState();
        return state == null ? drawable : state.newDrawable().mutate();
    }

    private void updateEnabled() {
        boolean enabled = mPresentationAllowed &&
                Settings.Secure.getIntForUser(getContext().getContentResolver(),
                        SETTING, 0, android.os.UserHandle.USER_CURRENT) != 0;
        mUnifiedStatusEnabled = enabled;
        setVisibility(enabled ? VISIBLE : GONE);
        updateUnifiedStatusBatteryVisibility(enabled);
        if (enabled) {
            startListening();
            updateNetworkState();
            updateNetworkType();
            updateDndState();
        } else {
            stopListening();
        }
        invalidate();
    }

    private void updateUnifiedStatusBatteryVisibility(boolean unifiedStatusEnabled) {
        if (!(getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) getParent();
        View battery = parent.findViewById(R.id.battery);
        if (battery != null && battery != this && unifiedStatusEnabled) {
            if (!mSiblingBatteryVisibilitySaved) {
                mSiblingBatteryView = battery;
                mSiblingBatteryVisibility = battery.getVisibility();
                mSiblingBatteryVisibilitySaved = true;
            }
            battery.setVisibility(GONE);
        } else if (!unifiedStatusEnabled && mSiblingBatteryVisibilitySaved &&
                mSiblingBatteryView == battery) {
            battery.setVisibility(mSiblingBatteryVisibility);
            mSiblingBatteryView = null;
            mSiblingBatteryVisibilitySaved = false;
        }

        // The newer status bar and keyguard layouts add their battery as a ComposeView after
        // inflation. The old BatteryMeterView-only lookup above misses it, which leaves the
        // stock circular battery beside Unified Status. Observe global layout so dynamically added views
        // are hidden as soon as they enter this icon tree.
        View composeBattery = parent.findViewById(R.id.unified_status_battery_composable_view);
        if (composeBattery != null && composeBattery != this) {
            if (unifiedStatusEnabled) {
                if (composeBattery.getVisibility() != GONE) {
                    mComposeBatteryVisibilities.putIfAbsent(
                            composeBattery, composeBattery.getVisibility());
                }
                composeBattery.setVisibility(GONE);
            } else {
                Integer originalVisibility = mComposeBatteryVisibilities.remove(composeBattery);
                if (originalVisibility != null) {
                    composeBattery.setVisibility(originalVisibility);
                }
            }
        }
        if (!unifiedStatusEnabled && !mComposeBatteryVisibilities.isEmpty()) {
            for (Map.Entry<View, Integer> entry : mComposeBatteryVisibilities.entrySet()) {
                entry.getKey().setVisibility(entry.getValue());
            }
            mComposeBatteryVisibilities.clear();
        }
    }

    @Override
    public void onDarkChanged(java.util.ArrayList<Rect> areas, float darkIntensity, int tint) {
        mIconTint = DarkIconDispatcher.isInAreas(areas, this) ? tint : Color.WHITE;
        invalidate();
    }

    private void startListening() {
        if (mListening) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(NotificationFilterChanged.ACTION);
        getContext().registerReceiver(mStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        try {
            mConnectivityManager.registerNetworkCallback(
                    new NetworkRequest.Builder().addCapability(
                            NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), mNetworkCallback);
        } catch (RuntimeException ignored) { }
        mSubscriptionManager.addOnSubscriptionsChangedListener(
                getContext().getMainExecutor(), mSubscriptionsChangedListener);
        registerDataTelephonyListener();
        mListening = true;
    }

    private void registerDataTelephonyListener() {
        try {
            mDataTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
        } catch (SecurityException ignored) { }
        int dataSubId = SubscriptionManager.getDefaultDataSubscriptionId();
        mDataTelephonyManager = SubscriptionManager.isValidSubscriptionId(dataSubId)
                ? mTelephonyManager.createForSubscriptionId(dataSubId) : mTelephonyManager;
        try {
            mDataTelephonyManager.listen(mPhoneStateListener,
                    PhoneStateListener.LISTEN_SIGNAL_STRENGTHS |
                            PhoneStateListener.LISTEN_SERVICE_STATE |
                            PhoneStateListener.LISTEN_DATA_CONNECTION_STATE);
        } catch (SecurityException ignored) { }
    }

    private void stopListening() {
        if (!mListening) return;
        try { getContext().unregisterReceiver(mStateReceiver); } catch (IllegalArgumentException ignored) { }
        try { mConnectivityManager.unregisterNetworkCallback(mNetworkCallback); } catch (RuntimeException ignored) { }
        try { mDataTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE); } catch (SecurityException ignored) { }
        try { mSubscriptionManager.removeOnSubscriptionsChangedListener(
                mSubscriptionsChangedListener); } catch (RuntimeException ignored) { }
        mListening = false;
    }

    private void updateNetworkState() {
        try {
            mWifiOn = mWifiManager.isWifiEnabled();
            android.net.NetworkInfo wifi = mConnectivityManager.getNetworkInfo(
                    android.net.ConnectivityManager.TYPE_WIFI);
            WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
            int rssi = wifiInfo.getRssi();
            mWifiLevel = WifiManager.calculateSignalLevel(rssi, 4) + 1;
            Network activeNetwork = mConnectivityManager.getActiveNetwork();
            NetworkCapabilities activeCapabilities = activeNetwork == null ? null :
                    mConnectivityManager.getNetworkCapabilities(activeNetwork);
            mWifiConnected = wifi != null && wifi.isConnected() && mWifiLevel > 0 &&
                    activeCapabilities != null && activeCapabilities.hasTransport(
                            NetworkCapabilities.TRANSPORT_WIFI) &&
                    activeCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            mWifiStandard = mWifiConnected && mWifiStandardEnabled
                    ? wifiInfo.getWifiStandard() : -1;
        } catch (RuntimeException ignored) {
            mWifiOn = false;
            mWifiConnected = false;
            mWifiLevel = 0;
            mWifiStandard = -1;
        }
        invalidate();
    }

    private void updateWifiStandardSetting() {
        mWifiStandardEnabled = Settings.Secure.getIntForUser(
                getContext().getContentResolver(), WIFI_STANDARD_SETTING, 0,
                android.os.UserHandle.USER_CURRENT) != 0;
        updateNetworkState();
    }

    private void updateIconVisibilitySettings() {
        String hidden = Settings.Secure.getStringForUser(getContext().getContentResolver(),
                StatusBarIconController.ICON_HIDE_LIST, android.os.UserHandle.USER_CURRENT);
        java.util.Set<String> hiddenIcons = StatusBarIconController.getIconHideList(
                getContext(), hidden);
        mShowDnd = !hiddenIcons.contains("zen");
        mShowWifi = !hiddenIcons.contains("wifi");
        mShowMobile = !hiddenIcons.contains("mobile");
        mShowCellSignal = !hiddenIcons.contains("call_strength");
        mShowAirplane = !hiddenIcons.contains("airplane");
        invalidate();
    }

    private void updateAirplaneState() {
        try {
            mAirplaneMode = Settings.Global.getInt(getContext().getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        } catch (RuntimeException ignored) {
            mAirplaneMode = false;
        }
        invalidate();
    }

    private void updateNetworkType() {
        try {
            ServiceState serviceState = mDataTelephonyManager.getServiceState();
            mCellAvailable = serviceState != null &&
                    serviceState.getState() == ServiceState.STATE_IN_SERVICE;
            if (serviceState != null) {
                mNrConnected = serviceState.getNrState()
                        == NetworkRegistrationInfo.NR_STATE_CONNECTED;
            }
            mNetworkType = mDataTelephonyManager.getDataNetworkType();
            if (mNetworkType == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                mNetworkType = mDataTelephonyManager.getVoiceNetworkType();
            }
            mMobileDataEnabled = mDataTelephonyManager.isDataEnabled();
        } catch (SecurityException ignored) {
            mNetworkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
        }
        invalidate();
    }

    private void updateDndState() {
        try {
            android.app.NotificationManager nm = getContext().getSystemService(
                    android.app.NotificationManager.class);
            mDnd = nm.getCurrentInterruptionFilter() !=
                    android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (RuntimeException ignored) { mDnd = false; }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(getWidth() * 0.36f, getHeight() * 0.36f);
        float scale = radius / RING_RADIUS_UNITS;
        float stroke = Math.max(1.5f * density, radius * (2.65f / RING_RADIUS_UNITS));
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(stroke);
        // Flat caps avoid the bright round bead where the ring opens for the charging bolt.
        mPaint.setStrokeCap(Paint.Cap.BUTT);
        mRing.set(cx - radius, cy - radius, cx + radius, cy + radius);
        mPaint.setColor((mIconTint & 0x00ffffff) | 0x66000000);
        // Keep the lower opening for the cellular dots.
        canvas.drawArc(mRing, BATTERY_LEFT_START, BATTERY_LEFT_SWEEP, false, mPaint);
        canvas.drawArc(mRing, BATTERY_RIGHT_START, BATTERY_RIGHT_SWEEP, false, mPaint);
        int batteryColor = mCharging ? 0xff72d78c
                : (mBatteryLevel <= 15 ? 0xffff6b6b : mIconTint);
        mPaint.setColor(batteryColor);
        float leftSweep = BATTERY_LEFT_SWEEP * Math.min(mBatteryLevel, 50) / 50f;
        if (leftSweep > 0) canvas.drawArc(mRing, BATTERY_LEFT_START, leftSweep, false, mPaint);
        float rightSweep = BATTERY_RIGHT_SWEEP * Math.max(0, mBatteryLevel - 50) / 50f;
        if (rightSweep > 0) canvas.drawArc(mRing, BATTERY_RIGHT_START, rightSweep, false, mPaint);

        if (mCharging) {
            drawChargingBolt(canvas, cx, cy, scale);
        } else {
            drawBatteryLevel(canvas, cx, cy, scale, batteryColor);
        }

        drawCenter(canvas, cx, cy, radius);
        drawNetworkDots(canvas, cx, cy, radius);
    }

    private void drawBatteryLevel(Canvas canvas, float cx, float cy, float scale,
            int batteryColor) {
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(batteryColor);
        mPaint.setTextAlign(Paint.Align.CENTER);
        mPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        mPaint.setTextSize(8.2f * scale);
        // The approved preview places the battery level in the arc's upper opening,
        // where the charging bolt appears while charging. Keep the level text permanent.
        canvas.drawText(Integer.toString(mBatteryLevel), cx, cy - 14.2f * scale, mPaint);
    }

    private void drawCenter(Canvas canvas, float cx, float cy, float radius) {
        if (mAirplaneMode && mShowAirplane) {
            drawAirplane(canvas, cx, cy, radius);
            return;
        }
        if (mDnd && mShowDnd) {
            if (mActiveModeIcon != null) {
                drawActiveModeIcon(canvas, cx, cy, radius);
            } else {
                drawMoon(canvas, cx, cy, radius);
            }
            return;
        }
        if (mWifiConnected && mShowWifi) {
            drawWifi(canvas, cx, cy, radius, true);
            return;
        }
        String label = mShowMobile && mCellAvailable && mMobileDataEnabled
                ? networkLabel(mNetworkType, mNrConnected) : "";
        if (label.isEmpty()) {
            if (mShowWifi && mWifiOn) drawWifi(canvas, cx, cy, radius, false);
            return;
        }
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mIconTint);
        mPaint.setTextAlign(Paint.Align.CENTER);
        float labelSize = radius * (15f / RING_RADIUS_UNITS);
        mPaint.setTextSize(labelSize);
        mPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        float baseline = cy
                - (mPaint.ascent() + mPaint.descent()) / 2f;
        if (label.endsWith("+")) {
            String main = label.substring(0, label.length() - 1);
            float mainWidth = mPaint.measureText(main);
            float plusWidth;
            mPaint.setTypeface(android.graphics.Typeface.create("sans-serif", 0));
            mPaint.setTextSize(labelSize * (10f / 15f));
            plusWidth = mPaint.measureText("+");
            mPaint.setTextAlign(Paint.Align.LEFT);
            float startX = cx - (mainWidth + plusWidth - radius * (0.2f / RING_RADIUS_UNITS)) / 2f;
            mPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
            mPaint.setTextSize(labelSize);
            canvas.drawText(main, startX, baseline, mPaint);
            mPaint.setTypeface(android.graphics.Typeface.create("sans-serif", 0));
            mPaint.setTextSize(labelSize * (10f / 15f));
            // Match the HTML preview: the superscript reaches the cap-height top of the main glyph.
            android.graphics.Rect mainBounds = new android.graphics.Rect();
            android.graphics.Rect plusBounds = new android.graphics.Rect();
            mPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
            mPaint.setTextSize(labelSize);
            mPaint.getTextBounds(main, 0, main.length(), mainBounds);
            mPaint.setTypeface(android.graphics.Typeface.create("sans-serif", 0));
            mPaint.setTextSize(labelSize * (10f / 15f));
            mPaint.getTextBounds("+", 0, 1, plusBounds);
            float mainGlyphTop = baseline + mainBounds.top;
            float plusBaseline = mainGlyphTop - plusBounds.top;
            canvas.drawText("+", startX + mainWidth - radius * (0.2f / RING_RADIUS_UNITS),
                    plusBaseline, mPaint);
        } else {
            mPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(label, cx, baseline, mPaint);
        }
    }

    private void drawWifi(Canvas canvas, float cx, float cy, float radius, boolean connected) {
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        float scale = radius / RING_RADIUS_UNITS;
        mPaint.setStrokeWidth(1.5f * scale);
        mPaint.setColor(connected ? mIconTint : ((mIconTint & 0x00ffffff) | 0x66000000));
        Path wifi = new Path();
        wifi.moveTo(cx - 8.5f * scale, cy - 1.6f * scale);
        wifi.quadTo(cx, cy - 9.2f * scale, cx + 8.5f * scale, cy - 1.6f * scale);
        wifi.moveTo(cx - 5.3f * scale, cy + 1.4f * scale);
        wifi.quadTo(cx, cy - 3.3f * scale, cx + 5.3f * scale, cy + 1.4f * scale);
        wifi.moveTo(cx - 2.2f * scale, cy + 4.2f * scale);
        wifi.quadTo(cx, cy + 2.2f * scale, cx + 2.2f * scale, cy + 4.2f * scale);
        canvas.drawPath(wifi, mPaint);
        mPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy + 7.6f * scale, 1.05f * scale, mPaint);
        if (connected && mWifiStandardEnabled && mWifiStandard >= 4 && mWifiStandard <= 7) {
            mPaint.setColor(mIconTint);
            mPaint.setTextAlign(Paint.Align.LEFT);
            mPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
            mPaint.setTextSize(8.5f * scale);
            // Match the approved HTML offsets: size 8.5, x -3.0 and y +2.5.
            // The baseline and left edge sit against the lower-right Wi-Fi glyph boundary.
            canvas.drawText(Integer.toString(mWifiStandard), cx + 5.5f * scale,
                    cy + 8.5f * scale, mPaint);
        }
    }

    private void drawMoon(Canvas canvas, float cx, float cy, float radius) {
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mIconTint);
        float r = radius * .42f;
        canvas.drawCircle(cx, cy, r, mPaint);
        mPaint.setColor((mIconTint & 0x00ffffff) | 0xff000000);
        canvas.drawCircle(cx + r * .42f, cy - r * .25f, r * .82f, mPaint);
    }

    private void drawActiveModeIcon(Canvas canvas, float cx, float cy, float radius) {
        Drawable icon = mActiveModeIcon;
        if (icon == null) return;
        int size = Math.round(radius * 1.12f);
        int centerX = Math.round(cx);
        int centerY = Math.round(cy);
        icon.setBounds(centerX - size / 2, centerY - size / 2,
                centerX + size / 2, centerY + size / 2);
        icon.setTint(mIconTint);
        icon.draw(canvas);
    }

    private void drawAirplane(Canvas canvas, float cx, float cy, float radius) {
        float scale = radius * 1.55f / 24f;
        Path path = new Path();
        path.moveTo(0, -12);
        path.lineTo(2, -4);
        path.lineTo(12, 1);
        path.lineTo(12, 4);
        path.lineTo(2, 2);
        path.lineTo(2, 8);
        path.lineTo(5, 10);
        path.lineTo(5, 12);
        path.lineTo(0, 11);
        path.lineTo(-5, 12);
        path.lineTo(-5, 10);
        path.lineTo(-2, 8);
        path.lineTo(-2, 2);
        path.lineTo(-12, 4);
        path.lineTo(-12, 1);
        path.lineTo(-2, -4);
        path.close();
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mIconTint);
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(scale, scale);
        canvas.drawPath(path, mPaint);
        canvas.restore();
    }

    private void drawNetworkDots(Canvas canvas, float cx, float cy, float ringRadius) {
        // Unified Status's four lower dots represent cellular signal; Wi-Fi has its own center glyph.
        if (!mShowMobile || !mShowCellSignal) return;
        int level = mAirplaneMode ? 0 : mCellLevel;
        mPaint.setStyle(Paint.Style.FILL);
        float scale = ringRadius / RING_RADIUS_UNITS;
        float dotRadius = 1.35f * scale;
        for (int i = 0; i < 4; i++) {
            mPaint.setColor(i < level ? mIconTint : ((mIconTint & 0x00ffffff) | 0x66000000));
            canvas.drawCircle(cx + NETWORK_DOT_POSITIONS[i][0] * scale,
                    cy + NETWORK_DOT_POSITIONS[i][1] * scale, dotRadius, mPaint);
        }
    }

    private void drawChargingBolt(Canvas canvas, float cx, float cy, float scale) {
        Path bolt = new Path();
        bolt.moveTo(cx, cy - 17.7f * scale);
        bolt.lineTo(cx - 2.8f * scale, cy - 13f * scale);
        bolt.lineTo(cx - 0.8f * scale, cy - 13f * scale);
        bolt.lineTo(cx - 1.9f * scale, cy - 8.9f * scale);
        bolt.lineTo(cx + 1.7f * scale, cy - 14.2f * scale);
        bolt.lineTo(cx - 0.3f * scale, cy - 14.2f * scale);
        bolt.close();
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(0xff72d78c);
        canvas.drawPath(bolt, mPaint);
    }

    private static String networkLabel(int type, boolean nrConnected) {
        switch (type) {
            // Android reports NETWORK_TYPE_NR for 5G Standalone. For 5G NSA,
            // the data type remains LTE/LTE_CA and NR_STATE_CONNECTED is set.
            case TelephonyManager.NETWORK_TYPE_NR: return "5G+";
            case TelephonyManager.NETWORK_TYPE_LTE: return nrConnected ? "5G" : "4G";
            case TelephonyManager.NETWORK_TYPE_LTE_CA: return nrConnected ? "5G" : "4G+";
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSDPA: return "3G+";
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
            case TelephonyManager.NETWORK_TYPE_EHRPD:
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA: return "3G";
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
            case TelephonyManager.NETWORK_TYPE_IDEN:
            case TelephonyManager.NETWORK_TYPE_GSM: return "2G";
            case TelephonyManager.NETWORK_TYPE_EDGE: return "2G+";
            default: return "";
        }
    }

    private static final class NotificationFilterChanged {
        static final String ACTION = "android.app.action.INTERRUPTION_FILTER_CHANGED";
    }
}
