/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Hashtable;
import android.provider.MediaStore;

/**
 * This class handles the mini-thumb file. A mini-thumb file consists
 * of blocks, indexed by id. Each block has BYTES_PER_MINTHUMB bytes in the
 * following format:
 *
 * 1 byte status (0 = empty, 1 = mini-thumb available)
 * 8 bytes magic (a magic number to match what's in the database)
 * 4 bytes data length (LEN)
 * LEN bytes jpeg data
 * (the remaining bytes are unused)
 *
 * @hide This file is shared between MediaStore and MediaProvider and should remained internal use
 *       only.
 */
public class MiniThumbFile {
    private static final String TAG = "MiniThumbFile";
    private static final int MINI_THUMB_DATA_FILE_VERSION = 3;
    public static final int BYTES_PER_MINTHUMB = 10000;
    private static final int HEADER_SIZE = 1 + 8 + 4;
    private Uri mUri;
    private RandomAccessFile mMiniThumbFile = null;
    private FileChannel mChannel = null;
    private ByteBuffer mBuffer;
    private static Hashtable<String, MiniThumbFile> sThumbFiles =
        new Hashtable<String, MiniThumbFile>();

    private static final boolean LOCAL_DEBUG_MSG = false;
    /**
     * We store different types of thumbnails in different files. To remain backward compatibility,
     * we should hashcode of content://media/external/images/media remains the same.
     */
    public static synchronized void reset() {
        for (MiniThumbFile file : sThumbFiles.values()) {
            file.deactivate();
        }
        sThumbFiles.clear();
    }

    public static synchronized MiniThumbFile instance(Uri uri) {
        String type = uri.getPathSegments().get(0) + "/" + uri.getPathSegments().get(1);
        // Log.v(TAG, "get minithumbfile for type: "+type);

        
        if(LOCAL_DEBUG_MSG) {
            int cnt = sThumbFiles.size();
            Log.e(TAG, "sThumbFiles size is " + cnt);

            try {
                String pid = new File("/proc/self").getCanonicalFile().getName();
                Log.e(TAG, "pid " + pid);

            } catch (IOException e) {
                
            }
        }

        synchronized (sThumbFiles) {
            MiniThumbFile file = sThumbFiles.get(type);
            
            if (file == null) {

                if( !Environment.isMounted(uri.getPathSegments().get(0)) ) {
                    Log.e(TAG, "MiniThumbFile.instance not mounted volume " + uri.getPathSegments().get(0));
                    return null;
                }
            
                file = new MiniThumbFile(
                        Uri.parse("content://media/" + type + "/media"));
                sThumbFiles.put(type, file);

                if(LOCAL_DEBUG_MSG) Log.e(TAG, "sThumbFiles.put type" + type);
            } else {
                if( !Environment.isMounted(uri.getPathSegments().get(0)) ) {
                    file.deactivate();
                    Log.e(TAG, "MiniThumbFile instance called file.deactive. uri is " + uri);
                    return null;
                }
            }
        
            if(LOCAL_DEBUG_MSG) Log.v(TAG, "instance uri:" + uri);

            return file;
        }

    }

    private String randomAccessFilePath(int version, String volumeName) {

        String directoryName;

        if( MediaStore.SDCARD_VOLUME.equals(volumeName) )
        {
            directoryName =
                Environment.getSdcardStorageDirectory().toString()
                        + "/DCIM";
        }
        else if( MediaStore.SCSI_VOLUME.equals(volumeName)) //VIEW_SCSI
        {
            directoryName = 
                Environment.getScsiStorageDirectory().toString()
                    + "/DCIM";
        }
        else if( MediaStore.NAND_VOLUME.equals(volumeName))
        {
            directoryName = 
                Environment.getNandStorageDirectory().toString()
                    + "/DCIM";
        }
        else
            throw new UnsupportedOperationException(
                    "Unknown or unsupported volumeName: " + volumeName);

        return directoryName + "/.thumbdata" + version + "-" + mUri.hashCode();
    }

    private void removeOldFile(String volumeName) {
        String oldPath = randomAccessFilePath(MINI_THUMB_DATA_FILE_VERSION - 1, volumeName);
        File oldFile = new File(oldPath);
        if (oldFile.exists()) {
            try {

                //Log.e(TAG, "removeOldFile file: " + oldPath );

                oldFile.delete();
            } catch (SecurityException ex) {
                // ignore
            }
        }
    }

