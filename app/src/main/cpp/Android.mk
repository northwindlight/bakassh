LOCAL_PATH := $(call my-dir)

# libvterm 的源码放在 cpp/libvterm/（首次构建前用 README 里那条命令拉取，MIT 许可）
# 它是纯 C99、零依赖，所以这里只是一个静态库 + 我们的 JNI 桥。

include $(CLEAR_VARS)
LOCAL_MODULE := vterm
LOCAL_SRC_FILES := \
    libvterm/src/vterm.c \
    libvterm/src/parser.c \
    libvterm/src/state.c \
    libvterm/src/screen.c \
    libvterm/src/pen.c \
    libvterm/src/keyboard.c \
    libvterm/src/mouse.c \
    libvterm/src/unicode.c \
    libvterm/src/encoding.c
LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/libvterm/include \
    $(LOCAL_PATH)/libvterm/src
LOCAL_CFLAGS := -std=c11 -O2 -fPIC -Wall
include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := bakasshterm
LOCAL_SRC_FILES := libvterm_jni.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/libvterm/include
LOCAL_STATIC_LIBRARIES := vterm
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -std=c11 -O2 -fPIC -Wall
include $(BUILD_SHARED_LIBRARY)
