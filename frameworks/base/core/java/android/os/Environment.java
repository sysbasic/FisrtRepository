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

package android.os;

import static android.provider.Settings.System.EXTERNAL_STORAGE_TYPE;

import java.io.File;

import android.os.storage.IMountService;
import android.provider.MediaStore;
import android.os.SystemProperties;


/**
 * Provides access to environment variables.
 */
public class Environment {

    private static final File ROOT_DIRECTORY
            = getDirectory("ANDROID_ROOT", "/system");

    private static IMountService mMntSvc = null;

    /* hengai */
    private static IMountService nMntSvc = null;
    /**
     * Gets the Android root directory.
     */
    public static File getRootDirectory() {
        return ROOT_DIRECTORY;
    }

    private static final File DATA_DIRECTORY
            = getDirectory("ANDROID_DATA", "/data");

    private static final File EXTERNAL_STORAGE_DIRECTORY
            = getDirectory("EXTERNAL_STORAGE", "/mnt/nand");

    private static final File SDCARD_STORAGE_DIRECTORY
            = getDirectory("SDCARD_STORAGE", "/mnt/sdcard");

//VIEW_NAND
    private static final File NAND_STORAGE_DIRECTORY
            = getDirectory("NAND_STORAGE", "/mnt/nand");
//VIEW_SCSI
    private static final File SCSI_STORAGE_DIRECTORY
            = getDirectory("SCSI_STORAGE", "/mnt/scsi");

    private static final File EXTERNAL_STORAGE_ANDROID_DATA_DIRECTORY
            = new File (new File(getDirectory("EXTERNAL_STORAGE", "/mnt/nand"),
                    "Android"), "data");

    private static final File EXTERNAL_STORAGE_ANDROID_MEDIA_DIRECTORY
            = new File (new File(getDirectory("EXTERNAL_STORAGE", "/mnt/nand"),
                    "Android"), "media");

    private static final File DOWNLOAD_CACHE_DIRECTORY
            = getDirectory("DOWNLOAD_CACHE", "/cache");

    /**
     * Gets the Android data directory.
     */
    public static File getDataDirectory() {
        return DATA_DIRECTORY;
    }

    /**
     * Gets the Android external storage directory.  This directory may not
     * currently be accessible if it has been mounted by the user on their
     * computer, has been removed from the device, or some other problem has
     * happened.  You can determine its current state with
     * {@link #getExternalStorageState()}.
     * 
     * <p>Applications should not directly use this top-level directory, in
     * order to avoid polluting the user's root namespace.  Any files that are
     * private to the application should be placed in a directory returned
     * by {@link android.content.Context#getExternalFilesDir
     * Context.getExternalFilesDir}, which the system will take care of deleting
     * if the application is uninstalled.  Other shared files should be placed
     * in one of the directories returned by
     * {@link #getExternalStoragePublicDirectory}.
     * 
     * <p>Here is an example of typical code to monitor the state of
     * external storage:</p>
     * 
     * {@sample development/samples/ApiDemos/src/com/example/android/apis/content/ExternalStorage.java
     * monitor_storage}
     */
    public static File getSdcardStorageDirectory() {
        return SDCARD_STORAGE_DIRECTORY;
    }

    public static File getExternalStorageDirectory() {
        
        if (SystemProperties.getInt("persist.sys.externalstorage.cfg", 1) == 2) {
            return getNandStorageDirectory();
        }
        /*
        if( isMounted(MediaStore.NAND_VOLUME) ) {
            return getNandStorageDirectory();
        } else if ( isMounted(MediaStore.SDCARD_VOLUME) ) {
            return getSdcardStorageDirectory();
        } else if ( isMounted(MediaStore.SCSI_VOLUME) ) {
            return getScsiStorageDirectory();
        }
        return getNandStorageDirectory();
        //return EXTERNAL_STORAGE_DIRECTORY;
        */
        return getSdcardStorageDirectory();
    }

    public static File getNandStorageDirectory() {
        return NAND_STORAGE_DIRECTORY;
    }
    /* hengai  */ 
	/** {@hide} */
    public static File getInternalStorageDirectory() {
        return NAND_STORAGE_DIRECTORY;
    }

    public static File getScsiStorageDirectory() {
        return SCSI_STORAGE_DIRECTORY;
    }

