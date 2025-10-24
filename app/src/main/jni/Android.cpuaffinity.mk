# Android.cpuaffinity.mk — robust include even if LOCAL_PATH gets polluted by imported modules
# This computes the directory of THIS makefile independently of the caller.

# Resolve this file's absolute path and directory
CPUAFF_THIS_MK := $(abspath $(lastword $(MAKEFILE_LIST)))
CPUAFF_DIR     := $(patsubst %/,%,$(dir $(CPUAFF_THIS_MK)))

# Use this module's directory as LOCAL_PATH
LOCAL_PATH := $(CPUAFF_DIR)

include $(CLEAR_VARS)
LOCAL_MODULE := cpuaffinity

# Prefer cpuaffinity.cpp in jni/, fallback to ../cpp/ (both paths are relative to CPUAFF_DIR)
ifeq ($(wildcard $(CPUAFF_DIR)/cpuaffinity.cpp),)
  LOCAL_SRC_FILES := ../cpp/cpuaffinity.cpp
else
  LOCAL_SRC_FILES := cpuaffinity.cpp
endif

LOCAL_CPPFLAGS := -std=c++14 -O3
LOCAL_LDLIBS  := -llog
include $(BUILD_SHARED_LIBRARY)
