/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server;

import com.android.server.am.ActivityManagerService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.storage.IMountService;
import android.os.storage.IMountServiceListener;
import android.os.storage.IMountShutdownObserver;
import android.os.storage.StorageResultCode;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.Environment;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Slog;
import java.util.ArrayList;
import java.util.HashSet;
import android.provider.MediaStore;

/**
 * MountService implements back-end services for platform storage
 * management.
 * @hide - Applications should use android.os.storage.StorageManager
 * to access the MountService.
 */
class MountService extends IMountService.Stub
        implements INativeDaemonConnectorCallbacks {
    private static final boolean LOCAL_LOGD = false;
    private static final boolean DEBUG_UNMOUNT = false;
    private static final boolean DEBUG_EVENTS = false;
    
    private static final String TAG = "MountService";

    /*
     * Internal vold volume state constants
     */
    class VolumeState {
        public static final int Init       = -1;
        public static final int NoMedia    = 0;
        public static final int Idle       = 1;
        public static final int Pending    = 2;
        public static final int Checking   = 3;
        public static final int Mounted    = 4;
        public static final int Unmounting = 5;
        public static final int Formatting = 6;
        public static final int Shared     = 7;
        public static final int SharedMnt  = 8;
    }

    /*
     * Internal vold response code constants
     */
    class VoldResponseCode {
        /*
         * 100 series - Requestion action was initiated; expect another reply
         *              before proceeding with a new command.
         */
        public static final int VolumeListResult               = 110;
        public static final int AsecListResult                 = 111;
        public static final int StorageUsersListResult         = 112;

        /*
         * 200 series - Requestion action has been successfully completed.
         */
        public static final int ShareStatusResult              = 210;
        public static final int AsecPathResult                 = 211;
        public static final int ShareEnabledResult             = 212;

        /*
         * 400 series - Command was accepted, but the requested action
         *              did not take place.
         */
        public static final int OpFailedNoMedia                = 401;
        public static final int OpFailedMediaBlank             = 402;
        public static final int OpFailedMediaCorrupt           = 403;
        public static final int OpFailedVolNotMounted          = 404;
        public static final int OpFailedStorageBusy            = 405;
        public static final int OpFailedStorageNotFound        = 406;

        /*
         * 600 series - Unsolicited broadcasts.
         */
        public static final int VolumeStateChange              = 605;
        public static final int ShareAvailabilityChange        = 620;
        public static final int VolumeDiskInserted             = 630;
        public static final int VolumeDiskRemoved              = 631;
        public static final int VolumeBadRemoval               = 632;
    }

    private Context                               mContext;
    private NativeDaemonConnector                 mConnector;
    private String                                mLegacySdcardState = Environment.MEDIA_REMOVED;
    /* hengai */
    private String                                mLegacyNandState = Environment.MEDIA_REMOVED;    
    // scsi
    private String                                mLegacyScsiState = Environment.MEDIA_REMOVED;
    private PackageManagerService                 mPms;
    private boolean                               mUmsEnabling;
    /* hengai */
    private boolean                               nUmsEnabling;  
    private boolean                               oUmsEnabling;     
    // Used as a lock for methods that register/unregister listeners.
    final private ArrayList<MountServiceBinderListener> mListeners =
            new ArrayList<MountServiceBinderListener>();
    private boolean                               mBooted = false;
    private boolean                               mReady = false;
    private boolean                               mSendUmsConnectedOnBoot = false;

    /**
     * Private hash of currently mounted secure containers.
     * Used as a lock in methods to manipulate secure containers.
     */
    final private HashSet<String> mAsecMountSet = new HashSet<String>();

    private static final int H_UNMOUNT_PM_UPDATE = 1;
    private static final int H_UNMOUNT_PM_DONE = 2;
    private static final int H_UNMOUNT_MS = 3;
    private static final int RETRY_UNMOUNT_DELAY = 30; // in ms
    private static final int MAX_UNMOUNT_RETRIES = 4;
    /*hengai*/
    private static final int H_INTERNAL_UNMOUNT_PM_UPDATE = 5;   
    private static final int H_INTERNAL_UNMOUNT_PM_DONE = 6;  
    private static final int H_INTERNAL_UNMOUNT_MS = 7;    
    class UnmountCallBack {
        String path;
        int retries;
        boolean force;

        UnmountCallBack(String path, boolean force) {
            retries = 0;
            this.path = path;
            this.force = force;
        }

        void handleFinished() {
            if (DEBUG_UNMOUNT) Slog.i(TAG, "Unmounting::handleFinished " + path);
            doUnmountVolume(path, true);
        }
        /* hengai */
        void handleInternalFinished() {
            if (DEBUG_UNMOUNT) Slog.i(TAG, "Unmounting::handleInternalFinished " + path);
            doUnmountVolume(path, true);
        }
    }

    class UmsEnableCallBack extends UnmountCallBack {
        String method;

        UmsEnableCallBack(String path, String method, boolean force) {
            super(path, force);
            this.method = method;
        }

        @Override
        void handleFinished() {
            super.handleFinished();
            doShareUnshareVolume(path, method, true);
        }
        /*hengai*/
        @Override
        void handleInternalFinished() {
            super.handleFinished();
            doShareUnshareVolume(path, method, true);
        }
    }

    class ShutdownCallBack extends UnmountCallBack {
        IMountShutdownObserver observer;
        ShutdownCallBack(String path, IMountShutdownObserver observer) {
            super(path, true);
            this.observer = observer;
        }

        @Override
        void handleFinished() {
            int ret = doUnmountVolume(path, true);
            if (observer != null) {
                try {
                    observer.onShutDownComplete(ret);
                } catch (RemoteException e) {
                    Slog.w(TAG, "RemoteException when shutting down");
                }
            }
        }
    }

    class MountServiceHandler extends Handler {
        ArrayList<UnmountCallBack> mForceUnmounts = new ArrayList<UnmountCallBack>();
        /*hengai*/
        ArrayList<UnmountCallBack> nForceUnmounts = new ArrayList<UnmountCallBack>();        
        boolean mUpdatingStatus = false;
        /*hengai*/
        boolean nUpdatingStatus = false;
        MountServiceHandler(Looper l) {
            super(l);
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case H_UNMOUNT_PM_UPDATE: {
                    if (DEBUG_UNMOUNT) Slog.i(TAG, "H_UNMOUNT_PM_UPDATE");
                    UnmountCallBack ucb = (UnmountCallBack) msg.obj;
                    mForceUnmounts.add(ucb);
                    if (DEBUG_UNMOUNT) Slog.i(TAG, " registered = " + mUpdatingStatus);
                    // Register only if needed.
                    if (!mUpdatingStatus) {
                        if (DEBUG_UNMOUNT) Slog.i(TAG, "Updating external media status on PackageManager");
                        mUpdatingStatus = true;
                        mPms.updateExternalMediaStatus(false, true);
                    }
                    break;
                }
                case H_INTERNAL_UNMOUNT_PM_UPDATE: {
                    if (DEBUG_UNMOUNT) Slog.i(TAG, "H_INTERNAL_UNMOUNT_PM_UPDATE");
                    UnmountCallBack ucb = (UnmountCallBack) msg.obj;
                    nForceUnmounts.add(ucb);
                    if (DEBUG_UNMOUNT) Slog.i(TAG, " registered = " + nUpdatingStatus);
                    // Register only if needed.
                    if (!nUpdatingStatus) {
                        if (DEBUG_UNMOUNT) Slog.i(TAG, "Updating internal media status on PackageManager");
                        nUpdatingStatus = true;
                        mPms.updateInternalMediaStatus(false, true);
                    }
                    break;
                }
                case H_UNMOUNT_PM_DONE: {
                    if (DEBUG_UNMOUNT) Slog.i(TAG, "H_UNMOUNT_PM_DONE");
                    if (DEBUG_UNMOUNT) Slog.i(TAG, "Updated external status. Processing requests");
                    mUpdatingStatus = false;
                    int size = mForceUnmounts.size();
                    int sizeArr[] = new int[size];
                    int sizeArrN = 0;
                    // Kill processes holding references first
                    ActivityManagerService ams = (ActivityManagerService)
                    ServiceManager.getService("activity");
                    for (int i = 0; i < size; i++) {
                        UnmountCallBack ucb = mForceUnmounts.get(i);
                        String path = ucb.path;
                        boolean done = false;
                        if (!ucb.force) {
                            done = true;
                        } else {
                            int pids[] = getStorageUsers(path);
                            if (pids == null || pids.length == 0) {
                                done = true;
                            } else {
                                // Eliminate system process here?
                                ams.killPids(pids, "unmount media");
                                // Confirm if file references have been freed.
                                pids = getStorageUsers(path);
                                if (pids == null || pids.length == 0) {
                                    done = true;
                                }
                            }
                        }
                        if (!done && (ucb.retries < MAX_UNMOUNT_RETRIES)) {
                            // Retry again
                            Slog.i(TAG, "Retrying to kill storage users again");
                            mHandler.sendMessageDelayed(
                                    mHandler.obtainMessage(H_UNMOUNT_PM_DONE,
                                            ucb.retries++),
                                    RETRY_UNMOUNT_DELAY);
                        } else {
                            if (ucb.retries >= MAX_UNMOUNT_RETRIES) {
                                Slog.i(TAG, "Failed to unmount media inspite of " +
                                        MAX_UNMOUNT_RETRIES + " retries. Forcibly killing processes now");
                            }
                            sizeArr[sizeArrN++] = i;
                            mHandler.sendMessage(mHandler.obtainMessage(H_UNMOUNT_MS,
                                    ucb));
                        }
                    }
                    // Remove already processed elements from list.
                    for (int i = (sizeArrN-1); i >= 0; i--) {
                        mForceUnmounts.remove(sizeArr[i]);
                    }
                    break;
                }
                 case H_INTERNAL_UNMOUNT_PM_DONE: {
                    if (DEBUG_UNMOUNT) Slog.i(TAG, "H_INTERNAL_UNMOUNT_PM_DONE");
                    if (DEBUG_UNMOUNT) Slog.i(TAG, "Updated internal status. Processing requests");
                    nUpdatingStatus = false;
                    int size = nForceUnmounts.size();
                    int sizeArr[] = new int[size];
                    int sizeArrN = 0;
                    // Kill processes holding references first
                    ActivityManagerService ams = (ActivityManagerService)
                    ServiceManager.getService("activity");
                    for (int i = 0; i < size; i++) {
                        UnmountCallBack ucb = nForceUnmounts.get(i);
                        String path = ucb.path;
                        boolean done = false;
                        if (!ucb.force) {
                            done = true;
                        } else {
                            int pids[] = getStorageUsers(path);
                            if (pids == null || pids.length == 0) {
                                done = true;
                            } else {
                                // Eliminate system process here?
                                ams.killPids(pids, "unmount media");
                                // Confirm if file references have been freed.
                                pids = getStorageUsers(path);
                                if (pids == null || pids.length == 0) {
                                    done = true;
                                }
                            }
                        }
                        if (!done && (ucb.retries < MAX_UNMOUNT_RETRIES)) {
                            // Retry again
                            Slog.i(TAG, "Retrying to kill storage users again");
                            mHandler.sendMessageDelayed(
                                    mHandler.obtainMessage(H_INTERNAL_UNMOUNT_PM_DONE,
                                            ucb.retries++),
                                    RETRY_UNMOUNT_DELAY);
                        } else {
                            if (ucb.retries >= MAX_UNMOUNT_RETRIES) {
                                Slog.i(TAG, "Failed to unmount media inspite of " +
                                        MAX_UNMOUNT_RETRIES + " retries. Forcibly killing processes now");
                            }
                            sizeArr[sizeArrN++] = i;
                            mHandler.sendMessage(mHandler.obtainMessage(H_INTERNAL_UNMOUNT_MS,
                                    ucb));
                        }
                    }
                    // Remove already processed elements from list.
                    for (int i = (sizeArrN-1); i >= 0; i--) {
                        nForceUnmounts.remove(sizeArr[i]);
                    }
                    break;
                }
                case H_UNMOUNT_MS : {
                    if (DEBUG_UNMOUNT) Slog.i(TAG, "H_UNMOUNT_MS");
                    UnmountCallBack ucb = (UnmountCallBack) msg.obj;
                    ucb.handleFinished();
                    break;
                }
                case H_INTERNAL_UNMOUNT_MS : {
                    if (DEBUG_UNMOUNT) Slog.i(TAG, "H_INTERNAL_UNMOUNT_MS");
                    UnmountCallBack ucb = (UnmountCallBack) msg.obj;
                    ucb.handleInternalFinished();
                    break;
                }
            }
        }
    };
    final private HandlerThread mHandlerThread;
    final private Handler mHandler;

    private void waitForReady() {
        while (mReady == false) {
            for (int retries = 5; retries > 0; retries--) {
                if (mReady) {
                    return;
                }
                SystemClock.sleep(1000);
            }
            Slog.w(TAG, "Waiting too long for mReady!");
        }
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
                mBooted = true;

                /*
                 * In the simulator, we need to broadcast a volume mounted event
                 * to make the media scanner run.
                 */
                if ("simulator".equals(SystemProperties.get("ro.product.device"))) {
                    notifyVolumeStateChange(null, "/sdcard", VolumeState.NoMedia, VolumeState.Mounted);
                    return;
                }
                new Thread() {
                    public void run() {
                        try {
                            /* hengai */
                            if(DEBUG_UNMOUNT) Slog.i(TAG, "Try boot time mount!!!");
                            String nPath = Environment.getInternalStorageDirectory().getPath();
                            String nState = getVolumeState(nPath);
                            if(DEBUG_UNMOUNT) {
                                Slog.i(TAG, "=========================");
                                Slog.i(TAG, "Try mount internal volume : " + nState);
                                Slog.i(TAG, "=========================");                            
                            }
                            if (nState.equals(Environment.MEDIA_UNMOUNTED)) {
                                
                                int rc = doMountVolume(nPath);
                                if (rc != StorageResultCode.OperationSucceeded) {
                                    Slog.e(TAG, String.format("Boot-time mount failed (%d)", rc));
                                }
                            } else if (nState.equals(Environment.MEDIA_SHARED)) {
                                /*
                                 * Bootstrap UMS enabled state since vold indicates
                                 * the volume is shared (runtime restart while ums enabled)
                                 */
                                notifyVolumeStateChange(null, nPath, VolumeState.NoMedia, VolumeState.Shared);
                            }
                          
                            String path = Environment.getSdcardStorageDirectory().getPath();
                            String state = getVolumeState(path);
							if(DEBUG_UNMOUNT) {
                                Slog.i(TAG, "=========================");                            
                                Slog.i(TAG, "Try mount external volume : " + state);
                                Slog.i(TAG, "=========================");  
							}
                            if (state.equals(Environment.MEDIA_UNMOUNTED)) {
                                int rc = doMountVolume(path);
                                if (rc != StorageResultCode.OperationSucceeded) {
                                    Slog.e(TAG, String.format("Boot-time mount failed (%d)", rc));
                                }
                            } else if (state.equals(Environment.MEDIA_SHARED)) {
                                /*
                                 * Bootstrap UMS enabled state since vold indicates
                                 * the volume is shared (runtime restart while ums enabled)
                                 */
                                notifyVolumeStateChange(null, path, VolumeState.NoMedia, VolumeState.Shared);
                            }

                            // scsi
                            path = Environment.getScsiStorageDirectory().getPath();
                            state = getVolumeState(path);
                            if(DEBUG_UNMOUNT) {
                                Slog.i(TAG, "=========================");                            
                                Slog.i(TAG, "Try mount external volume : " + state);
                                Slog.i(TAG, "=========================");  
                            }
                            if (state.equals(Environment.MEDIA_UNMOUNTED)) {
                                
                                int rc = doMountVolume(path);
                                if (rc != StorageResultCode.OperationSucceeded) {
                                    Slog.e(TAG, String.format("Boot-time mount failed (%d)", rc));
                                }
                            } else if (state.equals(Environment.MEDIA_SHARED)) {
                                /*
                                 * Bootstrap UMS enabled state since vold indicates
                                 * the volume is shared (runtime restart while ums enabled)
                                 */
                                notifyVolumeStateChange(null, path, VolumeState.NoMedia, VolumeState.Shared);
                            }
                                                       
                            /*
                             * If UMS was connected on boot, send the connected event
                             * now that we're up.
                             */
                            if (mSendUmsConnectedOnBoot) {
                                sendUmsIntent(true);
                                mSendUmsConnectedOnBoot = false;
                            }
                        } catch (Exception ex) {
                            Slog.e(TAG, "Boot-time mount exception", ex);
                        }
                    }
                }.start();
            }
        }
    };

    private final class MountServiceBinderListener implements IBinder.DeathRecipient {
        final IMountServiceListener mListener;

        MountServiceBinderListener(IMountServiceListener listener) {
            mListener = listener;
 
        }

        public void binderDied() {
            if (LOCAL_LOGD) Slog.d(TAG, "An IMountServiceListener has died!");
            synchronized(mListeners) {
                mListeners.remove(this);
                mListener.asBinder().unlinkToDeath(this, 0);
            }
        }
    }

    private void doShareUnshareVolume(String path, String method, boolean enable) {
        // TODO: Add support for multiple share methods
        if(DEBUG_UNMOUNT) Slog.w(TAG, "doShareUnshareVolume::  " + path + "  " + enable);
        if (!method.equals("ums")) {
            throw new IllegalArgumentException(String.format("Method %s not supported", method));
        }

        try {
            mConnector.doCommand(String.format(
                    "volume %sshare %s %s", (enable ? "" : "un"), path, method));
        } catch (NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to share/unshare", e);
        }
    }

    private void updatePublicVolumeState(String path, String state) {
        if(DEBUG_UNMOUNT) Slog.d(TAG, "======updatePublicVolumeState:: " + path + "  " + state  + "  ==========================");
        // scsi        
        if (path.equals(Environment.getScsiStorageDirectory().getPath())) {
            Slog.w(TAG, "updatePublicVolumeState:: " + path + "  " + state);
            if (mLegacyScsiState.equals(state)) {
                Slog.w(TAG, String.format("Duplicate state transition (%s -> %s)", mLegacyScsiState, state));
                return;
            }          
            // Update state on PackageManager
            if (Environment.MEDIA_UNMOUNTED.equals(state) || Environment.MEDIA_UNMOUNTING.equals(state)) {
                //mPms.updateExternalMediaStatus(false, false);

                String volumeName = MediaStore.getVolumeNameForPath(path);

                if( volumeName != null )
                    MediaStore.setDatabaseStatus(volumeName, MediaStore.MEDIA_DB_NOT_AVAILABLE);
                
            } else if (Environment.MEDIA_MOUNTED.equals(state)) {
                //mPms.updateExternalMediaStatus(true, false);
            }
            String oldState = mLegacyScsiState;
            mLegacyScsiState = state;
            synchronized (mListeners) {
                for (int i = mListeners.size() -1; i >= 0; i--) {
                    MountServiceBinderListener bl = mListeners.get(i);
                    try {                      
                        bl.mListener.onStorageStateChanged(path, oldState, state);
                    } catch (RemoteException rex) {
                        Slog.e(TAG, "Listener dead");
                        mListeners.remove(i);
                    } catch (Exception ex) {
                        Slog.e(TAG, "Listener failed", ex);
                    }
                }
            }

            return;
        }
        if (path.equals(Environment.getSdcardStorageDirectory().getPath())) {
            if (mLegacySdcardState.equals(state)) {
                Slog.w(TAG, String.format("Duplicate state transition (%s -> %s)", mLegacySdcardState, state));
                return;
            }          
            // Update state on PackageManager
            if (Environment.MEDIA_UNMOUNTED.equals(state) || Environment.MEDIA_UNMOUNTING.equals(state)) {
                //mPms.updateExternalMediaStatus(false, false);

                String volumeName = MediaStore.getVolumeNameForPath(path);

                if( volumeName != null )
                    MediaStore.setDatabaseStatus(volumeName, MediaStore.MEDIA_DB_NOT_AVAILABLE);
                
            } else if (Environment.MEDIA_MOUNTED.equals(state)) {
                //mPms.updateExternalMediaStatus(true, false);
            }
            String oldState = mLegacySdcardState;
            mLegacySdcardState = state;
            synchronized (mListeners) {
                for (int i = mListeners.size() -1; i >= 0; i--) {
                    MountServiceBinderListener bl = mListeners.get(i);
                    try {                      
                        bl.mListener.onStorageStateChanged(path, oldState, state);
                    } catch (RemoteException rex) {
                        Slog.e(TAG, "Listener dead");
                        mListeners.remove(i);
                    } catch (Exception ex) {
                        Slog.e(TAG, "Listener failed", ex);
                    }
                }
            }

            return;
        }

        /* hengai */
        if (path.equals(Environment.getInternalStorageDirectory().getPath())) {
            Slog.w(TAG, "updatePublicVolumenState:: " + path + state);
            if (mLegacyNandState.equals(state)) {
                Slog.w(TAG, String.format("Duplicate state transition (%s -> %s)", mLegacyNandState, state));
                return;
            }

            // Update state on PackageManager
            if (Environment.MEDIA_UNMOUNTED.equals(state)) {
                mPms.updateInternalMediaStatus(false, false);
            } else if (Environment.MEDIA_MOUNTED.equals(state)) {
                mPms.updateInternalMediaStatus(true, false);
            }
            String oldState = mLegacyNandState;
            mLegacyNandState = state;
            synchronized (mListeners) {
                for (int i = mListeners.size() -1; i >= 0; i--) {
                    MountServiceBinderListener bl = mListeners.get(i);
                    try {
                        bl.mListener.onStorageStateChanged(path, oldState, state);
                    } catch (RemoteException rex) {
                        Slog.e(TAG, "Listener dead");
                        mListeners.remove(i);
                    } catch (Exception ex) {
                        Slog.e(TAG, "Listener failed", ex);
                    }
                }
            }
            return;
        }
    }

    /**
     *
     * Callback from NativeDaemonConnector
     */
    public void onDaemonConnected() {
        /*
         * Since we'll be calling back into the NativeDaemonConnector,
         * we need to do our work in a new thread.
         */
        new Thread() {
            public void run() {
                /**
                 * Determine media state and UMS detection status
                 */
                
                try {
                    String[] vols = mConnector.doListCommand(
                        "volume list", VoldResponseCode.VolumeListResult);
                    for (String volstr : vols) {
                        String state = null;
                        String[] tok = volstr.split(" ");
                        /* hengai */
                        // FMT: <label> <mountpoint> <state>
                        //if (!tok[1].equals(path)) {
                        //    Slog.e(TAG, "==========================================");
                        //    Slog.w(TAG, String.format("Skipping unknown volume '%s'",tok[1]));
                        //    Slog.e(TAG, "==========================================");                            
                        //    continue;
                        //}
                        int st = Integer.parseInt(tok[2]);
                        if (st == VolumeState.NoMedia) {
                            state = Environment.MEDIA_REMOVED;
                        } else if (st == VolumeState.Idle) {
                            state = Environment.MEDIA_UNMOUNTED;
                        } else if (st == VolumeState.Mounted) {
                            state = Environment.MEDIA_MOUNTED;
                            Slog.i(TAG, "Media already mounted on daemon connection");
                        } else if (st == VolumeState.Shared) {
                            state = Environment.MEDIA_SHARED;
                            Slog.i(TAG, "Media shared on daemon connection");
                        } else {
                            throw new Exception(String.format("path=" + tok[1] + " Unexpected state %d", st));
                        }

                        if (state != null) {
                            Slog.i(TAG, "onDaemonConnected path=" + tok[1] + " state=" + state);
                            updatePublicVolumeState(tok[1], state);
                        }
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Error processing initial volume state", e);
                    //updatePublicVolumeState(Environment.getNandStorageDirectory().getPath(), Environment.MEDIA_REMOVED);
                    //updatePublicVolumeState(Environment.getSdcardStorageDirectory().getPath(), Environment.MEDIA_REMOVED);
                    //updatePublicVolumeState(Environment.getScsiStorageDirectory().getPath(), Environment.MEDIA_REMOVED);
                }

                try {
                    boolean avail = doGetShareMethodAvailable("ums");
                    notifyShareAvailabilityChange("ums", avail);
                } catch (Exception ex) {
                    Slog.w(TAG, "Failed to get share availability");
                }
                /*
                 * Now that we've done our initialization, release 
                 * the hounds!
                 */
                mReady = true;
            }
        }.start();
    }

    /**
     * Callback from NativeDaemonConnector
     */
    public boolean onEvent(int code, String raw, String[] cooked) {
        Intent in = null;
        if (DEBUG_EVENTS) {
            StringBuilder builder = new StringBuilder();
            builder.append("onEvent::");
            builder.append(" raw= " + raw);
            if (cooked != null) {
                builder.append(" cooked = " );
                for (String str : cooked) {
                    builder.append(" " + str);
                }
            }
            Slog.i(TAG, builder.toString());
        }
        if (code == VoldResponseCode.VolumeStateChange) {
            /*
             * One of the volumes we're managing has changed state.
             * Format: "NNN Volume <label> <path> state changed
             * from <old_#> (<old_str>) to <new_#> (<new_str>)"
             */
            notifyVolumeStateChange(
                    cooked[2], cooked[3], Integer.parseInt(cooked[7]),
                            Integer.parseInt(cooked[10]));
        } else if (code == VoldResponseCode.ShareAvailabilityChange) {
            // FMT: NNN Share method <method> now <available|unavailable>
            boolean avail = false;
            /*hengai*/
            if (oUmsEnabling) {
                Slog.e(TAG, "Mount Service::ums already enabled..");
                oUmsEnabling = false;                       
                // return false;
            }
            if (cooked[5].equals("available")) {
                avail = true;
            }
            notifyShareAvailabilityChange(cooked[3], avail);
        } else if ((code == VoldResponseCode.VolumeDiskInserted) ||
                   (code == VoldResponseCode.VolumeDiskRemoved) ||
                   (code == VoldResponseCode.VolumeBadRemoval)) {
            // FMT: NNN Volume <label> <mountpoint> disk inserted (<major>:<minor>)
            // FMT: NNN Volume <label> <mountpoint> disk removed (<major>:<minor>)
            // FMT: NNN Volume <label> <mountpoint> bad removal (<major>:<minor>)
            final String label = cooked[2];
            final String path = cooked[3];
            int major = -1;
            int minor = -1;

            try {
                String devComp = cooked[6].substring(1, cooked[6].length() -1);
                String[] devTok = devComp.split(":");
                major = Integer.parseInt(devTok[0]);
                minor = Integer.parseInt(devTok[1]);
            } catch (Exception ex) {
                Slog.e(TAG, "Failed to parse major/minor", ex);
            }

            if (code == VoldResponseCode.VolumeDiskInserted) {
                if (oUmsEnabling) {
                    Slog.e(TAG, "Mount Service::ums already enabled..");

                    if( "sdcard".equals(cooked[2]) ) {
                        new Thread() {
                            public void run() {
                                try {
                                    doShareUnshareVolume(Environment.getSdcardStorageDirectory().getPath(), "ums", true);
                                } catch (Exception ex) {
                                    Slog.w(TAG, "Failed to share media", ex);
                                }
                            }
                        }.start();
                    }
                    
                    return true;
                }
                
                new Thread() {
                    public void run() {
                        try {
                            int rc;
                            if ((rc = doMountVolume(path)) != StorageResultCode.OperationSucceeded) {
                                Slog.w(TAG, String.format("Insertion mount failed (%d)", rc));
                            }
                        } catch (Exception ex) {
                            Slog.w(TAG, "Failed to mount media on insertion", ex);
                        }
                    }
                }.start();
            } else if (code == VoldResponseCode.VolumeDiskRemoved) {
                /*
                 * This event gets trumped if we're already in BAD_REMOVAL state
                 */
                if (getVolumeState(path).equals(Environment.MEDIA_BAD_REMOVAL)) {
                    return true;
                }

                if (oUmsEnabling) {
                    Slog.e(TAG, "Mount Service::ums enable not yet");

                    if( "sdcard".equals(cooked[2]) ) {
                        new Thread() {
                            public void run() {
                                try {
                                    doShareUnshareVolume(Environment.getSdcardStorageDirectory().getPath(), "ums", false);
                                    updatePublicVolumeState(path, Environment.MEDIA_REMOVED);
                                } catch (Exception ex) {
                                    Slog.w(TAG, "Failed to unshare media", ex);
                                }
                            }
                        }.start();
                    }
                    
                    return false;
                } 

                /* Send the media unmounted event first */
                if (DEBUG_EVENTS) Slog.i(TAG, "Sending unmounted event first");
                updatePublicVolumeState(path, Environment.MEDIA_UNMOUNTED);
                in = new Intent(Intent.ACTION_MEDIA_UNMOUNTED, Uri.parse("file://" + path));
                mContext.sendBroadcast(in);

                if (DEBUG_EVENTS) Slog.i(TAG, "Sending media removed");
                updatePublicVolumeState(path, Environment.MEDIA_REMOVED);
                in = new Intent(Intent.ACTION_MEDIA_REMOVED, Uri.parse("file://" + path));
            } else if (code == VoldResponseCode.VolumeBadRemoval) {
                if (DEBUG_EVENTS) Slog.i(TAG, "Sending unmounted event first");
                /* Send the media unmounted event first */
                /*hengai*/
                if (oUmsEnabling) {
                    Slog.i(TAG, "Not yet ums enabled.");
                    return false;
                }
                
                updatePublicVolumeState(path, Environment.MEDIA_UNMOUNTED);
                in = new Intent(Intent.ACTION_MEDIA_UNMOUNTED, Uri.parse("file://" + path));

                Slog.i(TAG, "sendBroadcast : " + in); 
                mContext.sendBroadcast(in);

                if (DEBUG_EVENTS) Slog.i(TAG, "Sending media bad removal");
                updatePublicVolumeState(path, Environment.MEDIA_BAD_REMOVAL);
                in = new Intent(Intent.ACTION_MEDIA_BAD_REMOVAL, Uri.parse("file://" + path));
            } else {
                Slog.e(TAG, String.format("Unknown code {%d}", code));
            }
        } else {
            return false;
        }

        if (in != null) {
            Slog.i(TAG, "sendBroadcast : " + in); 
            mContext.sendBroadcast(in);
        }
        return true;
    }

    private void notifyVolumeStateChange(String label, String path, int oldState, int newState) {
        String vs = getVolumeState(path);
        if (DEBUG_EVENTS) Slog.i(TAG, "notifyVolumeStateChanged::" + vs);

        Intent in = null;


        if ( !label.equals("nand")) {
            if (oUmsEnabling) {
                Slog.i(TAG, "====>> already ums enable");
                //return;
            }    
        }

        if (oldState == VolumeState.Shared && newState != oldState) {
            if (LOCAL_LOGD) Slog.d(TAG, "Sending ACTION_MEDIA_UNSHARED intent");
            mContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_UNSHARED,
                                                Uri.parse("file://" + path)));
        }

        if (newState == VolumeState.Init) {
        } else if (newState == VolumeState.NoMedia) {
            // NoMedia is handled via Disk Remove events
        } else if (newState == VolumeState.Idle) {
            if (label.equals("nand")) {
                /*
                 * Don't notify if we're in BAD_REMOVAL, NOFS, UNMOUNTABLE, or
                 * if we're in the process of enabling UMS
                 */
                if (!vs.equals(Environment.MEDIA_BAD_REMOVAL) && 
                    !vs.equals(Environment.MEDIA_NOFS) && 
                    !vs.equals(Environment.MEDIA_UNMOUNTABLE) && 
                    !getInternalUmsEnabling()) {
                        if (DEBUG_EVENTS) 
                            Slog.i(TAG, "updating volume state for media bad removal nofs and unmountable");
                        updatePublicVolumeState(path, Environment.MEDIA_UNMOUNTED);
                        in = new Intent(Intent.ACTION_MEDIA_UNMOUNTED, Uri.parse("file://" + path));
                    }

            } else {
                /*
                 * Don't notify if we're in BAD_REMOVAL, NOFS, UNMOUNTABLE, or
                 * if we're in the process of enabling UMS
                 */
                if (!vs.equals(Environment.MEDIA_BAD_REMOVAL) && 
                    !vs.equals(Environment.MEDIA_NOFS) && 
                    !vs.equals(Environment.MEDIA_UNMOUNTABLE) && 
                    !getUmsEnabling() &&
                    !oUmsEnabling) {
                    if (DEBUG_EVENTS) Slog.i(TAG, "updating volume state for media bad removal nofs and unmountable");
                    updatePublicVolumeState(path, Environment.MEDIA_UNMOUNTED);
                    in = new Intent(Intent.ACTION_MEDIA_UNMOUNTED, Uri.parse("file://" + path));
                } else {
                    updatePublicVolumeState(path, Environment.MEDIA_UNMOUNTED);
                }
            }
        } else if (newState == VolumeState.Pending) {
        } else if (newState == VolumeState.Checking) {
            if (DEBUG_EVENTS) Slog.i(TAG, "updating volume state checking");
            updatePublicVolumeState(path, Environment.MEDIA_CHECKING);
            in = new Intent(Intent.ACTION_MEDIA_CHECKING, Uri.parse("file://" + path));
        } else if (newState == VolumeState.Mounted) {
            if (DEBUG_EVENTS) Slog.i(TAG, "updating volume state mounted");
            updatePublicVolumeState(path, Environment.MEDIA_MOUNTED);
            in = new Intent(Intent.ACTION_MEDIA_MOUNTED, Uri.parse("file://" + path));
            in.putExtra("read-only", false);
            in.putExtra("storage-type", label);
            Slog.i("unic", "storage-type="+in.getStringExtra("storage-type"));
        } else if (newState == VolumeState.Unmounting) {
            in = new Intent(Intent.ACTION_MEDIA_EJECT, Uri.parse("file://" + path));
            updatePublicVolumeState(path, Environment.MEDIA_UNMOUNTING);
        } else if (newState == VolumeState.Formatting) {
        } else if (newState == VolumeState.Shared) {
            if (DEBUG_EVENTS) Slog.i(TAG, "Updating volume state media mounted");
            /* Send the media unmounted event first */
            updatePublicVolumeState(path, Environment.MEDIA_UNMOUNTED);
            in = new Intent(Intent.ACTION_MEDIA_UNMOUNTED, Uri.parse("file://" + path));
            mContext.sendBroadcast(in);

            if (DEBUG_EVENTS) Slog.i(TAG, "Updating media shared");
            updatePublicVolumeState(path, Environment.MEDIA_SHARED);
            in = new Intent(Intent.ACTION_MEDIA_SHARED, Uri.parse("file://" + path));
            if (LOCAL_LOGD) Slog.d(TAG, "Sending ACTION_MEDIA_SHARED intent");
        } else if (newState == VolumeState.SharedMnt) {
            Slog.e(TAG, "Live shared mounts not supported yet!");
            return;
        } else {
            Slog.e(TAG, "Unhandled VolumeState {" + newState + "}");
        }

        if (in != null) {
            mContext.sendBroadcast(in);
        }
    }

    private boolean doGetShareMethodAvailable(String method) {
        ArrayList<String> rsp;
        try {
            rsp = mConnector.doCommand("share status " + method);
        } catch (NativeDaemonConnectorException ex) {
            Slog.e(TAG, "Failed to determine whether share method " + method + " is available.");
            return false;
        }

        for (String line : rsp) {
            String[] tok = line.split(" ");
            if (tok.length < 3) {
                Slog.e(TAG, "Malformed response to share status " + method);
                return false;
            }

            int code;
            try {
                code = Integer.parseInt(tok[0]);
            } catch (NumberFormatException nfe) {
                Slog.e(TAG, String.format("Error parsing code %s", tok[0]));
                return false;
            }
            if (code == VoldResponseCode.ShareStatusResult) {
                if (tok[2].equals("available"))
                    return true;
                return false;
            } else {
                Slog.e(TAG, String.format("Unexpected response code %d", code));
                return false;
            }
        }
        Slog.e(TAG, "Got an empty response");
        return false;
    }

    private int doMountVolume(String path) {
        int rc = StorageResultCode.OperationSucceeded;
        if(DEBUG_UNMOUNT) {
            Slog.w(TAG, "==================================================");
            Slog.w(TAG, "doMountVolume::  " + path);
            Slog.w(TAG, "==================================================");
        }
        if (path.equals(Environment.getInternalStorageDirectory().getPath())) {

            if (DEBUG_EVENTS) Slog.i(TAG, "doMountVolume::internal Mouting " + path);
            try {
                mConnector.doCommand(String.format("volume mount %s", path));
            } catch (NativeDaemonConnectorException e) {
                /*
                 * Mount failed for some reason
                 */
                Intent in = null;
                int code = e.getCode();
                if (code == VoldResponseCode.OpFailedNoMedia) {
                    /*
                     * Attempt to mount but no media inserted
                     */
                    rc = StorageResultCode.OperationFailedNoMedia;
                } else if (code == VoldResponseCode.OpFailedMediaBlank) {
                    if (DEBUG_EVENTS) Slog.i(TAG, " updating internal volume state :: media nofs");
                    /*
                     * Media is blank or does not contain a supported filesystem
                     */
                    updatePublicVolumeState(path, Environment.MEDIA_NOFS);
                    in = new Intent(Intent.ACTION_MEDIA_NOFS, Uri.parse("file://" + path));
                    rc = StorageResultCode.OperationFailedMediaBlank;
                } else if (code == VoldResponseCode.OpFailedMediaCorrupt) {
                    if (DEBUG_EVENTS) Slog.i(TAG, "updating internal volume state media corrupt");
                    /*
                     * Volume consistency check failed
                     */
                    updatePublicVolumeState(path, Environment.MEDIA_UNMOUNTABLE);
                    in = new Intent(Intent.ACTION_MEDIA_UNMOUNTABLE, Uri.parse("file://" + path));
                    rc = StorageResultCode.OperationFailedMediaCorrupt;
                } else {
                    rc = StorageResultCode.OperationFailedInternalError;
                }

                /*
                 * Send broadcast intent (if required for the failure)
                 */
                if (in != null) {
                    Slog.i(TAG, "sendBroadcast : " + in); 
                    mContext.sendBroadcast(in);
                }
            }
            
        } else {
            if(DEBUG_UNMOUNT) {
                Slog.w(TAG, "==================================================");
                Slog.w(TAG, "doMountVolume::  " + path);
                Slog.w(TAG, "==================================================");
            }
            if (DEBUG_EVENTS) Slog.i(TAG, "doMountVolume::external Mouting " + path);
            try {
                mConnector.doCommand(String.format("volume mount %s", path));
            } catch (NativeDaemonConnectorException e) {
                /*
                 * Mount failed for some reason
                 */
                Intent in = null;
                int code = e.getCode();
                if (code == VoldResponseCode.OpFailedNoMedia) {
                    /*
                     * Attempt to mount but no media inserted
                     */
                    rc = StorageResultCode.OperationFailedNoMedia;
                } else if (code == VoldResponseCode.OpFailedMediaBlank) {
                    if (DEBUG_EVENTS) Slog.i(TAG, " updating external volume state :: media nofs");
                    /*
                     * Media is blank or does not contain a supported filesystem
                     */
                    updatePublicVolumeState(path, Environment.MEDIA_NOFS);
                    in = new Intent(Intent.ACTION_MEDIA_NOFS, Uri.parse("file://" + path));
                    rc = StorageResultCode.OperationFailedMediaBlank;
                } else if (code == VoldResponseCode.OpFailedMediaCorrupt) {
                    if (DEBUG_EVENTS) Slog.i(TAG, "updating external volume state media corrupt");
                    /*
                     * Volume consistency check failed
                     */
                    updatePublicVolumeState(path, Environment.MEDIA_UNMOUNTABLE);
                    in = new Intent(Intent.ACTION_MEDIA_UNMOUNTABLE, Uri.parse("file://" + path));
                    rc = StorageResultCode.OperationFailedMediaCorrupt;
                } else {
                    rc = StorageResultCode.OperationFailedInternalError;
                }

                /*
                 * Send broadcast intent (if required for the failure)
                 */
                if (in != null) {
                    mContext.sendBroadcast(in);
                }
            }
        }

        return rc;
    }

    /*
     * If force is not set, we do not unmount if there are
     * processes holding references to the volume about to be unmounted.
     * If force is set, all the processes holding references need to be
     * killed via the ActivityManager before actually unmounting the volume.
     * This might even take a while and might be retried after timed delays
     * to make sure we dont end up in an instable state and kill some core
     * processes.
     */
    private int doUnmountVolume(String path, boolean force) {
        if(DEBUG_UNMOUNT) {
            Slog.w(TAG, "==================================================");
            Slog.w(TAG, "doUnmountVolume::  " + path + " " + force);
            Slog.w(TAG, "==================================================");
        }
        if (path.equals(Environment.getInternalStorageDirectory().getPath())) {
            if(DEBUG_UNMOUNT) Slog.w(TAG, "Internal Storage::  " + path + " " + force);
            if (!getVolumeState(path).equals(Environment.MEDIA_MOUNTED) && !getVolumeState(path).equals(Environment.MEDIA_UNMOUNTING) ) {
                return VoldResponseCode.OpFailedVolNotMounted;
            }
            mPms.updateInternalMediaStatus(false, false);
            try {
                mConnector.doCommand(String.format(
                        "volume unmount %s%s", path, (force ? " force" : "")));
                // We unmounted the volume. None of the asec containers are available now.
                synchronized (mAsecMountSet) {
                    mAsecMountSet.clear();
                }
                return StorageResultCode.OperationSucceeded;
            } catch (NativeDaemonConnectorException e) {
                // Don't worry about mismatch in PackageManager since the
                // call back will handle the status changes any way.
                int code = e.getCode();
                if (code == VoldResponseCode.OpFailedVolNotMounted) {
                    return StorageResultCode.OperationFailedStorageNotMounted;
                } else if (code == VoldResponseCode.OpFailedStorageBusy) {
                    return StorageResultCode.OperationFailedStorageBusy;
                } else {
                    return StorageResultCode.OperationFailedInternalError;
                }
            }

        } else {
            if(DEBUG_UNMOUNT) Slog.w(TAG, "External Storage::  " + path + " " + force);
            if (!getVolumeState(path).equals(Environment.MEDIA_MOUNTED) && !getVolumeState(path).equals(Environment.MEDIA_UNMOUNTING)) {
                return VoldResponseCode.OpFailedVolNotMounted;
            }
            // Redundant probably. But no harm in updating state again.
            //mPms.updateExternalMediaStatus(false, false);
            try {
                mConnector.doCommand(String.format(
                        "volume unmount %s%s", path, (force ? " force" : "")));
                // We unmounted the volume. None of the asec containers are available now.
                synchronized (mAsecMountSet) {
                    mAsecMountSet.clear();
                }
                return StorageResultCode.OperationSucceeded;
            } catch (NativeDaemonConnectorException e) {
                // Don't worry about mismatch in PackageManager since the
                // call back will handle the status changes any way.
                int code = e.getCode();
                if (code == VoldResponseCode.OpFailedVolNotMounted) {
                    return StorageResultCode.OperationFailedStorageNotMounted;
                } else if (code == VoldResponseCode.OpFailedStorageBusy) {
                    return StorageResultCode.OperationFailedStorageBusy;
                } else {
                    return StorageResultCode.OperationFailedInternalError;
                }
            }
        }
    }

    private int doFormatVolume(String path) {
        try {
            String cmd = String.format("volume format %s", path);
            mConnector.doCommand(cmd);
            return StorageResultCode.OperationSucceeded;
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedNoMedia) {
                return StorageResultCode.OperationFailedNoMedia;
            } else if (code == VoldResponseCode.OpFailedMediaCorrupt) {
                return StorageResultCode.OperationFailedMediaCorrupt;
            } else {
                return StorageResultCode.OperationFailedInternalError;
            }
        }
    }

    private boolean doGetVolumeShared(String path, String method) {
        String cmd = String.format("volume shared %s %s", path, method);
        ArrayList<String> rsp;

        try {
            rsp = mConnector.doCommand(cmd);
        } catch (NativeDaemonConnectorException ex) {
            Slog.e(TAG, "Failed to read response to volume shared " + path + " " + method);
            return false;
        }

        for (String line : rsp) {
            String[] tok = line.split(" ");
            if (tok.length < 3) {
                Slog.e(TAG, "Malformed response to volume shared " + path + " " + method + " command");
                return false;
            }

            int code;
            try {
                code = Integer.parseInt(tok[0]);
            } catch (NumberFormatException nfe) {
                Slog.e(TAG, String.format("Error parsing code %s", tok[0]));
                return false;
            }
            if (code == VoldResponseCode.ShareEnabledResult) {
                return "enabled".equals(tok[2]);
            } else {
                Slog.e(TAG, String.format("Unexpected response code %d", code));
                return false;
            }
        }
        Slog.e(TAG, "Got an empty response");
        return false;
    }

    private void notifyShareAvailabilityChange(String method, final boolean avail) {
        if (!method.equals("ums")) {
           Slog.w(TAG, "Ignoring unsupported share method {" + method + "}");
           return;
        }

        synchronized (mListeners) {
            for (int i = mListeners.size() -1; i >= 0; i--) {
                MountServiceBinderListener bl = mListeners.get(i);
                try {
                    bl.mListener.onUsbMassStorageConnectionChanged(avail);
                } catch (RemoteException rex) {
                    Slog.e(TAG, "Listener dead");
                    mListeners.remove(i);
                } catch (Exception ex) {
                    Slog.e(TAG, "Listener failed", ex);
                }
            }
        }

        if (mBooted == true) {
            sendUmsIntent(avail);
        } else {
            mSendUmsConnectedOnBoot = avail;
        }

        final String scsiPath = Environment.getScsiStorageDirectory().getPath();
        final String path = Environment.getSdcardStorageDirectory().getPath();
        final String nPath = Environment.getInternalStorageDirectory().getPath();        

        if(LOCAL_LOGD) Slog.d(TAG, "============notifyShareAvailabilityChange==================  nand_state=" + getVolumeState(nPath) + "   sdcard_state=" + getVolumeState(path));

        if ((avail == false && getVolumeState(scsiPath).equals(Environment.MEDIA_SHARED)) ||
            (avail == false && getVolumeState(nPath).equals(Environment.MEDIA_SHARED)) ||
            (avail == false && getVolumeState(path).equals(Environment.MEDIA_SHARED))) {
            /*
             * USB mass storage disconnected while enabled
             */
            new Thread() {
                public void run() {
                    try {
                        int rc;
                        Slog.w(TAG, "Disabling UMS after cable disconnect");

                        if( !getVolumeState(nPath).equals(Environment.MEDIA_REMOVED) ) {
                            doShareUnshareVolume(nPath, "ums", false);   
                            if ((rc = doMountVolume(nPath)) != StorageResultCode.OperationSucceeded) {
                                Slog.e(TAG, String.format(
                                        "Failed to remount {%s} on UMS enabled-disconnect (%d)",
                                                nPath, rc));
                            }
                        }

                        if( !getVolumeState(path).equals(Environment.MEDIA_REMOVED) ) {
                            doShareUnshareVolume(path, "ums", false);
                            if ((rc = doMountVolume(path)) != StorageResultCode.OperationSucceeded) {
                                Slog.e(TAG, String.format(
                                        "Failed to remount {%s} on UMS enabled-disconnect (%d)",
                                                path, rc));
                            }
                        }

                        if( !getVolumeState(scsiPath).equals(Environment.MEDIA_REMOVED) ) {
                            doShareUnshareVolume(scsiPath, "ums", false);   
                            if ((rc = doMountVolume(scsiPath)) != StorageResultCode.OperationSucceeded) {
                                Slog.e(TAG, String.format(
                                        "Failed to remount {%s} on UMS enabled-disconnect (%d)",
                                                scsiPath, rc));
                            }
                        }
                    } catch (Exception ex) {
                        Slog.w(TAG, "Failed to mount media on UMS enabled-disconnect", ex);
                    }
                }
            }.start();
        }
    }

    private void sendUmsIntent(boolean c) {
        mContext.sendBroadcast(
                new Intent((c ? Intent.ACTION_UMS_CONNECTED : Intent.ACTION_UMS_DISCONNECTED)));
    }

    private void validatePermission(String perm) {
        if (mContext.checkCallingOrSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(String.format("Requires %s permission", perm));
        }
    }

    /**
     * Constructs a new MountService instance
     *
     * @param context  Binder context for this service
     */
    public MountService(Context context) {
        mContext = context;

        // XXX: This will go away soon in favor of IMountServiceObserver
        mPms = (PackageManagerService) ServiceManager.getService("package");

        mContext.registerReceiver(mBroadcastReceiver,
                new IntentFilter(Intent.ACTION_BOOT_COMPLETED), null, null);

        mHandlerThread = new HandlerThread("MountService");
        mHandlerThread.start();
        mHandler = new MountServiceHandler(mHandlerThread.getLooper());

        /*
         * Vold does not run in the simulator, so pretend the connector thread
         * ran and did its thing.
         */
        if ("simulator".equals(SystemProperties.get("ro.product.device"))) {
            mReady = true;
            mUmsEnabling = true;
            return;
        }

        mConnector = new NativeDaemonConnector(this, "vold", 10, "VoldConnector");
        mReady = false;
        Thread thread = new Thread(mConnector, NativeDaemonConnector.class.getName());
        thread.start();
    }

    /**
     * Exposed API calls below here
     */

    public void registerListener(IMountServiceListener listener) {
        synchronized (mListeners) {
            MountServiceBinderListener bl = new MountServiceBinderListener(listener);
            try {
                listener.asBinder().linkToDeath(bl, 0);
                mListeners.add(bl);
            } catch (RemoteException rex) {
                Slog.e(TAG, "Failed to link to listener death");
            }
        }
    }

    public void unregisterListener(IMountServiceListener listener) {
        synchronized (mListeners) {
            for(MountServiceBinderListener bl : mListeners) {
                if (bl.mListener == listener) {
                    mListeners.remove(mListeners.indexOf(bl));
                    return;
                }
            }
        }
    }

    public void shutdown(final IMountShutdownObserver observer) {
        validatePermission(android.Manifest.permission.SHUTDOWN);

        Slog.i(TAG, "Shutting down");

        String path = Environment.getSdcardStorageDirectory().getPath();
        String state = getVolumeState(path);

        if (state.equals(Environment.MEDIA_SHARED)) {
            /*
             * If the media is currently shared, unshare it.
             * XXX: This is still dangerous!. We should not
             * be rebooting at *all* if UMS is enabled, since
             * the UMS host could have dirty FAT cache entries
             * yet to flush.
             */
            setUsbMassStorageEnabled(false);
        } else if (state.equals(Environment.MEDIA_CHECKING)) {
            /*
             * If the media is being checked, then we need to wait for
             * it to complete before being able to proceed.
             */
            // XXX: @hackbod - Should we disable the ANR timer here?
            int retries = 30;
            while (state.equals(Environment.MEDIA_CHECKING) && (retries-- >=0)) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException iex) {
                    Slog.e(TAG, "Interrupted while waiting for media", iex);
                    break;
                }
                state = Environment.getSdcardStorageState();
            }
            if (retries == 0) {
                Slog.e(TAG, "Timed out waiting for media to check");
            }
        }