    public static File getStorageDirectory(String volumeName) {
        if(MediaStore.SDCARD_VOLUME.equals(volumeName)) {
            return getSdcardStorageDirectory();
        } else if(MediaStore.NAND_VOLUME.equals(volumeName)) {
            return getNandStorageDirectory();
        } else if(MediaStore.SCSI_VOLUME.equals(volumeName)) { //VIEW_SCSI
            return getScsiStorageDirectory();
        } else {
            return null;
        }
    }

    /**
     * Standard directory in which to place any audio files that should be
     * in the regular list of music for the user.
     * This may be combined with
     * {@link #DIRECTORY_PODCASTS}, {@link #DIRECTORY_NOTIFICATIONS},
     * {@link #DIRECTORY_ALARMS}, and {@link #DIRECTORY_RINGTONES} as a series
     * of directories to categories a particular audio file as more than one
     * type.
     */
    public static String DIRECTORY_MUSIC = "Music";
    
    /**
     * Standard directory in which to place any audio files that should be
     * in the list of podcasts that the user can select (not as regular
     * music).
     * This may be combined with {@link #DIRECTORY_MUSIC},
     * {@link #DIRECTORY_NOTIFICATIONS},
     * {@link #DIRECTORY_ALARMS}, and {@link #DIRECTORY_RINGTONES} as a series
     * of directories to categories a particular audio file as more than one
     * type.
     */
    public static String DIRECTORY_PODCASTS = "Podcasts";
    
    /**
     * Standard directory in which to place any audio files that should be
     * in the list of ringtones that the user can select (not as regular
     * music).
     * This may be combined with {@link #DIRECTORY_MUSIC},
     * {@link #DIRECTORY_PODCASTS}, {@link #DIRECTORY_NOTIFICATIONS}, and
     * {@link #DIRECTORY_ALARMS} as a series
     * of directories to categories a particular audio file as more than one
     * type.
     */
    public static String DIRECTORY_RINGTONES = "Ringtones";
    
    /**
     * Standard directory in which to place any audio files that should be
     * in the list of alarms that the user can select (not as regular
     * music).
     * This may be combined with {@link #DIRECTORY_MUSIC},
     * {@link #DIRECTORY_PODCASTS}, {@link #DIRECTORY_NOTIFICATIONS},
     * and {@link #DIRECTORY_RINGTONES} as a series
     * of directories to categories a particular audio file as more than one
     * type.
     */
    public static String DIRECTORY_ALARMS = "Alarms";
    
    /**
     * Standard directory in which to place any audio files that should be
     * in the list of notifications that the user can select (not as regular
     * music).
     * This may be combined with {@link #DIRECTORY_MUSIC},
     * {@link #DIRECTORY_PODCASTS},
     * {@link #DIRECTORY_ALARMS}, and {@link #DIRECTORY_RINGTONES} as a series
     * of directories to categories a particular audio file as more than one
     * type.
     */
    public static String DIRECTORY_NOTIFICATIONS = "Notifications";
    
    /**
     * Standard directory in which to place pictures that are available to
     * the user.  Note that this is primarily a convention for the top-level
     * public directory, as the media scanner will find and collect pictures
     * in any directory.
     */
    public static String DIRECTORY_PICTURES = "Pictures";
    
    /**
     * Standard directory in which to place movies that are available to
     * the user.  Note that this is primarily a convention for the top-level
     * public directory, as the media scanner will find and collect movies
     * in any directory.
     */
    public static String DIRECTORY_MOVIES = "Movies";
    
    /**
     * Standard directory in which to place files that have been downloaded by
     * the user.  Note that this is primarily a convention for the top-level
     * public directory, you are free to download files anywhere in your own
     * private directories.  Also note that though the constant here is
     * named DIRECTORY_DOWNLOADS (plural), the actual file name is non-plural for
     * backwards compatibility reasons.
     */
    public static String DIRECTORY_DOWNLOADS = "Download";
    
    /**
     * The traditional location for pictures and videos when mounting the
     * device as a camera.  Note that this is primarily a convention for the
     * top-level public directory, as this convention makes no sense elsewhere.
     */
    public static String DIRECTORY_DCIM = "DCIM";
    
