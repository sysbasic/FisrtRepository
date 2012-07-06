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

package android.media;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.IContentProvider;
import android.database.Cursor;
import android.database.SQLException;
import android.database.DatabaseUtils;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;
import android.provider.MediaStore.Audio.Genres;
import android.provider.MediaStore.Audio.Playlists;
import android.sax.Element;
import android.sax.ElementListener;
import android.sax.RootElement;
import android.text.TextUtils;
import android.util.Config;
import android.util.Log;
import android.util.Xml;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

/**
 * Internal service helper that no-one should use directly.
 *
 * The way the scan currently works is:
 * - The Java MediaScannerService creates a MediaScanner (this class), and calls
 *   MediaScanner.scanDirectories on it.
 * - scanDirectories() calls the native processDirectory() for each of the specified directories.
 * - the processDirectory() JNI method wraps the provided mediascanner client in a native
 *   'MyMediaScannerClient' class, then calls processDirectory() on the native MediaScanner
 *   object (which got created when the Java MediaScanner was created).
 * - native MediaScanner.processDirectory() (currently part of opencore) calls
 *   doProcessDirectory(), which recurses over the folder, and calls
 *   native MyMediaScannerClient.scanFile() for every file whose extension matches.
 * - native MyMediaScannerClient.scanFile() calls back on Java MediaScannerClient.scanFile,
 *   which calls doScanFile, which after some setup calls back down to native code, calling
 *   MediaScanner.processFile().
 * - MediaScanner.processFile() calls one of several methods, depending on the type of the
 *   file: parseMP3, parseMP4, parseMidi, parseOgg or parseWMA.
 * - each of these methods gets metadata key/value pairs from the file, and repeatedly
 *   calls native MyMediaScannerClient.handleStringTag, which calls back up to its Java
 *   counterparts in this file.
 * - Java handleStringTag() gathers the key/value pairs that it's interested in.
 * - once processFile returns and we're back in Java code in doScanFile(), it calls
 *   Java MyMediaScannerClient.endFile(), which takes all the data that's been
 *   gathered and inserts an entry in to the database.
 *
 * In summary:
 * Java MediaScannerService calls
 * Java MediaScanner scanDirectories, which calls
 * Java MediaScanner processDirectory (native method), which calls
 * native MediaScanner processDirectory, which calls
 * native MyMediaScannerClient scanFile, which calls
 * Java MyMediaScannerClient scanFile, which calls
 * Java MediaScannerClient doScanFile, which calls
 * Java MediaScanner processFile (native method), which calls
 * native MediaScanner processFile, which calls
 * native parseMP3, parseMP4, parseMidi, parseOgg or parseWMA, which calls
 * native MyMediaScanner handleStringTag, which calls
 * Java MyMediaScanner handleStringTag.
 * Once MediaScanner processFile returns, an entry is inserted in to the database.
 *
 * {@hide}
 */
public class MediaScanner
{
    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    private final static String TAG = "MediaScanner";

    private static final String[] AUDIO_PROJECTION = new String[] {
            Audio.Media._ID, // 0
            Audio.Media.DATA, // 1
            Audio.Media.DATE_MODIFIED, // 2
    };

    private static final String[] UNIFIED_AUDIO_PROJECTION = new String[] {
            Audio.Media._ID, // 0
            Audio.Media.DATA, // 1
            Audio.Media.DATE_MODIFIED, // 2
            Audio.Media.ALBUM_ID,
            Audio.Media.IS_ALARM,
            Audio.Media.IS_RINGTONE,
            Audio.Media.TRACK,
            Audio.Media.IS_PODCAST,
            Audio.Media.IS_MUSIC,
            Audio.Media.COMPOSER,
            Audio.Media.DURATION,
            "title",
            Audio.Media.IS_NOTIFICATION,
            "mime_type",
            Audio.Media.YEAR,
            "_size",
            Audio.Media.ARTIST,
            "date_added",
            "date_modified"
    };

    private static final int ID_AUDIO_COLUMN_INDEX = 0;
    private static final int PATH_AUDIO_COLUMN_INDEX = 1;
    private static final int DATE_MODIFIED_AUDIO_COLUMN_INDEX = 2;
    private static final int ID_ALBUM_COLUMN_INDEX = 3;

    private static final String[] VIDEO_PROJECTION = new String[] {
            Video.Media._ID, // 0
            Video.Media.DATA, // 1
            Video.Media.DATE_MODIFIED, // 2
            Video.Media.MINI_THUMB_MAGIC, // 3
    };

    private static final int ID_VIDEO_COLUMN_INDEX = 0;
    private static final int PATH_VIDEO_COLUMN_INDEX = 1;
    private static final int DATE_MODIFIED_VIDEO_COLUMN_INDEX = 2;
    private static final int VIDEO_MINI_THUMB_MAGIC_COLUMN_INDEX = 3;
    
    private static final String[] IMAGES_PROJECTION = new String[] {
            Images.Media._ID, // 0
            Images.Media.DATA, // 1
            Images.Media.DATE_MODIFIED, // 2
            Images.Media.MINI_THUMB_MAGIC, // 3
    };

    private static final int ID_IMAGES_COLUMN_INDEX = 0;
    private static final int PATH_IMAGES_COLUMN_INDEX = 1;
    private static final int DATE_MODIFIED_IMAGES_COLUMN_INDEX = 2;
    private static final int IMAGE_MINI_THUMB_MAGIC_COLUMN_INDEX = 3;

    private static final String[] PLAYLISTS_PROJECTION = new String[] {
            Audio.Playlists._ID, // 0
            Audio.Playlists.DATA, // 1
            Audio.Playlists.DATE_MODIFIED, // 2
    };

    private static final String[] PLAYLIST_MEMBERS_PROJECTION = new String[] {
            Audio.Playlists.Members.PLAYLIST_ID, // 0
     };

    private static final int ID_PLAYLISTS_COLUMN_INDEX = 0;
    private static final int PATH_PLAYLISTS_COLUMN_INDEX = 1;
    private static final int DATE_MODIFIED_PLAYLISTS_COLUMN_INDEX = 2;

    private static final String[] GENRE_LOOKUP_PROJECTION = new String[] {
            Audio.Genres._ID, // 0
            Audio.Genres.NAME, // 1
    };

    private static final String RINGTONES_DIR = "/ringtones/";
    private static final String NOTIFICATIONS_DIR = "/notifications/";
    private static final String ALARMS_DIR = "/alarms/";
    private static final String MUSIC_DIR = "/music/";
    private static final String PODCAST_DIR = "/podcasts/";

    private int mDBInsertTime = 0;
    private int mDBDeleteTime = 0;
    private int mProcessFileTime = 0;
    private int mInsertCnt = 0;
    private int mDeleteCnt = 0;
    private int mUnifiedDBCnt = 0;
    private int mMusicAddCount = 0;
    
    private static final String[] ID3_GENRES = {
        // ID3v1 Genres
        "Blues",
        "Classic Rock",
        "Country",
        "Dance",
        "Disco",
        "Funk",
        "Grunge",
        "Hip-Hop",
        "Jazz",
        "Metal",
        "New Age",
        "Oldies",
        "Other",
        "Pop",
        "R&B",
        "Rap",
        "Reggae",
        "Rock",
        "Techno",
        "Industrial",
        "Alternative",
        "Ska",
        "Death Metal",
        "Pranks",
        "Soundtrack",
        "Euro-Techno",
        "Ambient",
        "Trip-Hop",
        "Vocal",
        "Jazz+Funk",
        "Fusion",
        "Trance",
        "Classical",
        "Instrumental",
        "Acid",
        "House",
        "Game",
        "Sound Clip",
        "Gospel",
        "Noise",
        "AlternRock",
        "Bass",
        "Soul",
        "Punk",
        "Space",
        "Meditative",
        "Instrumental Pop",
        "Instrumental Rock",
        "Ethnic",
        "Gothic",
        "Darkwave",
        "Techno-Industrial",
        "Electronic",
        "Pop-Folk",
        "Eurodance",
        "Dream",
        "Southern Rock",
        "Comedy",
        "Cult",
        "Gangsta",
        "Top 40",
        "Christian Rap",
        "Pop/Funk",
        "Jungle",
        "Native American",
        "Cabaret",
        "New Wave",
        "Psychadelic",
        "Rave",
        "Showtunes",
        "Trailer",
        "Lo-Fi",
        "Tribal",
        "Acid Punk",
        "Acid Jazz",
        "Polka",
        "Retro",
        "Musical",
        "Rock & Roll",
        "Hard Rock",
        // The following genres are Winamp extensions
        "Folk",
        "Folk-Rock",
        "National Folk",
        "Swing",
        "Fast Fusion",
        "Bebob",
        "Latin",
        "Revival",
        "Celtic",
        "Bluegrass",
        "Avantgarde",
        "Gothic Rock",
        "Progressive Rock",
        "Psychedelic Rock",
        "Symphonic Rock",
        "Slow Rock",
        "Big Band",
        "Chorus",
        "Easy Listening",
        "Acoustic",
        "Humour",
        "Speech",
        "Chanson",
        "Opera",
        "Chamber Music",
        "Sonata",
        "Symphony",
        "Booty Bass",
        "Primus",
        "Porn Groove",
        "Satire",
        "Slow Jam",
        "Club",
        "Tango",
        "Samba",
        "Folklore",
        "Ballad",
        "Power Ballad",
        "Rhythmic Soul",
        "Freestyle",
        "Duet",
        "Punk Rock",
        "Drum Solo",
        "A capella",
        "Euro-House",
        "Dance Hall"
    };

    private int mNativeContext;
    private Context mContext;
    private IContentProvider mMediaProvider;
    private Uri mAudioUri;
    private Uri mVideoUri;
    private Uri mImagesUri;
    private Uri mThumbsUri;
    private Uri mGenresUri;
    private Uri mPlaylistsUri;
    private Uri mImageThumbReqUri;
    private Uri mVideoThumbReqUri;
    private Uri mAlbumArtReqUri;
    
    private String mVolumeName;

    private boolean mProcessPlaylists, mProcessGenres;

    // used when scanning the image database so we know whether we have to prune
    // old thumbnail files
    private int mOriginalCount;
    /** Whether the scanner has set a default sound for the ringer ringtone. */
    private boolean mDefaultRingtoneSet;
    /** Whether the scanner has set a default sound for the notification ringtone. */
    private boolean mDefaultNotificationSet;
    /** Whether the scanner has set a default sound for the alarm ringtone. */
    private boolean mDefaultAlarmSet;
    /** The filename for the default sound for the ringer ringtone. */
    private String mDefaultRingtoneFilename;
    /** The filename for the default sound for the notification ringtone. */
    private String mDefaultNotificationFilename;
    /** The filename for the default sound for the alarm ringtone. */
    private String mDefaultAlarmAlertFilename;
    /**
     * The prefix for system properties that define the default sound for
     * ringtones. Concatenate the name of the setting from Settings
     * to get the full system property.
     */
    private static final String DEFAULT_RINGTONE_PROPERTY_PREFIX = "ro.config.";

    // set to true if file path comparisons should be case insensitive.
    // this should be set when scanning files on a case insensitive file system.
    private boolean mCaseInsensitivePaths;

    private BitmapFactory.Options mBitmapOptions = new BitmapFactory.Options();

    private PowerManager.WakeLock mWakeLock;

    private static class FileCacheEntry {
        Uri mTableUri;
        long mRowId;
        String mPath;
        long mLastModified;
        boolean mSeenInFileSystem;
        boolean mLastModifiedChanged;
        long mMiniThumbMagic;

        FileCacheEntry(Uri tableUri, long rowId, String path, long lastModified) {
            mTableUri = tableUri;
            mRowId = rowId;
            mPath = path;
            mLastModified = lastModified;
            mSeenInFileSystem = false;
            mLastModifiedChanged = false;
            mMiniThumbMagic = 0;
        }

        FileCacheEntry(Uri tableUri, long rowId, String path, long lastModified, long miniThumbMagic) {
            mTableUri = tableUri;
            mRowId = rowId;
            mPath = path;
            mLastModified = lastModified;
            mSeenInFileSystem = false;
            mLastModifiedChanged = false;
            mMiniThumbMagic = miniThumbMagic;
        }
        

        @Override
        public String toString() {
            return mPath;
        }
    }

    // hashes file path to FileCacheEntry.
    // path should be lower case if mCaseInsensitivePaths is true
    private HashMap<String, FileCacheEntry> mFileCache;

    private ArrayList<FileCacheEntry> mPlayLists;
    private HashMap<String, Uri> mGenreCache;


    public MediaScanner(Context c) {
        native_setup();
        mContext = c;
        mBitmapOptions.inSampleSize = 1;
        mBitmapOptions.inJustDecodeBounds = true;

        setDefaultRingtoneFileNames();

        PowerManager pm = (PowerManager)mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
    }

