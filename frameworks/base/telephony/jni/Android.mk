LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    com_android_internal_telephony_GSMPhone.cpp \
    control.cpp


LOCAL_C_INCLUDES += \
	$(JNI_H_INCLUDE)

LOCAL_SHARED_LIBRARIES := \
	libnativehelper \
	libcutils  \
     	libandroid_runtime 

ifeq ($(TARGET_SIMULATOR),true)
ifeq ($(TARGET_OS),linux)
ifeq ($(TARGET_ARCH),x86)
LOCAL_LDLIBS += -lpthread -ldl -lrt
endif
endif
endif

ifeq ($(WITH_MALLOC_LEAK_CHECK),true)
	LOCAL_CFLAGS += -DMALLOC_LEAK_CHECK
endif

LOCAL_PRELINK_MODULE := false
LOCAL_MODULE:= libtelephony

include $(BUILD_SHARED_LIBRARY)
   

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    com_android_internal_telephony_CDMAPhone.cpp \
    control.cpp

LOCAL_C_INCLUDES += \
	G$(JNI_H_INCLUDE)

LOCAL_SHARED_LIBRARIES := \
	libnativehelper \
	libcutils  \
     	libandroid_runtime 

ifeq ($(TARGET_SIMULATOR),true)
ifeq ($(TARGET_OS),linux)
ifeq ($(TARGET_ARCH),x86)
LOCAL_LDLIBS += -lpthread -ldl -lrt
endif
endif
endif

ifeq ($(WITH_MALLOC_LEAK_CHECK),true)
	LOCAL_CFLAGS += -DMALLOC_LEAK_CHECK
endif

LOCAL_PRELINK_MODULE := false
LOCAL_MODULE:= libtelephonycdma

include $(BUILD_SHARED_LIBRARY)
  