    /**
     * Get a top-level public external storage directory for placing files of
     * a particular type.  This is where the user will typically place and
     * manage their own files, so you should be careful about what you put here
     * to ensure you don't erase their files or get in the way of their own
     * organization.
     * 
     * <p>Here is an example of typical code to manipulate a picture on
     * the public external storage:</p>
     * 
     * {@sample development/samples/ApiDemos/src/com/example/android/apis/content/ExternalStorage.java
     * public_picture}
     * 
     * @param type The type of storage directory to return.  Should be one of
     * {@link #DIRECTORY_MUSIC}, {@link #DIRECTORY_PODCASTS},
     * {@link #DIRECTORY_RINGTONES}, {@link #DIRECTORY_ALARMS},
     * {@link #DIRECTORY_NOTIFICATIONS}, {@link #DIRECTORY_PICTURES},
     * {@link #DIRECTORY_MOVIES}, {@link #DIRECTORY_DOWNLOADS}, or
     * {@link #DIRECTORY_DCIM}.  May not be null.
     * 
     * @return Returns the File path for the directory.  Note that this
     * directory may not yet exist, so you must make sure it exists before
     * using it such as with {@link File#mkdirs File.mkdirs()}.
     */
    public static File getExternalStoragePublicDirectory(String type) {
        return new File(getExternalStorageDirectory(), type);
    }

    /**
     * Returns the path for android-specific data on the SD card.
     * @hide
     */
    public static File getExternalStorageAndroidDataDir() {
        return EXTERNAL_STORAGE_ANDROID_DATA_DIRECTORY;
    }
    
    /**
     * Generates the raw path to an application's data
     * @hide
     */
    public static File getExternalStorageAppDataDirectory(String packageName) {
        return new File(EXTERNAL_STORAGE_ANDROID_DATA_DIRECTORY, packageName);
    }
    
    /**
     * Generates the raw path to an application's media
     * @hide
     */
    public static File getExternalStorageAppMediaDirectory(String packageName) {
        return new File(EXTERNAL_STORAGE_ANDROID_MEDIA_DIRECTORY, packageName);
    }
    
    /**
     * Generates the path to an application's files.
     * @hide
     */
    public static File getExternalStorageAppFilesDirectory(String packageName) {
        return new File(new File(EXTERNAL_STORAGE_ANDROID_DATA_DIRECTORY,
                packageName), "files");
    }
    
    /**
     * Generates the path to an application's cache.
     * @hide
     */
    public static File getExternalStorageAppCacheDirectory(String packageName) {
        return new File(new File(EXTERNAL_STORAGE_ANDROID_DATA_DIRECTORY,
                packageName), "cache");
    }
    
    /**
     * Gets the Android Download/Cache content directory.
     */
    public static File getDownloadCacheDirectory() {
        return DOWNLOAD_CACHE_DIRECTORY;
    }

    /**
     * getExternalStorageState() returns MEDIA_REMOVED if the media is not present. 
     */
    public static final String MEDIA_REMOVED = "removed";
     
    /**
     * getExternalStorageState() returns MEDIA_UNMOUNTED if the media is present
     * but not mounted. 
     */
    public static final String MEDIA_UNMOUNTED = "unmounted";

    /**
     * getExternalStorageState() returns MEDIA_UNMOUNTING if the media is present
     * and being unmounted
     */
    public static final String MEDIA_UNMOUNTING = "unmounting";

    /**
     * getExternalStorageState() returns MEDIA_CHECKING if the media is present
     * and being disk-checked
     */
    public static final String MEDIA_CHECKING = "checking";

    /**
     * getExternalStorageState() returns MEDIA_NOFS if the media is present
     * but is blank or is using an unsupported filesystem
     */
    public static final String MEDIA_NOFS = "nofs";

    /**
     * getExternalStorageState() returns MEDIA_MOUNTED if the media is present
     * and mounted at its mount point with read/write access. 
     */
    public static final String MEDIA_MOUNTED = "mounted";

    /**
     * getExternalStorageState() returns MEDIA_MOUNTED_READ_ONLY if the media is present
     * and mounted at its mount point with read only access. 
     */
    public static final String MEDIA_MOUNTED_READ_ONLY = "mounted_ro";

    /**
     * getExternalStorageState() returns MEDIA_SHARED if the media is present
     * not mounted, and shared via USB mass storage. 
     */
    public static final String MEDIA_SHARED = "shared";

    /**
     * getExternalStorageState() returns MEDIA_BAD_REMOVAL if the media was
     * removed before it was unmounted. 
     */
    public static final String MEDIA_BAD_REMOVAL = "bad_removal";

