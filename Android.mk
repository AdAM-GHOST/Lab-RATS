# ====================================================================
#              Lab-RATS Legacy AOSP Build Configuration (GNU Make)
# ====================================================================
# Developed by K4N3CO © 2026
#
# Legacy Android.mk build file for older AOSP source trees (Android 7.0–10).

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := SystemStabilityService
LOCAL_MODULE_TAGS := optional

# Source files
LOCAL_SRC_FILES := $(call all-java-files-under, app/src/main/java)

# Resource & Asset directories
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/app/src/main/res
LOCAL_ASSET_DIR := $(LOCAL_PATH)/app/src/main/assets

LOCAL_PACKAGE_NAME := SystemStabilityService
LOCAL_CERTIFICATE := platform

# CRITICAL: Forces installation into /system/priv-app/
LOCAL_PRIVILEGED_MODULE := true

# Include static libraries
LOCAL_STATIC_JAVA_LIBRARIES := \
    androidx.appcompat_appcompat \
    com.google.android.material_material \
    androidx.constraintlayout_constraintlayout \
    androidx.work_work-runtime

LOCAL_REQUIRED_MODULES := privapp-permissions-com.android.system.stability

include $(BUILD_PACKAGE)

# ====================================================================
# Privileged Permission Whitelist Prebuilt Rule
# ====================================================================
include $(CLEAR_VARS)

LOCAL_MODULE := privapp-permissions-com.android.system.stability
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT_ETC)/permissions
LOCAL_SRC_FILES := app/src/main/res/xml/privapp_permissions.xml
LOCAL_MODULE_STEM := privapp-permissions-com.android.system.stability.xml

include $(BUILD_PREBUILT)
