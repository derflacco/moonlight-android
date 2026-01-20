# Application.mk for Moonlight

# Our minimum version is Android 5.0
APP_PLATFORM := android-21

# We support 16KB pages
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true

APP_STL := c++_shared

APP_PLATFORM := android-21
APP_STL := c++_shared

# ARM ABI support
APP_ABI := arm64-v8a armeabi-v7a

# === Perf-only overrides ===
ifeq ($(APP_PERF),1)
    ifndef APP_OPTIM
        APP_OPTIM := release
    endif

    # LTO mode: default = ThinLTO; override with APP_LTO_FULL=1 or APP_LTO_NONE=1
    ifeq ($(APP_LTO_NONE),1)
        $(info [NDK] PERF: LTO disabled)
    else ifeq ($(APP_LTO_FULL),1)
        APP_CFLAGS   += -flto
        APP_CPPFLAGS += -flto
        # (1) LTO backend a O3
        APP_LDFLAGS  += -flto -fuse-ld=lld -Wl,--icf=safe -Wl,--gc-sections -Wl,--lto-O3
        $(info [NDK] PERF: Full LTO (+lto-O3))
    else
        APP_CFLAGS   += -flto=thin
        APP_CPPFLAGS += -flto=thin
        # (1) LTO backend a O3
        APP_LDFLAGS  += -flto=thin -fuse-ld=lld -Wl,--icf=safe -Wl,--gc-sections -Wl,--lto-O3
        $(info [NDK] PERF: ThinLTO (+lto-O3))
    endif

    # Base perf flags
    APP_CFLAGS   += -O3 -DNDEBUG -ffunction-sections -fdata-sections
    APP_CPPFLAGS += -O3 -DNDEBUG -ffunction-sections -fdata-sections

    # AArch64: uses LSE when available (safe fallback)
    ifneq (,$(findstring arm64-v8a,$(APP_ABI)))
        APP_CFLAGS   += -moutline-atomics
        APP_CPPFLAGS += -moutline-atomics
    endif
endif