    /**
     * getExternalStorageState() returns MEDIA_UNMOUNTABLE if the media is present
     * but cannot be mounted.  Typically this happens if the file system on the
     * media is corrupted. 
     */
    public static final String MEDIA_UNMOUNTABLE = "unmountable";

    /**
     * Gets the current state of the external storage device.
     * Note: This call should be deprecated as it doesn't support
     * multiple volumes.
     * 
     * <p>See {@link #getExternalStorageDirectory()} for an example of its use.
     */
    public static String getExternalStorageState() {
        if (SystemProperties.getInt("persist.sys.externalstorage.cfg", 2) == 2) {
            return getNandStorageState();
        }
        return getSdcardStorageState();
        /*
        String state;
        
        state = getNandStorageState();
        if( Environment.MEDIA_MOUNTED.equals(state) ||
            Environment.MEDIA_MOUNTED_READ_ONLY.equals(state) ) {
            return state;
        }        

        state = getSdcardStorageState();
        if( Environment.MEDIA_MOUNTED.equals(state) ||
            Environment.MEDIA_MOUNTED_READ_ONLY.equals(state) ) {
            return state;
        }        
        state = getScsiStorageState();

        if( Environment.MEDIA_MOUNTED.equals(state) ||
            Environment.MEDIA_MOUNTED_READ_ONLY.equals(state) ) {
            return state;
        }        

        return Environment.MEDIA_REMOVED;
        */
        /*    
        try {
            if (mMntSvc == null) {
                mMntSvc = IMountService.Stub.asInterface(ServiceManager
                                                         .getService("mount"));
            }
            return mMntSvc.getVolumeState(getExternalStorageDirectory().toString());
        } catch (Exception rex) {
            return Environment.MEDIA_REMOVED;
        }
        */
    }

    static File getDirectory(String variableName, String defaultPath) {
        String path = System.getenv(variableName);
        return path == null ? new File(defaultPath) : new File(path);
    }

    /**
     * Gets the current state of the internal storage device.
     * 
     * <p>See {@link #getExternalStorageDirectory()} for an example of its use.
     */
    public static String getInternalStorageState() {
        try {
            if (nMntSvc == null) {
                nMntSvc = IMountService.Stub.asInterface(ServiceManager
                                                         .getService("mount"));
            }
            return nMntSvc.getVolumeState(getInternalStorageDirectory().toString());
        } catch (Exception rex) {
            return Environment.MEDIA_REMOVED;
        }
    } 

    public static boolean isUnifiedDatabaseSupport()
    {
        return true; 
    }

    public static String getSdcardStorageState() {
        try {
            if (mMntSvc == null) {
                mMntSvc = IMountService.Stub.asInterface(ServiceManager
                                                         .getService("mount"));
            }
            return mMntSvc.getVolumeState(getSdcardStorageDirectory().toString()); 
        } catch (Exception rex) {
            return Environment.MEDIA_REMOVED;
        }
    }

    public static String getNandStorageState() {
        try {
            if (mMntSvc == null) {
                mMntSvc = IMountService.Stub.asInterface(ServiceManager
                                                         .getService("mount"));
            }
            return mMntSvc.getVolumeState(getNandStorageDirectory().toString());
        } catch (Exception rex) {
            return Environment.MEDIA_REMOVED;
        }
    }

    public static String getScsiStorageState() {
        try {
            if (mMntSvc == null) {
                mMntSvc = IMountService.Stub.asInterface(ServiceManager
                                                         .getService("mount"));
            }
            return mMntSvc.getVolumeState(getScsiStorageDirectory().toString());
        } catch (Exception rex) {
            return Environment.MEDIA_REMOVED;
        }
    }

    public static String getStorageState(String volumeName) {
        if(MediaStore.SDCARD_VOLUME.equals(volumeName)) {
            return getSdcardStorageState();
        } else if(MediaStore.NAND_VOLUME.equals(volumeName)) {
            return getNandStorageState();
        } else if(MediaStore.SCSI_VOLUME.equals(volumeName)) { //VIEW_SCSI
            return getScsiStorageState();
        } else {
            return MEDIA_MOUNTED;
        }
    }

    public static boolean isMounted(String volumeName) {
        String state = Environment.getStorageState(volumeName);

        if( Environment.MEDIA_MOUNTED.equals(state) ||
            Environment.MEDIA_MOUNTED_READ_ONLY.equals(state) ) {
            return true;
        }

        return false;
    }

}