    private void setDefaultRingtoneFileNames() {
        mDefaultRingtoneFilename = SystemProperties.get(DEFAULT_RINGTONE_PROPERTY_PREFIX
                + Settings.System.RINGTONE);
        mDefaultNotificationFilename = SystemProperties.get(DEFAULT_RINGTONE_PROPERTY_PREFIX
                + Settings.System.NOTIFICATION_SOUND);
        mDefaultAlarmAlertFilename = SystemProperties.get(DEFAULT_RINGTONE_PROPERTY_PREFIX
                + Settings.System.ALARM_ALERT);
    }

    private boolean isAvailableStorage() {
        String state = Environment.MEDIA_MOUNTED;

        if( mVolumeName.equals(MediaStore.NAND_VOLUME) ) { 
            state = Environment.getNandStorageState();
        } else if( mVolumeName.equals(MediaStore.SDCARD_VOLUME) ) {
            state = Environment.getSdcardStorageState();        
        } else if( mVolumeName.equals(MediaStore.SCSI_VOLUME) ) { 
            state = Environment.getScsiStorageState();
        }

        if ( Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state) )
            return true;

       return false;
    }

    private MyMediaScannerBulkInsert mBulkInsert = new MyMediaScannerBulkInsert();

    private class MyMediaScannerBulkInsert {

        private List<ContentValues> mAudioCVItems = new ArrayList<ContentValues>();
        private List<ContentValues> mVideoCVItems = new ArrayList<ContentValues>();
        private List<ContentValues> mImagesCVItems = new ArrayList<ContentValues>();
        private List<ContentValues> mThumbsCVItems = new ArrayList<ContentValues>();        
        private List<ContentValues> mGenresCVItems = new ArrayList<ContentValues>();

        private List<ContentValues> mImageThumbReqCVItems = new ArrayList<ContentValues>();
        private List<ContentValues> mVideoThumbReqCVItems = new ArrayList<ContentValues>();
        private List<ContentValues> mAlbumartReqCVItems = new ArrayList<ContentValues>();

        public void insert(Uri url, ContentValues initialValues) throws RemoteException  {
            if( url.equals(mAudioUri) ) {
                mAudioCVItems.add(new ContentValues(initialValues));

                if( Environment.isUnifiedDatabaseSupport() ) {
                    if( mAudioCVItems.size() >= 1000 ) {
                        flush(url, mAudioCVItems);
                    }
                } else {
                    if( mAudioCVItems.size() >= 10 ) {
                        flush(url, mAudioCVItems);
                    }
                }
            } else if( url.equals(mVideoUri) ) {
                mVideoCVItems.add(new ContentValues(initialValues));

                if( mVideoCVItems.size() >= 1000 ) {
                    flush(url, mVideoCVItems);
                }
                
            } else if( url.equals(mImagesUri) ) {
                mImagesCVItems.add(new ContentValues(initialValues));
                
                if( mImagesCVItems.size() >= 1000 ) {
                    flush(url, mImagesCVItems);
                }

            } else if( url.equals(mThumbsUri) ) {
                mThumbsCVItems.add(new ContentValues(initialValues));

                if( mThumbsCVItems.size() >= 1000 ) {
                    flush(url, mThumbsCVItems);
                }
            } else if( url.equals(mGenresUri) ) {

                mGenresCVItems.add(new ContentValues(initialValues));

                if( mGenresCVItems.size() >= 1000 ) {
                    flush(url, mGenresCVItems);
                }
            } else if( url.equals(mImageThumbReqUri) ) {
                mImageThumbReqCVItems.add(new ContentValues(initialValues));

                if( mImageThumbReqCVItems.size() >= 1000 ) {
                    flush(url, mImageThumbReqCVItems);
                }
            } else if( url.equals(mVideoThumbReqUri) ) {
                mVideoThumbReqCVItems.add(new ContentValues(initialValues));

                if( mVideoThumbReqCVItems.size() >= 1000 ) {
                    flush(url, mVideoThumbReqCVItems);
                }
            } else if( url.equals(mAlbumArtReqUri) ) {
                mAlbumartReqCVItems.add(new ContentValues(initialValues));

                if( mAlbumartReqCVItems.size() >= 1000 ) {
                    flush(url, mAlbumartReqCVItems);
                }
            
            } else {
                throw new RuntimeException();
            }
        }

        private void flush(Uri url, List<ContentValues> items) throws RemoteException {

            if( !isAvailableStorage() ) {
                Log.e(TAG, "Not available storage : " + mVolumeName);
            } else if(  items.size() > 0 ) {
                int i = 0;
                
                ContentValues values [] = new ContentValues[items.size()];

                for( ContentValues va : items ) {
                    values[i++] = va;
                }

                mMediaProvider.bulkInsert(url, values);

                for( i=0; i<items.size() ; i++ ) {
                    values[i].clear();
                }

                for( ContentValues va : items ) {
                    va.clear();
                }
                
            }

            items.clear();            
        }

        public void flush() throws RemoteException {
            flush(mAudioUri, mAudioCVItems);
            flush(mVideoUri, mVideoCVItems);
            flush(mImagesUri, mImagesCVItems);
            flush(mThumbsUri, mThumbsCVItems);
            flush(mGenresUri, mGenresCVItems);
            flush(mImageThumbReqUri, mImageThumbReqCVItems);
            flush(mVideoThumbReqUri, mVideoThumbReqCVItems);
            flush(mAlbumArtReqUri, mAlbumartReqCVItems);
        }

        public void clear() {
            mAudioCVItems.clear();
            mVideoCVItems.clear();
            mImagesCVItems.clear();
            mThumbsCVItems.clear();
            mGenresCVItems.clear();
            mImageThumbReqCVItems.clear();
            mVideoThumbReqCVItems.clear();
            mAlbumartReqCVItems.clear();
        }

    }

    private MyMediaScannerBulkDelete mBulkDelete = new MyMediaScannerBulkDelete();

    private class MyMediaScannerBulkDelete {

        private List<Uri> mUris = new ArrayList<Uri>();
        
        public void delete(Uri url) throws RemoteException  {

            mUris.add(url);

            if( mUris.size() >= 2000 )
            {
                flush();
            }
        }

        private void flush() throws RemoteException {

            if( isAvailableStorage() == true && mUris.size() > 0 ) {
                int i = 0;
              
                Uri uris [] = new Uri[mUris.size()];

                for( Uri uri : mUris ) {
                    uris[i++] = uri;
                }

                mMediaProvider.bulkDelete(uris, null, null);
                
            }

            mUris.clear();            
        }

        public void clear() {
            mUris.clear();
        }
        
    }

    private MyMediaScannerClient mClient = new MyMediaScannerClient();

    private class MyMediaScannerClient implements MediaScannerClient {

        private String mArtist;
        private String mAlbumArtist;    // use this if mArtist is missing
        private String mAlbum;
        private String mTitle;
        private String mComposer;
        private String mGenre;
        private String mMimeType;
        private int mFileType;
        private int mTrack;
        private int mYear;
        private int mDuration;
        private String mPath;
        private long mLastModified;
        private long mFileSize;
        private String mWriter;

        public FileCacheEntry beginFile(String path, String mimeType, long lastModified, long fileSize) {

            // special case certain file names
            // I use regionMatches() instead of substring() below
            // to avoid memory allocation
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash + 2 < path.length()) {
                // ignore those ._* files created by MacOS
                if (path.regionMatches(lastSlash + 1, "._", 0, 2)) {
                    return null;
                }

                // ignore album art files created by Windows Media Player:
                // Folder.jpg, AlbumArtSmall.jpg, AlbumArt_{...}_Large.jpg and AlbumArt_{...}_Small.jpg
                if (path.regionMatches(true, path.length() - 4, ".jpg", 0, 4)) {
                    if (path.regionMatches(true, lastSlash + 1, "AlbumArt_{", 0, 10) ||
                            path.regionMatches(true, lastSlash + 1, "AlbumArt.", 0, 9)) {
                        return null;
                    }
                    int length = path.length() - lastSlash - 1;
                    if ((length == 17 && path.regionMatches(true, lastSlash + 1, "AlbumArtSmall", 0, 13)) ||
                            (length == 10 && path.regionMatches(true, lastSlash + 1, "Folder", 0, 6))) {
                        return null;
                    }
                }
            }

            mMimeType = null;
            // try mimeType first, if it is specified
            if (mimeType != null) {
                mFileType = MediaFile.getFileTypeForMimeType(mimeType);
                if (mFileType != 0) {
                    mMimeType = mimeType;
                }
            }
            mFileSize = fileSize;

            // if mimeType was not specified, compute file type based on file extension.
            if (mMimeType == null) {
                MediaFile.MediaFileType mediaFileType = MediaFile.getFileType(path);
                if (mediaFileType != null) {
                    mFileType = mediaFileType.fileType;
                    mMimeType = mediaFileType.mimeType;
                }
            }

            String key = path;
            if (mCaseInsensitivePaths) {
                key = path.toLowerCase();
            }
            FileCacheEntry entry = mFileCache.get(key);
            if (entry == null) {
                entry = new FileCacheEntry(null, 0, path, 0);
                mFileCache.put(key, entry);
            }
            entry.mSeenInFileSystem = true;

            // add some slack to avoid a rounding error
            long delta = lastModified - entry.mLastModified;
            if (delta > 1 || delta < -1) {
                entry.mLastModified = lastModified;
                entry.mLastModifiedChanged = true;
            }

            if (mProcessPlaylists && MediaFile.isPlayListFileType(mFileType)) {
                mPlayLists.add(entry);
                // we don't process playlists in the main scan, so return null
                return null;
            }

            // clear all the metadata
            mArtist = null;
            mAlbumArtist = null;
            mAlbum = null;
            mTitle = null;
            mComposer = null;
            mGenre = null;
            mTrack = 0;
            mYear = 0;
            mDuration = 0;
            mPath = path;
            mLastModified = lastModified;
            mWriter = null;

            return entry;
        }

        public void scanFile(String path, long lastModified, long fileSize) {
            // This is the callback funtion from native codes.
            // Log.v(TAG, "scanFile: "+path);
            doScanFile(path, null, lastModified, fileSize, false);
        }

        public void scanFile(String path, String mimeType, long lastModified, long fileSize) {
            doScanFile(path, mimeType, lastModified, fileSize, false);
        }

        public Uri doScanFile(String path, String mimeType, long lastModified, long fileSize, boolean scanAlways, boolean isSingleScan) {
            Uri result = null;
//            long t1 = System.currentTimeMillis();
            try {
                FileCacheEntry entry = beginFile(path, mimeType, lastModified, fileSize);

                if( entry != null )
                {
                    if( LOCAL_DEBUG_MSG ) Log.v(TAG, "doScanFile Path:"+entry.mPath+" mRowId:"+entry.mRowId+" mLastModifiedChanged:"+entry.mLastModifiedChanged+" scanAlways:"+scanAlways);
                }

                // rescan for metadata if file was modified since last scan
                if (entry != null && (entry.mLastModifiedChanged || scanAlways)) {
                    String lowpath = path.toLowerCase();
                    boolean ringtones = (lowpath.indexOf(RINGTONES_DIR) > 0);
                    boolean notifications = (lowpath.indexOf(NOTIFICATIONS_DIR) > 0);
                    boolean alarms = (lowpath.indexOf(ALARMS_DIR) > 0);
                    boolean podcasts = (lowpath.indexOf(PODCAST_DIR) > 0);
                    boolean music = (lowpath.indexOf(MUSIC_DIR) > 0) ||
                        (!ringtones && !notifications && !alarms && !podcasts);

                    long t1 = System.currentTimeMillis();
                    
                    if (!MediaFile.isImageFileType(mFileType)) {
                        processFile(path, mimeType, this);
                    }

                    long t2 = System.currentTimeMillis();

                    result = endFile(entry, ringtones, notifications, alarms, music, podcasts, isSingleScan);
                    long t3 = System.currentTimeMillis();

                    mDBInsertTime += (t3-t2);
                    mProcessFileTime += (t2-t1);
                    mInsertCnt ++;

                    // Log.v(TAG, "scanFile: " + path + " took " + (t4-t1) +" extract : " + (t2-t1) + " insert : " + (t3-t2) );
                    
                }
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in MediaScanner.scanFile()", e);
            }
//            long t2 = System.currentTimeMillis();
//            Log.v(TAG, "scanFile: " + path + " took " + (t2-t1));
            return result;
        }

        public Uri doScanFile(String path, String mimeType, long lastModified, long fileSize, boolean scanAlways) {
            return doScanFile(path, mimeType, lastModified, fileSize, scanAlways, false);
        }

        private int parseSubstring(String s, int start, int defaultValue) {
            int length = s.length();
            if (start == length) return defaultValue;

            char ch = s.charAt(start++);
            // return defaultValue if we have no integer at all
            if (ch < '0' || ch > '9') return defaultValue;

            int result = ch - '0';
            while (start < length) {
                ch = s.charAt(start++);
                if (ch < '0' || ch > '9') return result;
                result = result * 10 + (ch - '0');
            }

            return result;
        }

        public void handleStringTag(String name, String value) {
            if (name.equalsIgnoreCase("title") || name.startsWith("title;")) {
                // Don't trim() here, to preserve the special \001 character
                // used to force sorting. The media provider will trim() before
                // inserting the title in to the database.
                mTitle = value;
            } else if (name.equalsIgnoreCase("artist") || name.startsWith("artist;")) {
                mArtist = value.trim();
            } else if (name.equalsIgnoreCase("albumartist") || name.startsWith("albumartist;")) {
                mAlbumArtist = value.trim();
            } else if (name.equalsIgnoreCase("album") || name.startsWith("album;")) {
                mAlbum = value.trim();
            } else if (name.equalsIgnoreCase("composer") || name.startsWith("composer;")) {
                mComposer = value.trim();
            } else if (name.equalsIgnoreCase("genre") || name.startsWith("genre;")) {
                // handle numeric genres, which PV sometimes encodes like "(20)"
                if (value.length() > 0) {
                    int genreCode = -1;
                    char ch = value.charAt(0);
                    if (ch == '(') {
                        genreCode = parseSubstring(value, 1, -1);
                    } else if (ch >= '0' && ch <= '9') {
                        genreCode = parseSubstring(value, 0, -1);
                    }
                    if (genreCode >= 0 && genreCode < ID3_GENRES.length) {
                        value = ID3_GENRES[genreCode];
                    } else if (genreCode == 255) {
                        // 255 is defined to be unknown
                        value = null;
                    }
                }
                mGenre = value;
            } else if (name.equalsIgnoreCase("year") || name.startsWith("year;")) {
                mYear = parseSubstring(value, 0, 0);
            } else if (name.equalsIgnoreCase("tracknumber") || name.startsWith("tracknumber;")) {
                // track number might be of the form "2/12"
                // we just read the number before the slash
                int num = parseSubstring(value, 0, 0);
                mTrack = (mTrack / 1000) * 1000 + num;
            } else if (name.equalsIgnoreCase("discnumber") ||
                    name.equals("set") || name.startsWith("set;")) {
                // set number might be of the form "1/3"
                // we just read the number before the slash
                int num = parseSubstring(value, 0, 0);
                mTrack = (num * 1000) + (mTrack % 1000);
            } else if (name.equalsIgnoreCase("duration")) {
                mDuration = parseSubstring(value, 0, 0);
            } else if (name.equalsIgnoreCase("writer") || name.startsWith("writer;")) {
                mWriter = value.trim();
            }
        }

        public void setMimeType(String mimeType) {
            if ("audio/mp4".equals(mMimeType) &&
                    mimeType.startsWith("video")) {
                // for feature parity with Donut, we force m4a files to keep the
                // audio/mp4 mimetype, even if they are really "enhanced podcasts"
                // with a video track
                return;
            }
            mMimeType = mimeType;
            mFileType = MediaFile.getFileTypeForMimeType(mimeType);
        }

        /**
         * Formats the data into a values array suitable for use with the Media
         * Content Provider.
         *
         * @return a map of values
         */
        private ContentValues toValues() {
            ContentValues map = new ContentValues();

            map.put(MediaStore.MediaColumns.DATA, mPath);
            map.put(MediaStore.MediaColumns.TITLE, mTitle);
            map.put(MediaStore.MediaColumns.DATE_MODIFIED, mLastModified);
            map.put(MediaStore.MediaColumns.SIZE, mFileSize);
            map.put(MediaStore.MediaColumns.MIME_TYPE, mMimeType);

            if (MediaFile.isVideoFileType(mFileType)) {
                map.put(Video.Media.ARTIST, (mArtist != null && mArtist.length() > 0 ? mArtist : MediaStore.UNKNOWN_STRING));
                map.put(Video.Media.ALBUM, (mAlbum != null && mAlbum.length() > 0 ? mAlbum : MediaStore.UNKNOWN_STRING));
                map.put(Video.Media.DURATION, mDuration);
                // FIXME - add RESOLUTION
            } else if (MediaFile.isImageFileType(mFileType)) {
                // FIXME - add DESCRIPTION
            } else if (MediaFile.isAudioFileType(mFileType)) {
                map.put(Audio.Media.ARTIST, (mArtist != null && mArtist.length() > 0) ?
                        mArtist : MediaStore.UNKNOWN_STRING);
                map.put(Audio.Media.ALBUM_ARTIST, (mAlbumArtist != null &&
                        mAlbumArtist.length() > 0) ? mAlbumArtist : null);
                map.put(Audio.Media.ALBUM, (mAlbum != null && mAlbum.length() > 0) ?
                        mAlbum : MediaStore.UNKNOWN_STRING);
                map.put(Audio.Media.COMPOSER, mComposer);
                if (mYear != 0) {
                    map.put(Audio.Media.YEAR, mYear);
                }
                map.put(Audio.Media.TRACK, mTrack);
                map.put(Audio.Media.DURATION, mDuration);
            }
            return map;
        }


        private Uri endFile(FileCacheEntry entry, boolean ringtones, boolean notifications,
                boolean alarms, boolean music, boolean podcasts, boolean isSingleScan)
                throws RemoteException {
            // update database
            Uri tableUri;
            boolean isAudio = MediaFile.isAudioFileType(mFileType);
            boolean isVideo = MediaFile.isVideoFileType(mFileType);
            boolean isImage = MediaFile.isImageFileType(mFileType);
            if (isVideo) {
                tableUri = mVideoUri;
            } else if (isImage) {
                tableUri = mImagesUri;
            } else if (isAudio) {
                tableUri = mAudioUri;
            } else {
                // don't add file to database if not audio, video or image
                return null;
            }
            entry.mTableUri = tableUri;

             // use album artist if artist is missing
            if (mArtist == null || mArtist.length() == 0) {
                mArtist = mAlbumArtist;
            }

            ContentValues values = toValues();
            String title = values.getAsString(MediaStore.MediaColumns.TITLE);
            if (title == null || TextUtils.isEmpty(title.trim())) {
                title = values.getAsString(MediaStore.MediaColumns.DATA);
                // extract file name after last slash
                int lastSlash = title.lastIndexOf('/');
                if (lastSlash >= 0) {
                    lastSlash++;
                    if (lastSlash < title.length()) {
                        title = title.substring(lastSlash);
                    }
                }
                // truncate the file extension (if any)
                int lastDot = title.lastIndexOf('.');
                if (lastDot > 0) {
                    title = title.substring(0, lastDot);
                }
                values.put(MediaStore.MediaColumns.TITLE, title);
            }
            String album = values.getAsString(Audio.Media.ALBUM);
            if (MediaStore.UNKNOWN_STRING.equals(album)) {
                album = values.getAsString(MediaStore.MediaColumns.DATA);
                // extract last path segment before file name
                int lastSlash = album.lastIndexOf('/');
                if (lastSlash >= 0) {
                    int previousSlash = 0;
                    while (true) {
                        int idx = album.indexOf('/', previousSlash + 1);
                        if (idx < 0 || idx >= lastSlash) {
                            break;
                        }
                        previousSlash = idx;
                    }
                    if (previousSlash != 0) {
                        album = album.substring(previousSlash + 1, lastSlash);
                        values.put(Audio.Media.ALBUM, album);
                    }
                }
            }
            long rowId = entry.mRowId;
            if (isAudio && rowId == 0) {
                // Only set these for new entries. For existing entries, they
                // may have been modified later, and we want to keep the current
                // values so that custom ringtones still show up in the ringtone
                // picker.
                values.put(Audio.Media.IS_RINGTONE, ringtones);
                values.put(Audio.Media.IS_NOTIFICATION, notifications);
                values.put(Audio.Media.IS_ALARM, alarms);
                values.put(Audio.Media.IS_MUSIC, music);
                values.put(Audio.Media.IS_PODCAST, podcasts);
            } else if (mFileType == MediaFile.FILE_TYPE_JPEG) {
                ExifInterface exif = null;
                try {
                    exif = new ExifInterface(entry.mPath);
                } catch (IOException ex) {
                    // exif is null
                }
                if (exif != null) {
                    float[] latlng = new float[2];
                    if (exif.getLatLong(latlng)) {
                        values.put(Images.Media.LATITUDE, latlng[0]);
                        values.put(Images.Media.LONGITUDE, latlng[1]);
                    }

                    long time = exif.getGpsDateTime();
                    if (time != -1) {
                        values.put(Images.Media.DATE_TAKEN, time);
                    }

                    int orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, -1);
                    if (orientation != -1) {
                        // We only recognize a subset of orientation tag values.
                        int degree;
                        switch(orientation) {
                            case ExifInterface.ORIENTATION_ROTATE_90:
                                degree = 90;
                                break;
                            case ExifInterface.ORIENTATION_ROTATE_180:
                                degree = 180;
                                break;
                            case ExifInterface.ORIENTATION_ROTATE_270:
                                degree = 270;
                                break;
                            default:
                                degree = 0;
                                break;
                        }
                        values.put(Images.Media.ORIENTATION, degree);
                    }
                }
            }

            Uri result = null;
            if (rowId == 0) {
                if( (notifications && !mDefaultNotificationSet) || 
                    (ringtones && !mDefaultRingtoneSet) || 
                    (alarms && !mDefaultAlarmSet) ) 
                {
                    result = mMediaProvider.insert(tableUri, values);
                    if (result != null) {
                        rowId = ContentUris.parseId(result);
                        entry.mRowId = rowId;
                    }
                } else {
                    //new file, insert it
                    if( !Environment.isUnifiedDatabaseSupport() && isAudio && mMusicAddCount < 10 ) {
                        mMusicAddCount ++;

                        result = mMediaProvider.insert(tableUri, values);
                        if (result != null) {
                            rowId = ContentUris.parseId(result);
                            entry.mRowId = rowId;
                        }
                    } else {
                        if (isAudio && mProcessGenres && mGenre != null) {
                            values.put("genre", mGenre);
                        }

                        if( isSingleScan ) {
                            result = mMediaProvider.insert(tableUri, values);
                            if (result != null) {
                                rowId = ContentUris.parseId(result);
                                entry.mRowId = rowId;
                            }
                        } else {
                            mBulkInsert.insert(tableUri, values);
                        }
                    }
                }
            } else {
                // updated file
                result = ContentUris.withAppendedId(tableUri, rowId);
                mMediaProvider.update(result, values, null, null);
            }

            if( rowId != 0 ) {
                if (isAudio && mProcessGenres && mGenre != null) {
                    String genre = mGenre;
                    Uri uri = mGenreCache.get(genre);
                    if (uri == null) {
                        Cursor cursor = null;
                        try {
                            // see if the genre already exists
                            cursor = mMediaProvider.query(
                                    mGenresUri,
                                    GENRE_LOOKUP_PROJECTION, MediaStore.Audio.Genres.NAME + "=?",
                                            new String[] { genre }, null);
                            if (cursor == null || cursor.getCount() == 0) {
                                // genre does not exist, so create the genre in the genre table
                                values.clear();
                                values.put(MediaStore.Audio.Genres.NAME, genre);
                                uri = mMediaProvider.insert(mGenresUri, values);
                            } else {
                                // genre already exists, so compute its Uri
                                cursor.moveToNext();
                                uri = ContentUris.withAppendedId(mGenresUri, cursor.getLong(0));
                            }
                            if (uri != null) {
                                uri = Uri.withAppendedPath(uri, Genres.Members.CONTENT_DIRECTORY);
                                mGenreCache.put(genre, uri);
                            }
                        } finally {
                            // release the cursor if it exists
                            if (cursor != null) {
                                cursor.close();
                            }
                        }
                    }

                    if (uri != null) {
                        // add entry to audio_genre_map
                        values.clear();
                        values.put(MediaStore.Audio.Genres.Members.AUDIO_ID, Long.valueOf(rowId));
                        mMediaProvider.insert(uri, values);
                    }
                }                    
            }

            if (notifications && !mDefaultNotificationSet) {
                if (TextUtils.isEmpty(mDefaultNotificationFilename) ||
                        doesPathHaveFilename(entry.mPath, mDefaultNotificationFilename)) {
                    setSettingIfNotSet(Settings.System.NOTIFICATION_SOUND, tableUri, rowId);
                    mDefaultNotificationSet = true;
                }
            } else if (ringtones && !mDefaultRingtoneSet) {
                if (TextUtils.isEmpty(mDefaultRingtoneFilename) ||
                        doesPathHaveFilename(entry.mPath, mDefaultRingtoneFilename)) {
                    setSettingIfNotSet(Settings.System.RINGTONE, tableUri, rowId);
                    mDefaultRingtoneSet = true;
                }
            } else if (alarms && !mDefaultAlarmSet) {
                if (TextUtils.isEmpty(mDefaultAlarmAlertFilename) ||
                        doesPathHaveFilename(entry.mPath, mDefaultAlarmAlertFilename)) {
                    setSettingIfNotSet(Settings.System.ALARM_ALERT, tableUri, rowId);
                    mDefaultAlarmSet = true;
                }
            }

            return result;
        }

        private Uri endFile(FileCacheEntry entry, boolean ringtones, boolean notifications,
                boolean alarms, boolean music, boolean podcasts)
                throws RemoteException {
                return endFile(entry, ringtones, notifications, alarms, music, podcasts, false);
        }

        private boolean doesPathHaveFilename(String path, String filename) {
            int pathFilenameStart = path.lastIndexOf(File.separatorChar) + 1;
            int filenameLength = filename.length();
            return path.regionMatches(pathFilenameStart, filename, 0, filenameLength) &&
                    pathFilenameStart + filenameLength == path.length();
        }

        private void setSettingIfNotSet(String settingName, Uri uri, long rowId) {

            String existingSettingValue = Settings.System.getString(mContext.getContentResolver(),
                    settingName);

            if (TextUtils.isEmpty(existingSettingValue)) {
                // Set the setting to the given URI
                Settings.System.putString(mContext.getContentResolver(), settingName,
                        ContentUris.withAppendedId(uri, rowId).toString());
            }
        }

        public void addNoMediaFolder(String path) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.ImageColumns.DATA, "");
            String [] pathSpec = new String[] {path + '%'};
            try {
                // These tables have DELETE_FILE triggers that delete the file from the
                // sd card when deleting the database entry. We don't want to do this in
                // this case, since it would cause those files to be removed if a .nomedia
                // file was added after the fact, when in that case we only want the database
                // entries to be removed.
                mMediaProvider.update(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
                        MediaStore.Images.ImageColumns.DATA + " LIKE ?", pathSpec);
                mMediaProvider.update(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values,
                        MediaStore.Images.ImageColumns.DATA + " LIKE ?", pathSpec);
            } catch (RemoteException e) {
                throw new RuntimeException();
            }
        }

    }; // end of anonymous MediaScannerClient instance

    private void prescan(String volumeName, String filePath) throws RemoteException {
        Cursor c = null;
        String where = null;
        String limit = null;
        String[] selectionArgs = null;
        
        long index = 0;
        long bulkCount = 500;
        
        Uri uri;

        if (mFileCache == null) {
            mFileCache = new HashMap<String, FileCacheEntry>();
        } else {
            mFileCache.clear();
        }
        if (mPlayLists == null) {
            mPlayLists = new ArrayList<FileCacheEntry>();
        } else {
            mPlayLists.clear();
        }

        // Build the list of files from the content provider
        try {
            index = 0;

            while( true ) {
                if( !Environment.isMounted(volumeName) ) {
                    if( c != null) {
                        c.close();
                        c = null;
                    }

                    return;
                }

                // Read existing files from the audio table
                if (filePath != null) {
                    where = MediaStore.Audio.Media.DATA + "=?";
                    selectionArgs = new String[] { filePath };
                } else {
                    where = null;
                }
                
                limit = index + " , " + bulkCount;
                index += bulkCount;
                uri = mAudioUri.buildUpon().appendQueryParameter("limit", limit).build();
                
                c = mMediaProvider.query(uri, AUDIO_PROJECTION, where, selectionArgs, null);

                if ( c != null && c.moveToFirst() ) {
                    try {
                        do {
                            long rowId = c.getLong(ID_AUDIO_COLUMN_INDEX);
                            String path = c.getString(PATH_AUDIO_COLUMN_INDEX);
                            long lastModified = c.getLong(DATE_MODIFIED_AUDIO_COLUMN_INDEX);

                            // Only consider entries with absolute path names.
                            // This allows storing URIs in the database without the
                            // media scanner removing them.
                            String key = path;
                            if (path.startsWith("/")) {
                                if (mCaseInsensitivePaths) {
                                    key = path.toLowerCase();
                                }
                            }

                            mFileCache.put(key, new FileCacheEntry(mAudioUri, rowId, path,
                                    lastModified));
                        } while (c.moveToNext());
                    } finally {

                        if(c.getCount() != bulkCount) {
                            c.close();
                            c = null;
                            break;
                        }

                        c.close();
                        c = null;
                    }
                } else {

                    if( c != null) c.close();
                    c = null;
                    break;
                }
                
            }


            index = 0;

            while( true ) {

                if( !Environment.isMounted(volumeName) ) {
                    if( c != null) {
                        c.close();
                        c = null;
                    }

                    return;
                }
            
                // Read existing files from the video table
                if (filePath != null) {
                    where = MediaStore.Video.Media.DATA + "=?";
                } else {
                    where = null;
                }

                limit = index + " , " + bulkCount;
                index += bulkCount;

                uri = mVideoUri.buildUpon().appendQueryParameter("limit", limit).build();                
                c = mMediaProvider.query(uri, VIDEO_PROJECTION, where, selectionArgs, null);

                if (c != null && c.moveToFirst()) {
                    try {
                        do {
                            long rowId = c.getLong(ID_VIDEO_COLUMN_INDEX);
                            String path = c.getString(PATH_VIDEO_COLUMN_INDEX);
                            long lastModified = c.getLong(DATE_MODIFIED_VIDEO_COLUMN_INDEX);
                            long miniThumbMagic = c.getLong(VIDEO_MINI_THUMB_MAGIC_COLUMN_INDEX);
                            // Only consider entries with absolute path names.
                            // This allows storing URIs in the database without the
                            // media scanner removing them.
                            if (path.startsWith("/")) {
                                String key = path;
                                if (mCaseInsensitivePaths) {
                                    key = path.toLowerCase();
                                }
                                mFileCache.put(key, new FileCacheEntry(mVideoUri, rowId, path, lastModified, miniThumbMagic));
                            }
                        } while (c.moveToNext());
                    } finally {

                        if(c.getCount() != bulkCount) {
                            c.close();
                            c = null;
                            break;
                        }
                    
                        c.close();
                        c = null;
                    }
                }else {
                    if( c != null) c.close();
                    c = null;
                    break;
                }
            }
            
            where = null;            

            index = 0;
            while( true ) {

                if( !Environment.isMounted(volumeName) ) {
                    if( c != null) {
                        c.close();
                        c = null;
                    }

                    return;
                }

                // Read existing files from the images table
                if (filePath != null) {
                    where = MediaStore.Images.Media.DATA + "=?";
                } else {
                    where = null;
                }

                limit = index + " , " + bulkCount;
                index += bulkCount;

                uri = mImagesUri.buildUpon().appendQueryParameter("limit", limit).build();
                
                mOriginalCount = 0;
                c = mMediaProvider.query(uri, IMAGES_PROJECTION, where, selectionArgs, null);

                if (c != null && c.moveToFirst()) {
                    try {
                        mOriginalCount = c.getCount();
                        do {
                            long rowId = c.getLong(ID_IMAGES_COLUMN_INDEX);
                            String path = c.getString(PATH_IMAGES_COLUMN_INDEX);
                            long lastModified = c.getLong(DATE_MODIFIED_IMAGES_COLUMN_INDEX);
                            long miniThumbMagic= c.getLong(IMAGE_MINI_THUMB_MAGIC_COLUMN_INDEX); 

                           // Only consider entries with absolute path names.
                           // This allows storing URIs in the database without the
                           // media scanner removing them.
                           if (path.startsWith("/")) {
                               String key = path;
                               if (mCaseInsensitivePaths) {
                                   key = path.toLowerCase();
                               }
                                mFileCache.put(key, new FileCacheEntry(mImagesUri, rowId, path, lastModified, miniThumbMagic));
                           }
                        } while (c.moveToNext());
                    } finally {

                        if(c.getCount() != bulkCount) {
                            c.close();
                            c = null;
                            break;
                        }
                    
                        c.close();
                        c = null;
                    }
                }else {
                    if( c != null) c.close();
                    c = null;
                    break;
                }
            }
            
            if (mProcessPlaylists) {
                // Read existing files from the playlists table
                if (filePath != null) {
                    where = MediaStore.Audio.Playlists.DATA + "=?";
                } else {
                    where = null;
                }
                c = mMediaProvider.query(mPlaylistsUri, PLAYLISTS_PROJECTION, where, selectionArgs, null);

                if (c != null) {
                    try {
                        while (c.moveToNext()) {
                            String path = c.getString(PATH_PLAYLISTS_COLUMN_INDEX);

                            if (path != null && path.length() > 0) {
                                long rowId = c.getLong(ID_PLAYLISTS_COLUMN_INDEX);
                                long lastModified = c.getLong(DATE_MODIFIED_PLAYLISTS_COLUMN_INDEX);

                                String key = path;
                                if (mCaseInsensitivePaths) {
                                    key = path.toLowerCase();
                                }
                                mFileCache.put(key, new FileCacheEntry(mPlaylistsUri, rowId, path,
                                        lastModified));
                            }
                        }
                    } finally {
                        c.close();
                        c = null;
                    }
                }
            }
        }
        finally {
            if (c != null) {
                c.close();
            }
        }
    }

    // VIEW_PLAYLISTS
    private void syncPlaylistMembers(ContentValues valueInit, String volumeName) throws RemoteException{
        Cursor ce = null;
        long audio_id;
        String where = null;
        long date_added = 0;
        ContentValues cv = new ContentValues(valueInit);
        long playlist_id = 0;
        long play_order = 0;
        String name = cv.getAsString("name");

        if(LOCAL_DEBUG_MSG) Log.v(TAG, " playlists " + cv + "\n");

        if( !Environment.isUnifiedDatabaseSupport() )
            return;

        playlist_id = cv.getAsLong("_id");
        date_added = cv.getAsLong("date_added");

        where = "playlist_id=" + playlist_id + " AND _playlist_date_added=" + date_added;

        if(LOCAL_DEBUG_MSG) Log.v(TAG, " where=" + where + "\n");

        ce = mMediaProvider.query(Playlists.Members.getContentUri(volumeName,playlist_id),new String[] { "_id","audio_id", "play_order"}, where, null, null);

        if(ce != null && ce.getCount() > 0)
            if(LOCAL_DEBUG_MSG) Log.v(TAG, " member where " + where + " count : " + ce.getCount() + "\n");

        if(ce != null)
        {
            try{
                if(ce.moveToFirst())
                {
                    ContentValues cm[] = new ContentValues[ce.getCount()];
                    int memberCnt = 0;

                    do{
                        audio_id = ce.getLong(ce.getColumnIndex("audio_id"));
                        play_order = ce.getLong(ce.getColumnIndex("play_order"));
                        cm[memberCnt] = new ContentValues();
                        cm[memberCnt].put("_id",MediaStore.getUniIdForLocalId(volumeName, (int)ce.getLong(ce.getColumnIndex("_id"))));
                        cm[memberCnt].put("audio_id", MediaStore.getUniIdForLocalId(volumeName, (int)audio_id));
                        cm[memberCnt].put("playlist_id", playlist_id);
                        cm[memberCnt].put("play_order",play_order);
                        cm[memberCnt].put("_playlist_date_added",date_added);
                        if(LOCAL_DEBUG_MSG) Log.v(TAG," memberCnt:" +memberCnt + "      cm:" + cm[memberCnt] + "\n");
                        memberCnt++;

                    } while (ce.moveToNext());

                    int playlist_member = 0;

                    if(memberCnt > 0)
                    playlist_member = mMediaProvider.bulkInsert(Playlists.Members.getContentUri(MediaStore.UNIFIED_VOLUME_ONLY,playlist_id), cm);
                }
            }finally{
                ce.close();
                ce = null;
            }
        }
    }

    // start VIEW_PLAYLISTS
    public void syncPlaylist(String volumeName,long bulkCount) throws RemoteException{
        Cursor c = null;
        String where = null;
        ContentValues cv;
        String limit;
        Uri uri;
        long index = 0;
        try {
            where = null;            
            index = 0;
            while( true ) {
                if( !Environment.isMounted(volumeName) ) {
                    if( c != null) {
                        c.close();
                        c = null;
                    }

                    return;
                }

                limit = index + " , " + bulkCount;
                index += bulkCount;
                uri = Playlists.getContentUri(volumeName).buildUpon().appendQueryParameter("limit", limit).build();
                
                c = mMediaProvider.query(uri, null, null, null, null);
                if (c != null && c.moveToFirst()) {
                    long playlist_sync_start = System.currentTimeMillis();
                    int playlistCnt = 0;
                    do{
                        String path = c.getString(c.getColumnIndex("_data"));
                        String name = c.getString(c.getColumnIndex("name"));
                        long playlistId;
                        if(LOCAL_DEBUG_MSG) Log.v(TAG, " playlists name : " + name + "\n");
                        if (name != null && path == null) 
                        {
                            cv = new ContentValues();
                            playlistId = c.getLong(c.getColumnIndex("_id"));
                            cv.put("_id",MediaStore.getUniIdForLocalId(volumeName, (int)playlistId));
                            cv.put("name",name);
                            cv.put("date_added",c.getLong(c.getColumnIndex("date_added")));
                            if(LOCAL_DEBUG_MSG) Log.v(TAG, " playlists " + cv + "\n");
                            mMediaProvider.insert(Playlists.getContentUri(MediaStore.UNIFIED_VOLUME_ONLY), cv);
                        }

                    }while(c.moveToNext());
                    
                    long playlist_sync_end = System.currentTimeMillis();
                } else {
                    if(c != null) c.close(); 
                    c = null;                

                    break;
                }
                
                if( c != null ) c.close();
                c = null;
            }
        }finally {
            if (c != null) {
                c.close();
            }
        }
    }
    // end VIEW_PLAYLISTS
    private void _syncUnfiedDatabase(String volumeName) throws RemoteException {
        Cursor c = null;
        String where = null;
        String[] selectionArgs = null;
        ContentValues cv;

        Uri uri;
        String limit;
        mUnifiedDBCnt = 0;
        
        long index = 0;
        long bulkCount = 500;

        if( Environment.isMounted(volumeName) == false )
            return;

        if( !Environment.isUnifiedDatabaseSupport() )
            return;

        if( !MediaStore.SDCARD_VOLUME.equals(volumeName) && 
            !MediaStore.NAND_VOLUME.equals(volumeName) && 
            !MediaStore.SCSI_VOLUME.equals(volumeName))
            return;

        if( mMediaProvider == null )
            mMediaProvider = mContext.getContentResolver().acquireProvider("media");

        // Build the list of files from the content provider
        try {
            where = null;            
            index = 0;
            
            while( true ) {
                if( !Environment.isMounted(volumeName) ) {
                    if( c != null) {
                        c.close();
                        c = null;
                    }

                    return;
                }

                // Read existing files from the albums table
                limit = index + " , " + bulkCount;
                index += bulkCount;
                uri = Audio.Albums.getAlbumTable(volumeName).buildUpon().appendQueryParameter("limit", limit).build();
                
                c = mMediaProvider.query(uri, new String[] { "album_id", "album_key", "album"}, where, null, null);

                if (c != null && c.moveToFirst()) {
                    try {
                        long sync_start = System.currentTimeMillis();

                        ContentValues values [] = new ContentValues[c.getCount()];

                        int i = 0;

                        do {
                            long album_id = c.getLong(0);

                            values[i] = new ContentValues();
                            DatabaseUtils.cursorRowToContentValues(c, values[i]);
                            values[i].remove("_id");

                            values[i].put("album_id", MediaStore.getUniIdForLocalId(volumeName, (int)album_id) );
                            
                            i++;
                        } while( c.moveToNext() );

                        long make_values_end = System.currentTimeMillis();

                        int insert_count = 0;


                        if( !Environment.isMounted(volumeName) ) {
                            if( c != null) {
                                c.close();
                                c = null;
                            }

                            return;
                        }

                        insert_count = mMediaProvider.bulkInsert(Audio.Albums.getContentUri(MediaStore.UNIFIED_VOLUME_ONLY), values);

                        long sync_end = System.currentTimeMillis();
                    } finally {
                        if(c != null) c.close(); 
                        c = null;                
                    }
                }else {

                    if( c!=null) c.close();
                    c = null;
                    break;
                }
            }

            where = null;            

            index = 0;

            while(true) {
                if( !Environment.isMounted(volumeName) ) {
                    if( c != null) {
                        c.close();
                        c = null;
                    }

                    return;
                }

        
                // Read existing files from the audio table
                limit = index + " , " + bulkCount;
                index += bulkCount;
                uri = Audio.Media.getContentUri(volumeName).buildUpon().appendQueryParameter("limit", limit).build();
                
                c = mMediaProvider.query(uri, UNIFIED_AUDIO_PROJECTION, where, selectionArgs, null);

                if (c != null && c.moveToFirst()) {
                    try {
                        long audio_sync_start = System.currentTimeMillis();

                        ContentValues values [] = new ContentValues[c.getCount()];
                        int i = 0;

                        do {
                            long rowId = c.getLong(ID_AUDIO_COLUMN_INDEX);
                            long album_id = c.getLong(ID_ALBUM_COLUMN_INDEX);

                            values[i] = new ContentValues();
                            DatabaseUtils.cursorRowToContentValues(c, values[i]);

                            values[i].remove("album_id");
                            values[i].put("album_id", MediaStore.getUniIdForLocalId(volumeName, (int)album_id));
                            
                            values[i].remove("_id");
                            values[i].put("_id", MediaStore.getUniIdForLocalId(volumeName, (int)rowId));

                            i++;
                        } while( c.moveToNext() );

                        long audio_make_values_end = System.currentTimeMillis();

                        int audio_insert_count = 0;

                        if( !Environment.isMounted(volumeName) ) {
                            if( c != null) {
                                c.close();
                                c = null;
                            }

                            return;
                        }

                        audio_insert_count = mMediaProvider.bulkInsert(Audio.Media.getContentUri(MediaStore.UNIFIED_VOLUME_ONLY), values);

                        long audio_sync_end = System.currentTimeMillis();

                        mUnifiedDBCnt++;
                    } finally {
                        if(c != null) c.close(); 
                        c = null;                
                    }
                } else {

                    if( c!=null) c.close();
                    c = null;
                    break;
                }
            }

           
            where = null;            
            index = 0;

            while( true ) {
                if( !Environment.isMounted(volumeName) ) {
                    if( c != null) {
                        c.close();
                        c = null;
                    }

                    return;
                }

                // Read existing files from the album_art table
                limit = index + " , " + bulkCount;
                index += bulkCount;
                uri = Audio.AlbumArt.getContentUri(volumeName).buildUpon().appendQueryParameter("limit", limit).build();
                
                c = mMediaProvider.query(uri, new String[] { "album_id", "_data"}, where, selectionArgs, null);

                if ( c != null && c.moveToFirst() ) {
                    try {
                        int countOfAlbumart = 0;

                        do {
                            if( c.getString(1) != null )
                                countOfAlbumart ++;
                        } while( c.moveToNext() );

                        if( countOfAlbumart > 0 )
                        {
                            ContentValues values [] = new ContentValues[countOfAlbumart];
                            c.moveToFirst();

                            int i = 0;

                            do {
                                if( c.getString(1) != null )
                                {
                                    long rowId = c.getLong(0);
                                    values[i] = new ContentValues();

                                    values[i].put("album_id", MediaStore.getUniIdForLocalId(volumeName, (int)rowId) );
                                     values[i].put("_data", c.getString(1));
                                    i++;
                                    
                                }
                            } while( c.moveToNext() );

                            long audio_make_values_end = System.currentTimeMillis();

                            int audio_insert_count = 0;

                            if( !Environment.isMounted(volumeName) ) {
                                if( c != null) {
                                    c.close();
                                    c = null;
                                }

                                return;
                            }

                            audio_insert_count = mMediaProvider.bulkInsert(Audio.AlbumArt.getContentUri(MediaStore.UNIFIED_VOLUME_ONLY), values);
                        }
                    } finally {
                        if(c != null) c.close(); 
                        c = null;                
                    }
                } else {
                
                    if(c != null) c.close(); 
                    c = null;                

                    break;
                }
            }


            where = null;            
            index = 0;

            while( true ) {
                if( !Environment.isMounted(volumeName) ) {
                    if( c != null) {
                        c.close();
                        c = null;
                    }

                    return;
                }

                // Read existing files from the album_info table
                limit = index + " , " + bulkCount;
                index += bulkCount;
                uri = Audio.Genres.getContentMapInfoUri(volumeName).buildUpon().appendQueryParameter("limit", limit).build();
                
                c = mMediaProvider.query(uri, new String[] { "audio_id", "name" }, where, selectionArgs, null);

                if (c != null && c.moveToFirst()) {
                    try {
                        long genre_sync_start = System.currentTimeMillis();

                        ContentValues values [] = new ContentValues[c.getCount()];
                        int i = 0;

                        do {
                            long rowId = c.getLong(0);

                            values[i] = new ContentValues();

                            values[i].put("audio_id", MediaStore.getUniIdForLocalId(volumeName, (int)rowId) );
                            values[i].put("name", c.getString(1));
                            i++;
                        } while( c.moveToNext() );

                        long genre_make_values_end = System.currentTimeMillis();

                        int genre_insert_count = 0;

                        if( !Environment.isMounted(volumeName) ) {
                            if( c != null) {
                                c.close();
                                c = null;
                            }

                            return;
                        }

                        genre_insert_count = mMediaProvider.bulkInsert(Audio.Genres.getContentMapInfoUri(MediaStore.UNIFIED_VOLUME_ONLY), values);

                    } finally {
                        if(c != null) c.close(); 
                        c = null;                
                    }
                } else {

                    if(c != null) c.close(); 
                    c = null;                
                
                    break;
                }
            }
            
            where = null;            
            index = 0;

            while( true ) {
                if( !Environment.isMounted(volumeName) ) {
                    if( c != null) {
                        c.close();
                        c = null;
                    }

                    return;
                }
                
                limit = index + " , " + bulkCount;
                index += bulkCount;
                uri = Video.Media.getContentUri(volumeName).buildUpon().appendQueryParameter("limit", limit).build();
                
                c = mMediaProvider.query(uri, null, where, selectionArgs, null);

                if (c != null && c.moveToFirst()) {
                    try {
                        long video_sync_start = System.currentTimeMillis();

                        ContentValues values [] = new ContentValues[c.getCount()];
                        int i = 0;

                        do{
                            long rowId = c.getLong(c.getColumnIndex(Video.Media._ID));

                            values[i] = new ContentValues();
                            DatabaseUtils.cursorRowToContentValues(c, values[i]);

                            values[i].remove("_id");
                            values[i].put("_id", MediaStore.getUniIdForLocalId(volumeName, (int)rowId) );

                            i++;
                            mUnifiedDBCnt++;
                        } while(c.moveToNext());

                        long video_make_values_end = System.currentTimeMillis();

                        int video_insert_count = 0;

                        if( !Environment.isMounted(volumeName) ) {
                            if( c != null) {
                                c.close();
                                c = null;
                            }

                            return;
                        }

                        video_insert_count = mMediaProvider.bulkInsert(Video.Media.getContentUri(MediaStore.UNIFIED_VOLUME_ONLY), values);

                        long video_sync_end = System.currentTimeMillis();
                        /*
                        if (Config.LOGD) {
                        Log.d(TAG, "count of added video: " + video_insert_count + " ea\n");
                        Log.d(TAG, "  db sync total time: " + (video_sync_end - video_sync_start) + "ms\n");
                        Log.d(TAG, "      db insert time: " + (video_sync_end - video_make_values_end) + "ms\n");
                        }
                        */
                    } finally {
                        if(c != null) c.close(); 
                        c = null;
                    }
                } else { 

                    if(c != null) c.close(); 
                    c = null;                
                
                    break;
                }
            }
            
            where = null;            
            index = 0;

            while( true ) {
                if( !Environment.isMounted(volumeName) ) {
                    if( c != null) {
                        c.close();
                        c = null;
                    }

                    return;
                }

                limit = index + " , " + bulkCount;
                index += bulkCount;
                uri = Images.Media.getContentUri(volumeName).buildUpon().appendQueryParameter("limit", limit).build();
                
                c = mMediaProvider.query(uri, null, where, selectionArgs, null);

                if (c != null && c.moveToFirst()) {
                    try {
                        mOriginalCount = c.getCount();

                        long image_sync_start = System.currentTimeMillis();

                        ContentValues values [] = new ContentValues[c.getCount()];
                        int i = 0;

                        do{
                            long rowId = c.getLong(c.getColumnIndex(Images.Media._ID));

                            values[i] = new ContentValues();
                            DatabaseUtils.cursorRowToContentValues(c, values[i]);

                            values[i].remove("_id");
                            values[i].put("_id", MediaStore.getUniIdForLocalId(volumeName, (int)rowId));

                            i++;
                            mUnifiedDBCnt++;
                        }while(c.moveToNext());

                        long image_make_values_end = System.currentTimeMillis();

                        int image_insert_count = 0;

                        if( !Environment.isMounted(volumeName) ) {
                            if( c != null) {
                                c.close();
                                c = null;
                            }

                            return;
                        }

                        image_insert_count = mMediaProvider.bulkInsert(Images.Media.getContentUri(MediaStore.UNIFIED_VOLUME_ONLY), values);

                        long image_sync_end = System.currentTimeMillis();
                    } finally {
                        if(c != null) c.close(); 
                        c = null;                
                    }

                } else {

                    if(c != null) c.close(); 
                    c = null;                
                
                    break;
                }
            }
            
            // start VIEW_PLAYLISTS 
            syncPlaylist(volumeName,bulkCount);
            
            where = null;            
            index = 0;

            while( true ) {
                if( !Environment.isMounted(volumeName) ) {
                    if( c != null) {
                        c.close();
                        c = null;
                    }

                    return;
                }

                limit = index + " , " + bulkCount;
                index += bulkCount;
                uri = Playlists.getContentUri(MediaStore.EXTERNAL_VOLUME).buildUpon().appendQueryParameter("limit", limit).build();

                c = mMediaProvider.query(uri,null,null,null,null);
                
                if (c != null && c.moveToFirst()) {
                    long playlistmember_sync_start = System.currentTimeMillis();
                    do{
                        String ref_volume = null;
                        long playlistId;
                        cv = new ContentValues();
                        DatabaseUtils.cursorRowToContentValues(c, cv);

                        playlistId = cv.getAsLong("_id");
                        ref_volume = MediaStore.getVolumeNameForUniId((int)playlistId);
                        if(LOCAL_DEBUG_MSG) Log.v(TAG, " query playlist " + cv + "\n");

                        if( !Environment.isMounted(volumeName) ) {
                            if( c != null) {
                                c.close();
                                c = null;
                            }

                            return;
                        }

                        syncPlaylistMembers(cv,volumeName);
                    }while(c.moveToNext());
                    long playlistmember_sync_end = System.currentTimeMillis();
                } else {
                    if(c != null) c.close(); 
                    c = null;                
                    break;
                }

                if(c != null) c.close();
                c = null;
            }
            // end VIEW_PLAYLISTS

        }
        finally {
            if (c != null) {
                c.close();
            }
        }
    }
    

    public void syncUnifiedDatabase(String volumeName) {
    
        if( MediaStore.isValidUnifiedDatabase(volumeName) ) {
            Log.w(TAG, "unified database " + volumeName + " volume is valid. sync skip!");
            return;
        }
    
        try {
            long start = System.currentTimeMillis();
            //initialize(volumeName);
            //prescan(null, false);
            mVolumeName = volumeName;

            MediaStore.setUnifiedDatabaseStatus(volumeName, MediaStore.MEDIA_DB_SYNCDB);

            if (volumeName.equals(MediaStore.NAND_VOLUME) && MediaStore.getAvailableUnifiedDBCount() == 0 ){
                if( mMediaProvider == null )
                    mMediaProvider = mContext.getContentResolver().acquireProvider("media");
            
                ContentValues cv = new ContentValues();
                cv.put("volume", MediaStore.NAND_VOLUME);
                mMediaProvider.insert(MediaStore.getAddNandDatabaseToUnifiedDatabaseUri(), cv);
            } else {
                if(LOCAL_DEBUG_MSG) Log.d(TAG, "called _syncUnfiedDatabase()\n");
                _syncUnfiedDatabase(volumeName);
            }

            // PLAYLIST_SAVE_INT start
            if( MediaStore.getSyncPlaylistToUnifiedDB() ) {
                if(LOCAL_DEBUG_MSG) Log.d(TAG, "called SyncPlaylist() volume : "+volumeName+"\n");
                if(!MediaStore.INTERNAL_VOLUME.equals(volumeName) && Environment.isUnifiedDatabaseSupport())
                {
                    if( mMediaProvider == null )
                    {
                        if(LOCAL_DEBUG_MSG) Log.d(TAG, "   mMediaProvider == null \n");
                        mMediaProvider = mContext.getContentResolver().acquireProvider("media");
                    }
                    if(LOCAL_DEBUG_MSG) Log.d(TAG, "called syncUnfiedDB() for internal playlist\n");
                    syncPlaylist(MediaStore.INTERNAL_VOLUME,500);
    
                    if(LOCAL_DEBUG_MSG) Log.d(TAG, "setSyncPlaylistToUnifiedDB : false\n");
                    MediaStore.setSyncPlaylistToUnifiedDB(false);
                }
            }
            else{
                 if(LOCAL_DEBUG_MSG) Log.d(TAG, "getSyncPlaylistToUnifiedDB : false\n");
            }
            // PLAYLIST_SAVE_INT end

            MediaStore.setUnifiedDatabaseStatus(volumeName, MediaStore.MEDIA_DB_AVAILABLE);
            
            long prescan = System.currentTimeMillis();

            if (Config.LOGD) {
                Log.d(TAG, "sync unified database time: " + (prescan - start) + "ms\n");
            }
        } catch (SQLException e) {
            // this might happen if the SD card is removed while the media scanner is running
            Log.e(TAG, "SQLException in MediaScanner.syncUnifiedDatabase()", e);
        } catch (UnsupportedOperationException e) {
            // this might happen if the SD card is removed while the media scanner is running
            Log.e(TAG, "UnsupportedOperationException in MediaScanner.syncUnifiedDatabase()", e);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in MediaScanner.syncUnifiedDatabase()", e);
        }
    }

    private boolean inScanDirectory(String path, String[] directories) {
        for (int i = 0; i < directories.length; i++) {
            if (path.startsWith(directories[i])) {
                return true;
            }
        }
        return false;
    }

    private void pruneDeadThumbnailFiles(String volumeName, Uri uri, String directory) {
        HashSet<String> existingFiles = new HashSet<String>();
        IContentProvider mediaProvider = mContext.getContentResolver().acquireProvider("media");
        
        String [] files = (new File(directory)).list();
        if (files == null)
            files = new String[0];

        if( !Environment.isMounted(volumeName) ) {            
            return;
        }
        
        for (int i = 0; i < files.length; i++) {
            String fullPathString = directory + "/" + files[i];
            existingFiles.add(fullPathString);

            if( !Environment.isMounted(volumeName) ) {            
                return;
            }
            
        }

        try {

            if( !Environment.isMounted(volumeName) ) {            
                return;
            }
        
            Cursor c = mediaProvider.query(
                    uri,
                    new String [] { "_data" },
                    null,
                    null,
                    null);
            Log.v(TAG, "pruneDeadThumbnailFiles... " + c);
            if (c != null && c.moveToFirst()) {
                do {
                    String fullPathString = c.getString(0);
                    existingFiles.remove(fullPathString);
                } while (c.moveToNext());
            }

            if( !Environment.isMounted(volumeName) ) {      
                if (c != null) {
                    c.close();
                }
            
                return;
            }


            for (String fileToDelete : existingFiles) {
                if (Config.LOGV)
                    Log.v(TAG, "fileToDelete is " + fileToDelete);
                try {
                
                    if( !Environment.isMounted(volumeName) ) {      
                        if (c != null) {
                            c.close();
                        }
                    
                        return;
                    }
                    
                    mWakeLock.acquire();
                    (new File(fileToDelete)).delete();
                    mWakeLock.release();

                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        ;
                    }

                } catch (SecurityException ex) {
                }
            }

            Log.v(TAG, "/pruneDeadThumbnailFiles... " + c);
            if (c != null) {
                c.close();
                c = null;
            }
        } catch (RemoteException e) {
            // We will soon be killed...
        }
    }
    
    public void pruneDeadThumbnailFiles(String volumeName) {
        if( MediaStore.SDCARD_VOLUME.equals(volumeName) ) {
            pruneDeadThumbnailFiles(volumeName, Images.Thumbnails.getContentUri(volumeName), "/sdcard/sd/DCIM/.thumbnails");
            pruneDeadThumbnailFiles(volumeName, Audio.AlbumArt.getContentUri(volumeName), "/sdcard/sd/DCIM/albumthumbs");
        } else if( MediaStore.NAND_VOLUME.equals(volumeName) ) {
            pruneDeadThumbnailFiles(volumeName, Images.Thumbnails.getContentUri(volumeName), "/sdcard/DCIM/.thumbnails");
            pruneDeadThumbnailFiles(volumeName, Audio.AlbumArt.getContentUri(volumeName), "/sdcard/DCIM/albumthumbs");
        } else if( MediaStore.SCSI_VOLUME.equals(volumeName) ) { //VIEW_SCSI
            pruneDeadThumbnailFiles(volumeName, Images.Thumbnails.getContentUri(volumeName), "/sdcard/scsi/DCIM/.thumbnails");
            pruneDeadThumbnailFiles(volumeName, Audio.AlbumArt.getContentUri(volumeName), "/sdcard/scsi/DCIM/albumthumbs");
        }
    }

     private void postscan(String volumeName, String[] directories) throws RemoteException {

        Iterator<FileCacheEntry> iterator = mFileCache.values().iterator();
        String where;

        long t1 = System.currentTimeMillis();

        boolean bValidDatabase = MediaStore.isValidDatabase(volumeName);
        
        while (iterator.hasNext()) {
            FileCacheEntry entry = iterator.next();
            String path = entry.mPath;

            // remove database entries for files that no longer exist.
            boolean fileMissing = false;

            if (!entry.mSeenInFileSystem) {
                if (inScanDirectory(path, directories)) {
                    // we didn't see this file in the scan directory.
                    fileMissing = true;
                } else {
                    // the file is outside of our scan directory,
                    // so we need to check for file existence here.
                    File testFile = new File(path);
                    if (!testFile.exists()) {
                        fileMissing = true;
                    }
                }
            } 
            
            if (fileMissing) {
                // do not delete missing playlists, since they may have been modified by the user.
                // the user can delete them in the media player instead.
                // instead, clear the path and lastModified fields in the row
                MediaFile.MediaFileType mediaFileType = MediaFile.getFileType(path);
                int fileType = (mediaFileType == null ? 0 : mediaFileType.fileType);

                if (MediaFile.isPlayListFileType(fileType)) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Audio.Playlists.DATA, "");
                    values.put(MediaStore.Audio.Playlists.DATE_MODIFIED, 0);
                    mMediaProvider.update(ContentUris.withAppendedId(mPlaylistsUri, entry.mRowId), values, null, null);
                } else {
                    mDeleteCnt++;

                    mBulkDelete.delete(ContentUris.withAppendedId(entry.mTableUri, entry.mRowId));
                    //mMediaProvider.delete(ContentUris.withAppendedId(entry.mTableUri, entry.mRowId), null, null);
                    
                    iterator.remove();                    
                }
            } else if(bValidDatabase == false) {  
                // thumbnail requset process====================================================
                if( entry.mMiniThumbMagic == 0 && entry.mRowId != 0 ) {
                    if( mImagesUri.toString().equals(entry.mTableUri.toString()) ) {
                        ContentValues values = new ContentValues();
                        values.put("path", entry.mPath);
                        values.put("id", entry.mRowId);

                        //Log.d(TAG, "image thumbreq : value =" + values);
                        
                        mBulkInsert.insert(mImageThumbReqUri, values);
                    } else if( mVideoUri.toString().equals(entry.mTableUri.toString()) ) {
                        ContentValues values = new ContentValues();
                        values.put("path", entry.mPath);
                        values.put("id", entry.mRowId);
                        
                        //Log.d(TAG, "video thumbreq : value =" + values);
                        
                        mBulkInsert.insert(mVideoThumbReqUri, values);
                    }
                }
            }
            
        }

        mBulkDelete.flush();
        mBulkInsert.flush();
            
        long t2 = System.currentTimeMillis();
        mDBDeleteTime += (t2-t1);
        
        // handle playlists last, after we know what media files are on the storage.
        if (mProcessPlaylists) {
            processPlayLists();
        }

        long t3 = System.currentTimeMillis();
        
        //if (mOriginalCount == 0 )
        //    pruneDeadThumbnailFiles(mVolumeName);

        //long t4 = System.currentTimeMillis();

        // make albumart request================================================
        HashMap<String, Long> albumartCache = new HashMap<String, Long>();
        HashMap<Long, Long> albumartReqCache = new HashMap<Long, Long>();
        
        int index = 0;

        Uri AlbumArtUri = Audio.AlbumArt.getContentUri(volumeName);

        while( true ) {
            String limit;
            Uri uri;
            int bulkCount = 500;
            Cursor c = null;
            
            if( !Environment.isMounted(volumeName) ) {
                if( c != null) {
                    c.close();
                    c = null;
                }

                return;
            }

            limit = index + " , " + bulkCount;
            index += bulkCount;
            uri = AlbumArtUri.buildUpon().appendQueryParameter("limit", limit).build();
            c = mMediaProvider.query(uri, new String[] {"album_id", "_data"}, null, null, null);

            if ( c != null && c.moveToFirst() ) {
                try {
                    do {
                        long albumArtStatus = 1;
                        
                        albumartCache.put(c.getString(1), c.getLong(0));
                        albumartReqCache.put(c.getLong(0), albumArtStatus);
                    } while (c.moveToNext());
                } finally {
                    if(c.getCount() != bulkCount) {
                        c.close();
                        c = null;
                        break;
                    }
                    c.close();
                    c = null;
                }
            } else {

                if( c != null) c.close();
                c = null;
                break;
            }
            
        }

        where = null;            
        index = 0;

        while( true ) {

            String limit;
            Uri uri;
            int bulkCount = 500;
            Cursor c = null;
            
            if( Environment.isMounted(volumeName) == false ) {
                if( c != null ) {
                    c.close();
                    c = null;
                    albumartReqCache.clear();
                    albumartCache.clear();
                }
                return;
            }


            uri = MediaStore.Audio.Media.getContentUri(volumeName);

            limit = index + " , " + bulkCount;
            index += bulkCount;

            uri = uri.buildUpon().appendQueryParameter("limit", limit).build();
            
            c = mMediaProvider.query(uri, new String[] {"_data", "album_id"}, null, null, null);

            if (c != null && c.moveToFirst()) {
                do {

                    Long rowId = c.getLong(1);
                    Long temp = albumartReqCache.get(rowId);
                    long albumArtStatus = 2;

                    if(LOCAL_DEBUG_MSG) Log.v(TAG, "_data:" + c.getString(0) + " album_id:" + c.getLong(1) + " temp:" + temp);
                    
                    if( temp == null || temp != 2 )
                    {   
                        String path = c.getString(0);
                        
                        albumartReqCache.remove(rowId);
                        albumartReqCache.put(rowId, albumArtStatus);
                        
                        if( Environment.isMounted(volumeName) == false ) {
                            if( c != null ) {
                                c.close();
                                c = null;
                                albumartReqCache.clear();
                                albumartCache.clear();
                            }
                            return;
                        }

                        ContentValues values = new ContentValues();
                        values.put("path", c.getString(0));
                        values.put("album_id", c.getLong(1));
                        values.put("volume", volumeName); 
                        
                        //Log.d(TAG, "audio thumbreq : value =" + values);
                        
                        mBulkInsert.insert(mAlbumArtReqUri, values);
                    }
                }while (c.moveToNext());

                if( c.getCount() < bulkCount ) {
                    c.close();
                    c = null;
                    break;
                } else {
                    c.close();
                    c = null;
                }
            } else {
                if( c != null ) c.close();
                c = null;
                break;
            }
        }

        mBulkInsert.flush();
        albumartReqCache.clear();

        // prune albumart===============================================================

        File storgeFile = Environment.getStorageDirectory(volumeName);

        if( storgeFile != null ) {
            File[] files = new File(storgeFile, "albumthumbs").listFiles();
            for (int i = 0; files != null && i < files.length; i++) {
                albumartCache.remove(files[i].getPath());
            }
        }

        Iterator<String> albumartIterator = albumartCache.keySet().iterator();
        
        while (albumartIterator.hasNext()) {
            Long rowId;
            Uri delUri;
            String filename = albumartIterator.next();
            rowId = (Long)albumartCache.get(filename);

            delUri = ContentUris.withAppendedId(AlbumArtUri, rowId);
            mMediaProvider.delete(delUri, null, null);

        }

        albumartCache.clear();

        boolean imageMagicClearFlag = false;
        Uri imageUri = MediaStore.Images.Media.getContentUri(volumeName);

        if( !MediaStore.INTERNAL_VOLUME.equals(volumeName) ) {

            // prune image thumbnail for micro thumb========================================
            MiniThumbFile thumbFile = MiniThumbFile.instance(MediaStore.Images.Media.getContentUri(volumeName));
            File f = new File(thumbFile.getMiniThumbPath(volumeName));

            if( !f.exists() ) {
                imageMagicClearFlag = true;
                index = 0;
                while( true ) {
                    String limit;
                    Uri uri;
                    int bulkCount = 500;
                    Cursor c = null;
                    
                    if( !Environment.isMounted(volumeName) ) {
                        if( c != null) {
                            c.close();
                            c = null;
                        }

                        return;
                    }

                    limit = index + " , " + bulkCount;
                    index += bulkCount;

                    uri = imageUri.buildUpon().appendQueryParameter("limit", limit).build();
                    where = "mini_thumb_magic NOT NULL";
                    
                    c = mMediaProvider.query(uri, new String[] {"_id"}, where, null, null);

                    if ( c != null && c.moveToFirst() ) {
                        try {
                            do {
                                Long rowId;
                                Uri updateUri;

                                ContentValues values = new ContentValues();
                                values.put("mini_thumb_magic", "");

                                rowId = c.getLong(0);
                                updateUri = ContentUris.withAppendedId(imageUri, rowId);

                                mMediaProvider.update(updateUri, values, null, null);
                            } while (c.moveToNext());
                        } finally {
                            if(c.getCount() != bulkCount) {
                                c.close();
                                c = null;
                                break;
                            }
                            c.close();
                            c = null;
                        }
                    } else {

                        if( c != null) c.close();
                        c = null;
                        break;
                    }
                    
                }
            } 

            // prune video thumbnail========================================================
            Uri videoUri = MediaStore.Video.Media.getContentUri(volumeName);
            thumbFile = MiniThumbFile.instance(videoUri);
            f = new File(thumbFile.getMiniThumbPath(volumeName));

            if( !f.exists() ) {
                index = 0;
                
                while( true ) {
                    String limit;
                    Uri uri;
                    int bulkCount = 500;
                    Cursor c = null;
                    
                    if( !Environment.isMounted(volumeName) ) {
                        if( c != null) {
                            c.close();
                            c = null;
                        }

                        return;
                    }

                    limit = index + " , " + bulkCount;
                    index += bulkCount;
                    uri = videoUri.buildUpon().appendQueryParameter("limit", limit).build();
                    where = "mini_thumb_magic NOT NULL";
                    c = mMediaProvider.query(uri, new String[] {"_id"}, where, null, null);

                    if ( c != null && c.moveToFirst() ) {
                        try {
                            do {
                                Long rowId;
                                Uri updateUri;

                                ContentValues values = new ContentValues();
                                values.put("mini_thumb_magic", "");

                                rowId = c.getLong(0);
                                updateUri = ContentUris.withAppendedId(videoUri, rowId);

                                mMediaProvider.update(updateUri, values, null, null);
                            } while (c.moveToNext());
                        } finally {
                            if(c.getCount() != bulkCount) {
                                c.close();
                                c = null;
                                break;
                            }
                            c.close();
                            c = null;
                        }
                    } else {

                        if( c != null) c.close();
                        c = null;
                        break;
                    }
                    
                }
            } 
        }

        // prune image thumbnail for /DCIM/.thumbnail/ thumbnail path===================
        HashMap<String, Long> thumbnailCache = new HashMap<String, Long>();
        Uri thumbnailUri = MediaStore.Images.Thumbnails.getContentUri(volumeName);
        
        index = 0;

        while( true ) {
            String limit;
            Uri uri;
            int bulkCount = 500;
            Cursor c = null;
            
            if( !Environment.isMounted(volumeName) ) {
                if( c != null) {
                    c.close();
                    c = null;
                }

                return;
            }

            limit = index + " , " + bulkCount;
            index += bulkCount;
            uri = thumbnailUri.buildUpon().appendQueryParameter("limit", limit).build();
            
            c = mMediaProvider.query(uri, new String[] {"image_id", "_data"}, null, null, null);

            if ( c != null && c.moveToFirst() ) {
                try {
                    do {
                        thumbnailCache.put(c.getString(1), c.getLong(0));
                    } while (c.moveToNext());
                } finally {
                    if(c.getCount() != bulkCount) {
                        c.close();
                        c = null;
                        break;
                    }
                    c.close();
                    c = null;
                }
            } else {

                if( c != null) c.close();
                c = null;
                break;
            }
            
        }

        storgeFile = Environment.getStorageDirectory(volumeName);

        if( storgeFile != null ) {
            File[] files = new File(storgeFile, "DCIM/.thumbnails").listFiles();
            for (int i = 0; files != null && i < files.length; i++) {
                thumbnailCache.remove(files[i].getPath());
            }
        }

        Iterator<String> thumbnailIterator = thumbnailCache.keySet().iterator();
        
        while (thumbnailIterator.hasNext()) {
            Long rowId;
            Uri updateUri;
            Uri deleteUri;
            String filename = thumbnailIterator.next();

            rowId = (Long)thumbnailCache.get(filename);
            
            if( !imageMagicClearFlag ) {
                ContentValues values = new ContentValues();
                values.put("mini_thumb_magic", "");

                updateUri = ContentUris.withAppendedId(imageUri, rowId);
                mMediaProvider.update(updateUri, values, null, null);
            }
            
            deleteUri = ContentUris.withAppendedId(thumbnailUri, rowId);
            mMediaProvider.delete(deleteUri, null, null);
        }

        thumbnailCache.clear();

        if(LOCAL_DEBUG_MSG) Log.v(TAG, "postscan process update&delete :" + (t2-t1) + "ms \n");
        if(LOCAL_DEBUG_MSG) Log.v(TAG, "postscan process playlist :" + (t3-t2) + "ms \n");
        //if(LOCAL_DEBUG_MSG) Log.v(TAG, "postscan process prune Dead ThumbnailFiles :" + (t4-t3) + "ms \n");


        // allow GC to clean up
        mGenreCache = null;
        mPlayLists = null;
        mFileCache = null;
        mMediaProvider = null;
    }

    private void initialize(String volumeName) {
        mMediaProvider = mContext.getContentResolver().acquireProvider("media");

        mVolumeName = volumeName;
        mAudioUri = Audio.Media.getContentUri(volumeName);
        mVideoUri = Video.Media.getContentUri(volumeName);
        mImagesUri = Images.Media.getContentUri(volumeName);
        mThumbsUri = Images.Thumbnails.getContentUri(volumeName);

        mImageThumbReqUri = Images.Thumbnails.getThumbReqContentUri(volumeName);
        mVideoThumbReqUri = Video.Thumbnails.getThumbReqContentUri(volumeName);
        mAlbumArtReqUri = Audio.Media.getAlbumArtReqContentUri(volumeName);
        
        mBulkInsert.clear();
        mBulkDelete.clear();

        if (!volumeName.equals("internal")) {
            // we only support playlists on external media
            mProcessPlaylists = true;
            mProcessGenres = true;
            mGenreCache = new HashMap<String, Uri>();
            mGenresUri = Genres.getContentUri(volumeName);
            mPlaylistsUri = Playlists.getContentUri(volumeName);

            // assuming external storage is FAT (case insensitive), except on the simulator.
            if ( Process.supportsProcesses()) {
                mCaseInsensitivePaths = true;
            }
        }

        mDBInsertTime = 0;
        mProcessFileTime = 0;
        mInsertCnt = 0;
        mDBDeleteTime = 0;
        mDeleteCnt = 0;

        mMusicAddCount = 0;
    }

    public void scanDirectories(String[] directories, String volumeName) {
        try {
            long start = System.currentTimeMillis();

            initialize(volumeName);

            if( !MediaStore.isValidDatabase(volumeName) )
                MediaStore.setDatabaseStatus(volumeName, MediaStore.MEDIA_DB_PRESCAN);

            if(LOCAL_DEBUG_MSG) Log.d(TAG, "called prescan()\n");
            
            prescan(volumeName, null);
            long prescan_end = System.currentTimeMillis();

            if( !Environment.isMounted(volumeName) ) {
                return;
            }

            if(LOCAL_DEBUG_MSG) Log.d(TAG, "called processDirectory()\n");

            if( !MediaStore.isValidDatabase(volumeName) )
                MediaStore.setDatabaseStatus(volumeName, MediaStore.MEDIA_DB_SCANNING);
                
            for (int i = 0; i < directories.length; i++) {
                processDirectory(directories[i], MediaFile.sFileExtensions, mClient);
            }

            if( !Environment.isMounted(volumeName) ) {
                return;
            }

            long scan_end_first = System.currentTimeMillis();

            mBulkInsert.flush();

            long scan_end = System.currentTimeMillis();
            mDBInsertTime += (scan_end-scan_end_first);

            if( !Environment.isMounted(volumeName) ) {
                return;
            }

            if(LOCAL_DEBUG_MSG) Log.d(TAG, "called postscan()\n");

            if( !MediaStore.isValidDatabase(volumeName) )
                MediaStore.setDatabaseStatus(volumeName, MediaStore.MEDIA_DB_POSTSCAN);
                
            postscan(volumeName, directories);

            long postscan_end = System.currentTimeMillis();

            if( !Environment.isMounted(volumeName) ) {
                return;
            }

            if( MediaStore.getAvailableUnifiedDBCount() == 0 ) {
                if( MediaStore.isValidDatabase(MediaStore.NAND_VOLUME) ) {
                    syncUnifiedDatabase(MediaStore.NAND_VOLUME);
                }
            }

            syncUnifiedDatabase(volumeName);

            long end = System.currentTimeMillis();

            if (Config.LOGD) {
                Log.d(TAG, "prescan time              : " + (prescan_end - start) + "ms\n");
                Log.d(TAG, "scan time                 : " + (scan_end - prescan_end) + "ms\n");
                Log.d(TAG, "--insert/update total     : " + mInsertCnt + "ea \n");
                Log.d(TAG, "--DB Insert & Update time : " + mDBInsertTime + "ms (per one file " + (mInsertCnt!=0?mDBInsertTime/mInsertCnt:0)  + " ms) \n");
                Log.d(TAG, "--Extract Meta time       : " + mProcessFileTime + "ms (per one file " + (mInsertCnt!=0?mProcessFileTime/mInsertCnt:0)  + "ms) \n");
                Log.d(TAG, "postscan time             : " + (postscan_end - scan_end) + "ms\n");
                Log.d(TAG, "--delete total            : " + mDeleteCnt + "ea \n");
                Log.d(TAG, "--DB delete time          : " + mDBDeleteTime + "ms (per one file " + (mDeleteCnt!=0?mDBDeleteTime/mDeleteCnt:0)  + " ms) \n");
                Log.d(TAG, "unified db insert total   : " + mUnifiedDBCnt + "ea\n");
                Log.d(TAG, "--unified insert time     : " + (end - postscan_end) + "ms (one file insert : " + (mUnifiedDBCnt!=0?((end - postscan_end)/mUnifiedDBCnt):0)  + "ms) \n");
                Log.d(TAG, "Total time                : " + (end - start) + "ms\n");
            }
        } catch (SQLException e) {
            // this might happen if the SD card is removed while the media scanner is running
            Log.e(TAG, "SQLException in MediaScanner.scan()", e);
        } catch (UnsupportedOperationException e) {
            // this might happen if the SD card is removed while the media scanner is running
            Log.e(TAG, "UnsupportedOperationException in MediaScanner.scan()", e);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in MediaScanner.scan()", e);
        }
    }

    // this function is used to scan a single file
    public Uri scanSingleFile(String path, String volumeName, String mimeType) {
        try {
            initialize(volumeName);
            prescan(volumeName, path);

            File file = new File(path);
            // always scan the file, so we can return the content://media Uri for existing files
            return mClient.doScanFile(path, mimeType, file.lastModified(), file.length(), true, true);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in MediaScanner.scanFile()", e);
            return null;
        }
    }

    // returns the number of matching file/directory names, starting from the right
    private int matchPaths(String path1, String path2) {
        int result = 0;
        int end1 = path1.length();
        int end2 = path2.length();

        while (end1 > 0 && end2 > 0) {
            int slash1 = path1.lastIndexOf('/', end1 - 1);
            int slash2 = path2.lastIndexOf('/', end2 - 1);
            int backSlash1 = path1.lastIndexOf('\\', end1 - 1);
            int backSlash2 = path2.lastIndexOf('\\', end2 - 1);
            int start1 = (slash1 > backSlash1 ? slash1 : backSlash1);
            int start2 = (slash2 > backSlash2 ? slash2 : backSlash2);
            if (start1 < 0) start1 = 0; else start1++;
            if (start2 < 0) start2 = 0; else start2++;
            int length = end1 - start1;
            if (end2 - start2 != length) break;
            if (path1.regionMatches(true, start1, path2, start2, length)) {
                result++;
                end1 = start1 - 1;
                end2 = start2 - 1;
            } else break;
        }

        return result;
    }

    private boolean addPlayListEntry(String entry, String playListDirectory,
            Uri uri, ContentValues values, int index) {

        // watch for trailing whitespace
        int entryLength = entry.length();
        while (entryLength > 0 && Character.isWhitespace(entry.charAt(entryLength - 1))) entryLength--;
        // path should be longer than 3 characters.
        // avoid index out of bounds errors below by returning here.
        if (entryLength < 3) return false;
        if (entryLength < entry.length()) entry = entry.substring(0, entryLength);

        // does entry appear to be an absolute path?
        // look for Unix or DOS absolute paths
        char ch1 = entry.charAt(0);
        boolean fullPath = (ch1 == '/' ||
                (Character.isLetter(ch1) && entry.charAt(1) == ':' && entry.charAt(2) == '\\'));
        // if we have a relative path, combine entry with playListDirectory
        if (!fullPath)
            entry = playListDirectory + entry;

        //FIXME - should we look for "../" within the path?

        // best matching MediaFile for the play list entry
        FileCacheEntry bestMatch = null;

        // number of rightmost file/directory names for bestMatch
        int bestMatchLength = 0;

        Iterator<FileCacheEntry> iterator = mFileCache.values().iterator();
        while (iterator.hasNext()) {
            FileCacheEntry cacheEntry = iterator.next();
            String path = cacheEntry.mPath;

            if (path.equalsIgnoreCase(entry)) {
                bestMatch = cacheEntry;
                break;    // don't bother continuing search
            }

            int matchLength = matchPaths(path, entry);
            if (matchLength > bestMatchLength) {
                bestMatch = cacheEntry;
                bestMatchLength = matchLength;
            }
        }

        // if the match is not for an audio file, bail out
        if (bestMatch == null || ! mAudioUri.equals(bestMatch.mTableUri)) {
            return false;
        }

        try {
        // OK, now we need to add this to the database
            values.clear();
            values.put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, Integer.valueOf(index));
            values.put(MediaStore.Audio.Playlists.Members.AUDIO_ID, Long.valueOf(bestMatch.mRowId));
            mMediaProvider.insert(uri, values);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in MediaScanner.addPlayListEntry()", e);
            return false;
        }

        return true;
    }

    private void processM3uPlayList(String path, String playListDirectory, Uri uri, ContentValues values) {
        BufferedReader reader = null;
        try {
            File f = new File(path);
            if (f.exists()) {
                reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(f)), 8192);
                String line = reader.readLine();
                int index = 0;
                while (line != null) {
                    // ignore comment lines, which begin with '#'
                    if (line.length() > 0 && line.charAt(0) != '#') {
                        values.clear();
                        if (addPlayListEntry(line, playListDirectory, uri, values, index))
                            index++;
                    }
                    line = reader.readLine();
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "IOException in MediaScanner.processM3uPlayList()", e);
        } finally {
            try {
                if (reader != null)
                    reader.close();
            } catch (IOException e) {
                Log.e(TAG, "IOException in MediaScanner.processM3uPlayList()", e);
            }
        }
    }

    private void processPlsPlayList(String path, String playListDirectory, Uri uri, ContentValues values) {
        BufferedReader reader = null;
        try {
            File f = new File(path);
            if (f.exists()) {
                reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(f)), 8192);
                String line = reader.readLine();
                int index = 0;
                while (line != null) {
                    // ignore comment lines, which begin with '#'
                    if (line.startsWith("File")) {
                        int equals = line.indexOf('=');
                        if (equals > 0) {
                            values.clear();
                            if (addPlayListEntry(line.substring(equals + 1), playListDirectory, uri, values, index))
                                index++;
                        }
                    }
                    line = reader.readLine();
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "IOException in MediaScanner.processPlsPlayList()", e);
        } finally {
            try {
                if (reader != null)
                    reader.close();
            } catch (IOException e) {
                Log.e(TAG, "IOException in MediaScanner.processPlsPlayList()", e);
            }
        }
    }

    class WplHandler implements ElementListener {

        final ContentHandler handler;
        String playListDirectory;
        Uri uri;
        ContentValues values = new ContentValues();
        int index = 0;

        public WplHandler(String playListDirectory, Uri uri) {
            this.playListDirectory = playListDirectory;
            this.uri = uri;

            RootElement root = new RootElement("smil");
            Element body = root.getChild("body");
            Element seq = body.getChild("seq");
            Element media = seq.getChild("media");
            media.setElementListener(this);

            this.handler = root.getContentHandler();
        }

        public void start(Attributes attributes) {
            String path = attributes.getValue("", "src");
            if (path != null) {
                values.clear();
                if (addPlayListEntry(path, playListDirectory, uri, values, index)) {
                    index++;
                }
            }
        }

       public void end() {
       }

        ContentHandler getContentHandler() {
            return handler;
        }
    }

    private void processWplPlayList(String path, String playListDirectory, Uri uri) {
        FileInputStream fis = null;
        try {
            File f = new File(path);
            if (f.exists()) {
                fis = new FileInputStream(f);

                Xml.parse(fis, Xml.findEncodingByName("UTF-8"), new WplHandler(playListDirectory, uri).getContentHandler());
            }
        } catch (SAXException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (fis != null)
                    fis.close();
            } catch (IOException e) {
                Log.e(TAG, "IOException in MediaScanner.processWplPlayList()", e);
            }
        }
    }

    private void processPlayLists() throws RemoteException {
        Iterator<FileCacheEntry> iterator = mPlayLists.iterator();
        while (iterator.hasNext()) {
            FileCacheEntry entry = iterator.next();
            String path = entry.mPath;

            // only process playlist files if they are new or have been modified since the last scan
            if (entry.mLastModifiedChanged) {
                ContentValues values = new ContentValues();
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash < 0) throw new IllegalArgumentException("bad path " + path);
                Uri uri, membersUri;
                long rowId = entry.mRowId;
                if (rowId == 0) {
                    // Create a new playlist

                    int lastDot = path.lastIndexOf('.');
                    String name = (lastDot < 0 ? path.substring(lastSlash + 1) : path.substring(lastSlash + 1, lastDot));
                    values.put(MediaStore.Audio.Playlists.NAME, name);
                    values.put(MediaStore.Audio.Playlists.DATA, path);
                    values.put(MediaStore.Audio.Playlists.DATE_MODIFIED, entry.mLastModified);
                    uri = mMediaProvider.insert(mPlaylistsUri, values);
                    rowId = ContentUris.parseId(uri);
                    membersUri = Uri.withAppendedPath(uri, Playlists.Members.CONTENT_DIRECTORY);
                } else {
                    uri = ContentUris.withAppendedId(mPlaylistsUri, rowId);

                    // update lastModified value of existing playlist
                    values.put(MediaStore.Audio.Playlists.DATE_MODIFIED, entry.mLastModified);
                    mMediaProvider.update(uri, values, null, null);

                    // delete members of existing playlist
                    membersUri = Uri.withAppendedPath(uri, Playlists.Members.CONTENT_DIRECTORY);
                    mMediaProvider.delete(membersUri, null, null);
                }

                String playListDirectory = path.substring(0, lastSlash + 1);
                MediaFile.MediaFileType mediaFileType = MediaFile.getFileType(path);
                int fileType = (mediaFileType == null ? 0 : mediaFileType.fileType);

                if (fileType == MediaFile.FILE_TYPE_M3U)
                    processM3uPlayList(path, playListDirectory, membersUri, values);
                else if (fileType == MediaFile.FILE_TYPE_PLS)
                    processPlsPlayList(path, playListDirectory, membersUri, values);
                else if (fileType == MediaFile.FILE_TYPE_WPL)
                    processWplPlayList(path, playListDirectory, membersUri);

                Cursor cursor = mMediaProvider.query(membersUri, PLAYLIST_MEMBERS_PROJECTION, null,
                        null, null);
                try {
                    if (cursor == null || cursor.getCount() == 0) {
                        Log.d(TAG, "playlist is empty - deleting");
                        mMediaProvider.delete(uri, null, null);
                    }
                } finally {
                    if (cursor != null) cursor.close();
                }
            }
        }
    }

    private native void processDirectory(String path, String extensions, MediaScannerClient client);
    private native void processFile(String path, String mimeType, MediaScannerClient client);
    public native void setLocale(String locale);

    public native byte[] extractAlbumArt(FileDescriptor fd);

    private static native final void native_init();
    private native final void native_setup();
    private native final void native_finalize();
    private static final boolean LOCAL_DEBUG_MSG = false;
    
    @Override
    protected void finalize() {
        mContext.getContentResolver().releaseProvider(mMediaProvider);
        native_finalize();
    }
}