    private RandomAccessFile miniThumbDataFile(String volumeName) {

        if(LOCAL_DEBUG_MSG) Log.v(TAG, "miniThumbDataFile volume : " + volumeName +" mUri: " + mUri );

        if( !Environment.isMounted(volumeName) ) {
            Log.e(TAG, "miniThumbDataFile not mounted volume " + volumeName);
            return null;
        }

        if (mMiniThumbFile == null) {
            removeOldFile(volumeName);
            String path = randomAccessFilePath(MINI_THUMB_DATA_FILE_VERSION, volumeName);
            File directory = new File(path).getParentFile();
            if (!directory.isDirectory()) {
                if (!directory.mkdirs()) {
                    Log.e(TAG, "Unable to create .thumbnails directory "
                            + directory.toString());
                }
            }
            File f = new File(path);
            try {
                mMiniThumbFile = new RandomAccessFile(f, "rw");
            } catch (IOException ex) {
                // Open as read-only so we can at least read the existing
                // thumbnails.
                try {
                    mMiniThumbFile = new RandomAccessFile(f, "r");
                } catch (IOException ex2) {
                    // ignore exception
                }
            }
            if (mMiniThumbFile != null) {
                try {
                    if( mChannel != null ) {
                        mChannel.close();
                        mChannel = null;
                    }
                } catch (IOException ex3) {
                    // ignore exception
                }
                
                mChannel = mMiniThumbFile.getChannel();
            }
            else
                throw new UnsupportedOperationException(
                    "mChannel archive fail: " + path + " mUri: " + mUri);

        }
        return mMiniThumbFile;
    }
    
    public MiniThumbFile(Uri uri) {
        //Log.e(TAG, "new MiniThumbFile " + uri);

        mUri = uri;
        mBuffer = ByteBuffer.allocateDirect(BYTES_PER_MINTHUMB);
    }

    public synchronized void deactivate() {

        Log.e(TAG, "deactivate mUri " + mUri);

        try {
            if( mChannel != null ) {
                mChannel.close();
                mChannel = null;
            }
        } catch (IOException ex3) {
            // ignore exception
            throw new IllegalArgumentException();                    
        }

        try {
            if (mMiniThumbFile != null) {
                mMiniThumbFile.close();
                mMiniThumbFile = null;
            }
        } catch (IOException ex) {
            // ignore exception
            throw new IllegalArgumentException();	                
        }
    }

    // Get the magic number for the specified id in the mini-thumb file.
    // Returns 0 if the magic is not available.
    public synchronized long getMagic(long id, String volumeName) {
        // check the mini thumb file for the right data.  Right is
        // defined as having the right magic number at the offset
        // reserved for this "id".
        if( !Environment.isMounted(volumeName) ) {
            Log.e(TAG, "getMagic not mounted volume " + volumeName);
            return 0;
        }
        
        RandomAccessFile r = miniThumbDataFile(volumeName);

        if(LOCAL_DEBUG_MSG) Log.d(TAG, "getMagic Start : id:"+id + " volume:"+volumeName + " uri:"+mUri);

        if( id >= 0x40000000 )
        {
            throw new UnsupportedOperationException(
                    "Unknown or unsupported id: " + id);
        }

        
        if (r != null) {
            long pos = id * BYTES_PER_MINTHUMB;
            FileLock lock = null;

            try {

                mBuffer.clear();
                mBuffer.limit(1 + 8);

                if( !Environment.isMounted(volumeName) ) {
                    Log.e(TAG, "getMagic not mounted volume " + volumeName);
                    return 0;
                }

                lock = mChannel.lock(pos, 1 + 8, true);
                // check that we can read the following 9 bytes
                // (1 for the "status" and 8 for the long)

                if (mChannel.read(mBuffer, pos) == 9) {
                    mBuffer.position(0);
                    if (mBuffer.get() == 1) {
                        if(LOCAL_DEBUG_MSG) Log.d(TAG, "getMagic end : id:"+id + " volume:"+volumeName + " uri:"+mUri);
                    
                        return mBuffer.getLong();
                    }
                    else
                    {
                        if(LOCAL_DEBUG_MSG) Log.e(TAG, "getMagic:mBuffer.get() fail! mUri " + mUri + " id=" + id);
                    }
                }
                else
                {
                    Log.e(TAG, "getMagic:channel.read(mBuffer, pos) fail! mUri" + mUri + " id=" + id);
                }

            } catch (IOException ex) {
                Log.v(TAG, "Got exception checking file magic: ", ex);
            } catch (RuntimeException ex) {
                // Other NIO related exception like disk full, read only channel..etc
                Log.e(TAG, "Got exception when reading magic, id = " + id +
                        ", disk full or mount read-only? " + ex.getClass());
            } finally {
                try {
                    if (lock != null) lock.release();
                }
                catch (IOException ex) {
                    // ignore it.
                }
            }
        }
        else
        {
            Log.e(TAG, "getMagic:miniThumbDataFile() fail!");
        }

        if(LOCAL_DEBUG_MSG) Log.d(TAG, "getMagic end : id:"+id + " volume:"+volumeName + " uri:"+mUri);

        return 0;
    }