/*
        if (state.equals(Environment.MEDIA_MOUNTED)) {
            // Post a unmount message.
            ShutdownCallBack ucb = new ShutdownCallBack(path, observer);
            mHandler.sendMessage(mHandler.obtainMessage(H_UNMOUNT_PM_UPDATE, ucb));
        } else if (state.equals(Environment.MEDIA_REMOVED)) {
            ShutdownCallBack ucb = new ShutdownCallBack(path, observer);
            mHandler.sendMessage(mHandler.obtainMessage(H_UNMOUNT_MS, ucb));
        }
*/
            try {
                observer.onShutDownComplete(0);
            } catch (RemoteException e) {
                Slog.w(TAG, "RemoteException when shutting down");
            }
    }

    private boolean getUmsEnabling() {
        synchronized (mListeners) {
            return mUmsEnabling;
        }
    }

    private void setUmsEnabling(boolean enable) {
        synchronized (mListeners) {
            mUmsEnabling = true;
            oUmsEnabling = true;
        }
    }
    /* hengai */
    private boolean getInternalUmsEnabling() {
        synchronized (mListeners) {
            return nUmsEnabling;
        }
    }

    private void setInternalUmsEnabling(boolean enable) {
        synchronized (mListeners) {
            nUmsEnabling = true;
            oUmsEnabling = true;
        }
    }

    public boolean isUsbMassStorageConnected() {
        waitForReady();
        if(LOCAL_LOGD) Slog.i(TAG, "isUsbMassStorageConnected");  
        if (getUmsEnabling()) {
        if(LOCAL_LOGD) Slog.i(TAG, "isUsbMassStorageConnected::true");              
            return true;
        }
        return doGetShareMethodAvailable("ums");
    }

    /*hengai */
    public boolean isInternalUsbMassStorageConnected() {
        waitForReady();
        if(LOCAL_LOGD) Slog.i(TAG, "isInternalUsbMassStorageConnected");  
        if (getInternalUmsEnabling()) {
            Slog.i(TAG, "isInternalUsbMassStorageConnected::true");              
            return true;
        }
        return doGetShareMethodAvailable("ums");
    }

    public void setUsbMassStorageEnabled(boolean enable) {
        if(LOCAL_LOGD) Slog.i(TAG, "setUsbMassStorageEnabled:  " + enable);        
        waitForReady();
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);

        // TODO: Add support for multiple share methods

        /*
         * If the volume is mounted and we're enabling then unmount it
         */
        String path = Environment.getSdcardStorageDirectory().getPath();
        String vs = getVolumeState(path);
        String method = "ums";  
        
        // scsi
        String scsiPath = Environment.getScsiStorageDirectory().getPath();
        String scsivs = getVolumeState(scsiPath);
            
        /* hengai */
        String nPath = Environment.getInternalStorageDirectory().getPath();
        String nvs = getVolumeState(nPath);
        if ( enable ) {
			// scsi
            if ( scsivs.equals(Environment.MEDIA_MOUNTED) ) {
                MediaStore.setDatabaseStatus(MediaStore.SCSI_VOLUME, MediaStore.MEDIA_DB_NOT_AVAILABLE);

                mContext.sendBroadcast(
                        new Intent(Intent.ACTION_MEDIA_EJECT, Uri.parse("file://" + scsiPath)));
            }
        	
            if ( vs.equals(Environment.MEDIA_MOUNTED) ) {
                MediaStore.setDatabaseStatus(MediaStore.SDCARD_VOLUME, MediaStore.MEDIA_DB_NOT_AVAILABLE);
                
                mContext.sendBroadcast(
                        new Intent(Intent.ACTION_MEDIA_EJECT, Uri.parse("file://" + path)));
             }
            
            if ( nvs.equals(Environment.MEDIA_MOUNTED) ) {
                MediaStore.setDatabaseStatus(MediaStore.NAND_VOLUME, MediaStore.MEDIA_DB_NOT_AVAILABLE);
                
                mContext.sendBroadcast(
                        new Intent(Intent.ACTION_MEDIA_EJECT, Uri.parse("file://" + nPath)));
            } 
        }

        if (enable && vs.equals(Environment.MEDIA_MOUNTED)) {
            // Override for isUsbMassStorageEnabled()
            setUmsEnabling(enable);
            UmsEnableCallBack umscb = new UmsEnableCallBack(path, method, true);
            mHandler.sendMessage(mHandler.obtainMessage(H_UNMOUNT_PM_UPDATE, umscb));
            // Clear override
            setUmsEnabling(false);
        }

        /* hengai */
        if (enable && nvs.equals(Environment.MEDIA_MOUNTED)) {           
            // Override for isUsbMassStorageEnabled()
            setInternalUmsEnabling(enable);
            UmsEnableCallBack numscb = new UmsEnableCallBack(nPath, method, true);
            mHandler.sendMessage(mHandler.obtainMessage(H_INTERNAL_UNMOUNT_PM_UPDATE, numscb));
            // Clear override
            setInternalUmsEnabling(false);
        }

        if (enable && scsivs.equals(Environment.MEDIA_MOUNTED)) {           
            // Override for isUsbMassStorageEnabled()
            setUmsEnabling(enable);
            UmsEnableCallBack scsiumscb = new UmsEnableCallBack(scsiPath, method, true);
            mHandler.sendMessage(mHandler.obtainMessage(H_UNMOUNT_PM_UPDATE, scsiumscb));
            // Clear override
            setUmsEnabling(false);
        }
   
        /* hengai */
        if (!enable) {
            oUmsEnabling = false;
            
            doShareUnshareVolume(nPath, method, enable);
            if (doMountVolume(nPath) != StorageResultCode.OperationSucceeded) {
                Slog.e(TAG, "Failed to remount " + nPath +
                        " after disabling share method " + method);
                /*
                 * Even though the mount failed, the unshare didn't so don't indicate an error.
                 * The mountVolume() call will have set the storage state and sent the necessary
                 * broadcasts.
                 */
            }

        }

        /*
         * If we disabled UMS then mount the volume
         */
        if (!enable) {
            oUmsEnabling = false;
            
            if( !Environment.getSdcardStorageState().equals(Environment.MEDIA_REMOVED) ) {
                doShareUnshareVolume(path, method, enable);
                if (doMountVolume(path) != StorageResultCode.OperationSucceeded) {
                    Slog.e(TAG, "Failed to remount " + path +
                            " after disabling share method " + method);
                    /*
                     * Even though the mount failed, the unshare didn't so don't indicate an error.
                     * The mountVolume() call will have set the storage state and sent the necessary
                     * broadcasts.
                     */
                }
            }
        }

        // scsi
        if (!enable) {          
            oUmsEnabling = false;
            if( Environment.getScsiStorageState().equals(Environment.MEDIA_SHARED) ) {
                doShareUnshareVolume(scsiPath, method, enable);
                if (doMountVolume(scsiPath) != StorageResultCode.OperationSucceeded) {
                    Slog.e(TAG, "Failed to remount " + scsiPath +
                            " after disabling share method " + method);
                    /*
                     * Even though the mount failed, the unshare didn't so don't indicate an error.
                     * The mountVolume() call will have set the storage state and sent the necessary
                     * broadcasts.
                     */
                }
            }
        }
    }

    public boolean isUsbMassStorageEnabled() {
        waitForReady();
        return doGetVolumeShared(Environment.getSdcardStorageDirectory().getPath(), "ums");
    }

    
    // scsi
    public boolean isScsiUsbMassStorageEnabled() {
        waitForReady();
        return doGetVolumeShared(Environment.getScsiStorageDirectory().getPath(), "ums");
    }

    /* jmele */
    public boolean isInternalUsbMassStorageEnabled() {
        waitForReady();
        return doGetVolumeShared(Environment.getInternalStorageDirectory().getPath(), "ums");
    }    

    /**
     * @return state of the volume at the specified mount point
     */

    private String mOldSdcardState;
    private String mOldNandState;
    private String mOldScsiState;

    public String getVolumeState(String mountPoint) {
        /*
         * XXX: Until we have multiple volume discovery, just hardwire
         * this to /sdcard
         */

        String ret = "";
         /* hengai */
        if (mountPoint.equals(Environment.getSdcardStorageDirectory().getPath())) {
            if( !mLegacySdcardState.equals(mOldSdcardState) ) {
                Slog.w(TAG, "getVolumeState(" + mountPoint + "): " + mLegacySdcardState);
                mOldSdcardState = mLegacySdcardState;
            }
            
            return ret = mLegacySdcardState;
        } else if (mountPoint.equals(Environment.getScsiStorageDirectory().getPath())) {
            if( !mLegacyScsiState.equals(mOldScsiState) ) {
                Slog.w(TAG, "getVolumeState(" + mountPoint + "): state " + mLegacyScsiState);
                mOldScsiState = mLegacyScsiState;
            }
            
            return ret = mLegacyScsiState;
        } else if (mountPoint.equals(Environment.getNandStorageDirectory().getPath())) {
            if( !mLegacyNandState.equals(mOldNandState) ) {
                Slog.w(TAG, "getVolumeState(" + mountPoint + "): " + mLegacyNandState);
                mOldNandState = mLegacyNandState;
            }
            
            return ret = mLegacyNandState;
        }
            
        return ret;
    }

    public int mountVolume(String path) {
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);

        waitForReady();
        return doMountVolume(path);
    }

    public void unmountVolume(String path, boolean force) {
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();

        String volState = getVolumeState(path);
        if (DEBUG_UNMOUNT) Slog.i(TAG, "==>>>>>> Unmounting " + path + " force = " + force);

        if (Environment.MEDIA_UNMOUNTED.equals(volState) ||
                Environment.MEDIA_REMOVED.equals(volState) ||
                Environment.MEDIA_SHARED.equals(volState) ||
                Environment.MEDIA_UNMOUNTABLE.equals(volState)) {
            // Media already unmounted or cannot be unmounted.
            // TODO return valid return code when adding observer call back.
            return;
        }
        UnmountCallBack ucb = new UnmountCallBack(path, force);
        mHandler.sendMessage(mHandler.obtainMessage(H_INTERNAL_UNMOUNT_PM_UPDATE, ucb));
    }

    public int formatVolume(String path) {
        validatePermission(android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS);
        waitForReady();

        return doFormatVolume(path);
    }

    public int []getStorageUsers(String path) {
        validatePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS);
        waitForReady();
        try {
            String[] r = mConnector.doListCommand(
                    String.format("storage users %s", path),
                            VoldResponseCode.StorageUsersListResult);
            // FMT: <pid> <process name>
            int[] data = new int[r.length];
            for (int i = 0; i < r.length; i++) {
                String []tok = r[i].split(" ");
                try {
                    data[i] = Integer.parseInt(tok[0]);
                } catch (NumberFormatException nfe) {
                    Slog.e(TAG, String.format("Error parsing pid %s", tok[0]));
                    return new int[0];
                }
            }
            return data;
        } catch (NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to retrieve storage users list", e);
            return new int[0];
        }
    }

    private void warnOnNotMounted() {
        if (!Environment.getSdcardStorageState().equals(Environment.MEDIA_MOUNTED)) {
            Slog.w(TAG, "getSecureContainerList() called when external storage not mounted");
        }
    }
    /*hengai*/
    private void warnOnInternalNotMounted() {
        if (!Environment.getInternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                Slog.w(TAG, "getSecureContainerList() called when internal storage not mounted");
        }
    }    
    public String[] getSecureContainerList() {
        validatePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();
        warnOnInternalNotMounted();
        try {
            return mConnector.doListCommand("asec list", VoldResponseCode.AsecListResult);
        } catch (NativeDaemonConnectorException e) {
            return new String[0];
        }
    }

    public int createSecureContainer(String id, int sizeMb, String fstype,
                                    String key, int ownerUid) {
        validatePermission(android.Manifest.permission.ASEC_CREATE);
        waitForReady();
        warnOnNotMounted();
        warnOnInternalNotMounted();
        int rc = StorageResultCode.OperationSucceeded;
        String cmd = String.format("asec create %s %d %s %s %d", id, sizeMb, fstype, key, ownerUid);
        try {
            mConnector.doCommand(cmd);
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                mAsecMountSet.add(id);
            }
        }
        return rc;
    }

    public int finalizeSecureContainer(String id) {
        validatePermission(android.Manifest.permission.ASEC_CREATE);
        warnOnNotMounted();
        warnOnInternalNotMounted();
        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.doCommand(String.format("asec finalize %s", id));
            /*
             * Finalization does a remount, so no need
             * to update mAsecMountSet
             */
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }
        return rc;
    }

    public int destroySecureContainer(String id, boolean force) {
        validatePermission(android.Manifest.permission.ASEC_DESTROY);
        waitForReady();
        warnOnNotMounted();
        warnOnInternalNotMounted();
        int rc = StorageResultCode.OperationSucceeded;
        try {
            mConnector.doCommand(String.format("asec destroy %s%s", id, (force ? " force" : "")));
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageBusy) {
                rc = StorageResultCode.OperationFailedStorageBusy;
            } else {
                rc = StorageResultCode.OperationFailedInternalError;
            }
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                if (mAsecMountSet.contains(id)) {
                    mAsecMountSet.remove(id);
                }
            }
        }

        return rc;
    }
   
    public int mountSecureContainer(String id, String key, int ownerUid) {
        validatePermission(android.Manifest.permission.ASEC_MOUNT_UNMOUNT);
        waitForReady();
        warnOnNotMounted();
        warnOnInternalNotMounted();
        synchronized (mAsecMountSet) {
            if (mAsecMountSet.contains(id)) {
                return StorageResultCode.OperationFailedStorageMounted;
            }
        }

        int rc = StorageResultCode.OperationSucceeded;
        String cmd = String.format("asec mount %s %s %d", id, key, ownerUid);
        try {
            mConnector.doCommand(cmd);
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code != VoldResponseCode.OpFailedStorageBusy) {
                rc = StorageResultCode.OperationFailedInternalError;
            }
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                mAsecMountSet.add(id);
            }
        }
        return rc;
    }

    public int unmountSecureContainer(String id, boolean force) {
        validatePermission(android.Manifest.permission.ASEC_MOUNT_UNMOUNT);
        waitForReady();
        warnOnNotMounted();
        warnOnInternalNotMounted();
        synchronized (mAsecMountSet) {
            if (!mAsecMountSet.contains(id)) {
                return StorageResultCode.OperationFailedStorageNotMounted;
            }
         }

        int rc = StorageResultCode.OperationSucceeded;
        String cmd = String.format("asec unmount %s%s", id, (force ? " force" : ""));
        try {
            mConnector.doCommand(cmd);
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageBusy) {
                rc = StorageResultCode.OperationFailedStorageBusy;
            } else {
                rc = StorageResultCode.OperationFailedInternalError;
            }
        }

        if (rc == StorageResultCode.OperationSucceeded) {
            synchronized (mAsecMountSet) {
                mAsecMountSet.remove(id);
            }
        }
        return rc;
    }

    public boolean isSecureContainerMounted(String id) {
        validatePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();
        warnOnInternalNotMounted();
        synchronized (mAsecMountSet) {
            return mAsecMountSet.contains(id);
        }
    }

    public int renameSecureContainer(String oldId, String newId) {
        validatePermission(android.Manifest.permission.ASEC_RENAME);
        waitForReady();
        warnOnNotMounted();
        warnOnInternalNotMounted();
        synchronized (mAsecMountSet) {
            /*
             * Because a mounted container has active internal state which cannot be 
             * changed while active, we must ensure both ids are not currently mounted.
             */
            if (mAsecMountSet.contains(oldId) || mAsecMountSet.contains(newId)) {
                return StorageResultCode.OperationFailedStorageMounted;
            }
        }

        int rc = StorageResultCode.OperationSucceeded;
        String cmd = String.format("asec rename %s %s", oldId, newId);
        try {
            mConnector.doCommand(cmd);
        } catch (NativeDaemonConnectorException e) {
            rc = StorageResultCode.OperationFailedInternalError;
        }

        return rc;
    }

    public String getSecureContainerPath(String id) {
        validatePermission(android.Manifest.permission.ASEC_ACCESS);
        waitForReady();
        warnOnNotMounted();
        warnOnInternalNotMounted();
        try {
            ArrayList<String> rsp = mConnector.doCommand(String.format("asec path %s", id));
            String []tok = rsp.get(0).split(" ");
            int code = Integer.parseInt(tok[0]);
            if (code != VoldResponseCode.AsecPathResult) {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
            return tok[1];
        } catch (NativeDaemonConnectorException e) {
            int code = e.getCode();
            if (code == VoldResponseCode.OpFailedStorageNotFound) {
                throw new IllegalArgumentException(String.format("Container '%s' not found", id));
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
    }

    public void finishMediaUpdate() {
        if(LOCAL_LOGD) Slog.w(TAG, "finishMediaUpdate::H_UNMOUNT_PM_DONE");        
        mHandler.sendEmptyMessage(H_UNMOUNT_PM_DONE);
    }
    /* hengai */
    public void finishInternalMediaUpdate() {
        if(LOCAL_LOGD) Slog.w(TAG, "finishInternalMediaUpdate::H_INTERNAL_UNMOUNT_PM_DONE");        
        mHandler.sendEmptyMessage(H_INTERNAL_UNMOUNT_PM_DONE);
    }    
}

