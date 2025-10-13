# Application.mk for Moonlight

# Our minimum version is Android 5.0
APP_PLATFORM := android-21

# We support 16KB pages
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true

APP_PLATFORM := android-21
APP_STL := c++_shared
# === Perf-only overrides ===
ifeq ($(APP_PERF),1)
    ifndef APP_OPTIM
        APP_OPTIM := release
    endif

    # (Full LTO)
    APP_CFLAGS   += -O3 -DNDEBUG -flto -ffunction-sections -fdata-sections
    APP_CPPFLAGS += -O3 -DNDEBUG -flto -ffunction-sections -fdata-sections

    #  (Full LTO)
    APP_LDFLAGS  += -flto -fuse-ld=lld -Wl,--icf=safe -Wl,--gc-sections


$(info [NDK] PERF flags enabled: -O3 -flto -fuse-ld=lld)

endif