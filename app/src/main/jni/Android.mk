# Save app jni dir before importing other modules
APP_JNI_DIR := $(call my-dir)
include $(call all-subdir-makefiles)
# Include our cpuaffinity module using the known app jni dir
include $(APP_JNI_DIR)/Android.cpuaffinity.mk

LOCAL_CFLAGS   := $(filter-out -O%,$(LOCAL_CFLAGS)) -O3
LOCAL_CPPFLAGS := $(filter-out -O%,$(LOCAL_CPPFLAGS)) -O3