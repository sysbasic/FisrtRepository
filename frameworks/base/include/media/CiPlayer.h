#pragma once

#include <media/MediaPlayerInterface.h>
#include <utils/Errors.h>
#include <media/MediaMetadataRetrieverInterface.h>

using android::INVALID_OPERATION;
using android::ISurface;
using android::MediaPlayerBase;
using android::OK;
using android::Parcel;
using android::SortedVector;
using android::PV_PLAYER;
using android::UNKNOWN_ERROR;
using android::player_type;
using android::sp;
using android::status_t;
using android::String8;
using android::KeyedVector;

class CCiPlayerImpl;
class CiMetadataRetrieverImpl;

namespace android
{
class CiPlayer : public MediaPlayerHWInterface
{
public:
	CiPlayer();
    virtual ~CiPlayer();

	virtual status_t initCheck();
	virtual bool hardwareOutput();

    virtual status_t setDataSource(const char *url, const KeyedVector<String8, String8> *);

    virtual status_t    setDataSource(int fd, int64_t offset, int64_t length);
    virtual status_t    setVideoSurface(const sp<ISurface>& surface);
    virtual status_t    prepare(); // check media codecs initialization
    virtual status_t    prepareAsync(); // check media codecs initialization <ASYNC>
    virtual status_t    start();  // play
    virtual status_t    stop();
    virtual status_t    pause();
    virtual bool        isPlaying();  // is playing
    virtual status_t    seekTo(int msec);
    virtual status_t    getCurrentPosition(int *msec);
    virtual status_t    getDuration(int *msec);
    virtual status_t    reset();  // release opened media content
    virtual status_t    setLooping(int loop);
	virtual player_type playerType();
	virtual status_t    invoke(const Parcel& request, Parcel *reply);

	virtual status_t    setVolume(float leftVolume, float rightVolume);
    virtual status_t    setAudioStreamType(int streamType);
private:
	CCiPlayerImpl * m_player;
};

class CiMetadataRetriever : public MediaMetadataRetrieverInterface 
{
public:
    CiMetadataRetriever();
    virtual ~CiMetadataRetriever();

    virtual status_t setDataSource(const char *url);
    virtual status_t setDataSource(int fd, int64_t offset, int64_t length);

    virtual VideoFrame *captureFrame();
    virtual MediaAlbumArt *extractAlbumArt();
    virtual const char *extractMetadata(int keyCode);

private:
	CiMetadataRetrieverImpl * m_retriever;
};

}