    public synchronized void saveMiniThumbToFile(byte[] data, long id, long magic, String volumeName)
            throws IOException {

        if( !Environment.isMounted(volumeName) ) {
            Log.e(TAG, "saveMiniThumbToFile not mounted volume " + volumeName);
            return;
        }
            
        RandomAccessFile r = miniThumbDataFile(volumeName);

        if(LOCAL_DEBUG_MSG) Log.d(TAG, "saveMiniThumbToFile start: id:"+id + " volume:"+volumeName + " uri:"+mUri);

        if (r == null) {
            Log.e(TAG, "saveMiniThumbToFile:RandomAccessFile aquire fail!");
            return;
        }

        long pos = id * BYTES_PER_MINTHUMB;
        FileLock lock = null;
        try {
            if( magic == 0xffffffff ) {
                mBuffer.clear();
                mBuffer.put((byte) 1);
                mBuffer.putLong(magic);
                mBuffer.flip();

                lock = mChannel.lock(pos, BYTES_PER_MINTHUMB, false);
                long writeSize = mChannel.write(mBuffer, pos);

                mBuffer.clear();
            } else if (data != null) {
                if (data.length > BYTES_PER_MINTHUMB - HEADER_SIZE) {
                    // not enough space to store it.
                    Log.d(TAG, "saveMiniThumbToFile end: id:"+id + " volume:"+volumeName + " uri:"+mUri);
                    
                    return;
                }
                
                mBuffer.clear();
                mBuffer.put((byte) 1);
                mBuffer.putLong(magic);
                mBuffer.putInt(data.length);
                mBuffer.put(data);
                mBuffer.flip();

                lock = mChannel.lock(pos, BYTES_PER_MINTHUMB, false);
                long writeSize = mChannel.write(mBuffer, pos);

                mBuffer.clear();

                //Log.d(TAG, "saveMiniThumbToFile: writeSize:"+writeSize);
            }
            else
            {
                throw new UnsupportedOperationException(
                        "saveMiniThumbToFile : data is null");
            }
        } catch (IOException ex) {
            Log.e(TAG, "couldn't save mini thumbnail data for "
                    + id + "; ", ex);
            throw ex;
        } catch (RuntimeException ex) {
            // Other NIO related exception like disk full, read only channel..etc
            Log.e(TAG, "couldn't save mini thumbnail data for "
                    + id + "; disk full or mount read-only? " + ex.getClass());
        } finally {
            try {
                if (lock != null) lock.release();
            }
            catch (IOException ex) {
                // ignore it.
            }
        }

        if(LOCAL_DEBUG_MSG) Log.d(TAG, "saveMiniThumbToFile end: id:"+id + " volume:"+volumeName + " uri:"+mUri);
    }

    /**
     * Gallery app can use this method to retrieve mini-thumbnail. Full size
     * images share the same IDs with their corresponding thumbnails.
     *
     * @param id the ID of the image (same of full size image).
     * @param data the buffer to store mini-thumbnail.
     */
    public synchronized byte [] getMiniThumbFromFile(long id, byte [] data, String volumeName) {
        RandomAccessFile r = miniThumbDataFile(volumeName);

        if( !Environment.isMounted(volumeName) ) {
            Log.e(TAG, "getMiniThumbFromFile not mounted volume " + volumeName);
            return null;
        }

        if (r == null) return null;

        if(LOCAL_DEBUG_MSG) Log.d(TAG, "getMiniThumbFromFile start: id:"+id + " volume:"+volumeName + " uri:"+mUri);
        
        long pos = id * BYTES_PER_MINTHUMB;
        FileLock lock = null;
        try {
            int size = 0;
            
            mBuffer.clear();

            lock = mChannel.lock(pos, BYTES_PER_MINTHUMB, true);
            size = mChannel.read(mBuffer, pos);
            
            if (size > 1 + 8 + 4) { // flag, magic, length
                mBuffer.position(0);
                byte flag = mBuffer.get();
                long magic = mBuffer.getLong();
                int length = mBuffer.getInt();

                if (size >= 1 + 8 + 4 + length && data.length >= length) {
                    mBuffer.get(data, 0, length);

                    if(LOCAL_DEBUG_MSG) Log.d(TAG, "getMiniThumbFromFile end: id:"+id + " volume:"+volumeName + " uri:"+mUri);
                    
                    return data;
                }
            }
        } catch (IOException ex) {
            Log.w(TAG, "got exception when reading thumbnail id=" + id + ", exception: " + ex);
        } catch (RuntimeException ex) {
            // Other NIO related exception like disk full, read only channel..etc
            Log.e(TAG, "Got exception when reading thumbnail, id = " + id +
                    ", disk full or mount read-only? " + ex.getClass());
        } finally {
            try {
                if (lock != null) lock.release();
            }
            catch (IOException ex) {
                // ignore it.
            }
        }

        if(LOCAL_DEBUG_MSG) Log.d(TAG, "getMiniThumbFromFile end: id:"+id + " volume:"+volumeName + " uri:"+mUri);
        
        return null;
    }

    public String getMiniThumbPath(String volumeName) {
        String path = randomAccessFilePath(MINI_THUMB_DATA_FILE_VERSION, volumeName);
        return path;
    }

}
