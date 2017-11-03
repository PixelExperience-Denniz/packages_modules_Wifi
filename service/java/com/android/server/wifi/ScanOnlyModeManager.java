/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi;

import android.annotation.NonNull;
import android.net.wifi.IClientInterface;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.net.BaseNetworkObserver;

/**
 * Manager WiFi in Scan Only Mode - no network connections.
 */
public class ScanOnlyModeManager implements ActiveModeManager {

    private final ScanOnlyModeStateMachine mStateMachine;

    private static final String TAG = "ScanOnlyModeManager";

    private final WifiNative mWifiNative;
    private final INetworkManagementService mNwService;
    private final WifiMetrics mWifiMetrics;

    private IClientInterface mClientInterface;
    private String mClientInterfaceName;

    ScanOnlyModeManager(@NonNull Looper looper, WifiNative wifiNative,
             INetworkManagementService networkManagementService, WifiMetrics wifiMetrics) {
        mStateMachine = new ScanOnlyModeStateMachine(looper);
        mWifiNative = wifiNative;
        mNwService = networkManagementService;
        mWifiMetrics = wifiMetrics;
    }

    /**
     * Start scan only mode.
     */
    public void start() {
        mStateMachine.sendMessage(ScanOnlyModeStateMachine.CMD_START);
    }

    /**
     * Cancel any pending scans and stop scan mode.
     */
    public void stop() {
        mStateMachine.sendMessage(ScanOnlyModeStateMachine.CMD_STOP);
    }

    /**
     * Helper function to increment the appropriate setup failure metrics.
     *
     * Note: metrics about these failures will move to where the issues are actually detected
     * (b/69426063)
     */
    private void incrementMetricsForSetupFailure(int failureReason) {
        if (failureReason == WifiNative.SETUP_FAILURE_HAL) {
            mWifiMetrics.incrementNumWifiOnFailureDueToHal();
        } else if (failureReason == WifiNative.SETUP_FAILURE_WIFICOND) {
            mWifiMetrics.incrementNumWifiOnFailureDueToWificond();
        }
    }

    private class ScanOnlyModeStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START = 0;
        public static final int CMD_STOP = 1;
        public static final int CMD_CLIENT_INTERFACE_BINDER_DEATH = 2;
        public static final int CMD_INTERFACE_STATUS_CHANGED = 3;

        private final State mIdleState = new IdleState();
        private final State mStartedState = new StartedState();

        private NetworkObserver mNetworkObserver;
        private boolean mIfaceIsUp = false;

        private class NetworkObserver extends BaseNetworkObserver {
            private final String mIfaceName;
            NetworkObserver(String ifaceName) {
                mIfaceName = ifaceName;
            }

            @Override
            public void interfaceLinkStateChanged(String iface, boolean up) {
                Log.d(TAG, "iface update: " + iface + " our iface: " + mIfaceName);
                if (mIfaceName.equals(iface)) {
                    ScanOnlyModeStateMachine.this.sendMessage(
                            CMD_INTERFACE_STATUS_CHANGED, up ? 1 : 0 , 0, this);
                    Log.d(TAG, "sent interface status changed message");
                }
            }
        }

        private void unregisterObserver() {
            if (mNetworkObserver == null) {
                return;
            }
            try {
                mNwService.unregisterObserver(mNetworkObserver);
            } catch (RemoteException e) { }
            mNetworkObserver = null;
        }

        ScanOnlyModeStateMachine(Looper looper) {
            super(TAG, looper);

            addState(mIdleState);
            addState(mStartedState);

            setInitialState(mIdleState);
            start();
        }

        private class IdleState extends State {

            @Override
            public void enter() {
                Log.d(TAG, "entering IdleState");
                unregisterObserver();
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        mClientInterface = null;
                        Pair<Integer, IClientInterface> statusAndInterface =
                                mWifiNative.setupForClientMode(mWifiNative.getInterfaceName());
                        if (statusAndInterface.first == WifiNative.SETUP_SUCCESS) {
                            mClientInterface = statusAndInterface.second;
                        } else {
                            incrementMetricsForSetupFailure(statusAndInterface.first);
                        }
                        if (mClientInterface == null) {
                            Log.e(TAG, "Failed to create ClientInterface.");
                            break;
                        }
                        try {
                            mClientInterfaceName = mClientInterface.getInterfaceName();
                        } catch (RemoteException e) {
                            Log.e(TAG, "Failed to retrieve ClientInterface name.");
                            break;
                        }

                        try {
                            mNetworkObserver =
                                    new NetworkObserver(mClientInterface.getInterfaceName());
                            mNwService.registerObserver(mNetworkObserver);
                        } catch (RemoteException e) {
                            unregisterObserver();
                            // TODO: update wifi scan state
                            break;
                        }

                        transitionTo(mStartedState);
                        break;
                    case CMD_STOP:
                        // This should be safe to ignore.
                        Log.d(TAG, "received CMD_STOP when idle, ignoring");
                        break;
                    default:
                        Log.d(TAG, "received an invalid message: " + message);
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        private class StartedState extends State {

            private void onUpChanged(boolean isUp) {
                if (isUp == mIfaceIsUp) {
                    return;  // no change
                }
                mIfaceIsUp = isUp;
                if (isUp) {
                    Log.d(TAG, "Wifi is ready to use for scanning");
                    // TODO: send scan available broadcast
                } else {
                    // if the interface goes down we should exit and go back to idle state.
                    mStateMachine.sendMessage(CMD_STOP);
                }
            }

            @Override
            public void enter() {
                Log.d(TAG, "entering StartedState");
            }

            @Override
            public boolean processMessage(Message message) {
                switch(message.what) {
                    case CMD_START:
                        // Already started, ignore this command.
                        break;
                    case CMD_STOP:
                        Log.d(TAG, "Stopping scan mode.");
                        transitionTo(mIdleState);
                        break;
                    case CMD_INTERFACE_STATUS_CHANGED:
                        if (message.obj != mNetworkObserver) {
                            // This is not from our current observer
                            break;
                        }
                        boolean isUp = message.arg1 == 1;
                        onUpChanged(isUp);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            /**
             * Clean up state, unregister listeners and send broadcast to tell WifiScanner
             * that wifi is disabled.
             */
            @Override
            public void exit() {
                // TODO: update WifiScanner about wifi state

                unregisterObserver();
            }
        }
    }
}
