package com.coara.expdbextractor;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends AppCompatActivity {

    private Button btnSelect, btnExtract;
    private TextView tvSelected, tvLog;
    private ActivityResultLauncher<String> pickFileLauncher;
    private File tempExpdb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnSelect = findViewById(R.id.btn_select);
        btnExtract = findViewById(R.id.btn_extract);
        tvSelected = findViewById(R.id.tv_selected);
        tvLog = findViewById(R.id.tv_log);

        pickFileLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                this::handleFileSelected
        );

        btnSelect.setOnClickListener(v -> pickFileLauncher.launch("*/*"));

        btnExtract.setOnClickListener(v -> {
            if (tempExpdb != null && tempExpdb.exists()) {
                startExtraction();
            } else {
                Toast.makeText(this, "まずexpdb.binを選択してください", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void handleFileSelected(Uri uri) {
        if (uri != null) {
            tempExpdb = new File(getCacheDir(), "temp_expdb.bin");
            copyUriToTemp(uri, tempExpdb);
            tvSelected.setText("選択済: " + uri.getLastPathSegment());
            btnExtract.setEnabled(true);
            tvLog.setText("準備完了\n");
        }
    }

    private void copyUriToTemp(Uri uri, File dest) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
            Toast.makeText(this, "expdbをアプリ内に一時コピーしました", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "コピー失敗: " + e.getMessage(), Toast.LENGTH_LONG).show();
            tempExpdb = null;
        }
    }

    private void startExtraction() {
        tvLog.append("抽出処理を開始します...\n");
        new Thread(this::performExtraction).start();
    }

    private void performExtraction() {
        try {
            byte[] data = readFileToBytes(tempExpdb);

            if (data.length < 0x28 + 64) {
                updateLog("Error: ファイルが小さすぎます");
                return;
            }

            ByteBuffer hdr = ByteBuffer.wrap(data, 0, 40).order(ByteOrder.LITTLE_ENDIAN);
            long magic = hdr.getInt() & 0xFFFFFFFFL;
            int version = hdr.getInt();

            boolean headerValid = (magic == 0xaee0deadL && version == 0x10);

            if (!headerValid) {
                updateLog("Error: Magic/Version不一致");
            } else {
                updateLog(" expdb有効 (ヘッダー正常)");
            }

            List<File> extractedFiles = new ArrayList<>();
            int count = 0;

            if (headerValid) {
                for (int i = 0; i < 32; i++) {
                    int dh_off = 0x28 + i * 64;
                    if (dh_off + 64 > data.length) break;

                    ByteBuffer dh = ByteBuffer.wrap(data, dh_off, 64).order(ByteOrder.LITTLE_ENDIAN);
                    dh.getInt();
                    int valid = dh.getInt();
                    int off = dh.getInt();
                    int used = dh.getInt();
                    dh.getInt();
                    dh.getInt();
                    dh.getLong();

                    byte[] name_b = new byte[32];
                    dh.get(name_b);

                    String name = getCleanName(name_b);
                    if (valid != 1 || used == 0) continue;

                    byte[] section = Arrays.copyOfRange(data, off, off + used);
                    File saved = processAndSave(section, String.format("expdb_%02d_%s", i, name), extractedFiles);
                    if (saved != null) {
                        count++;
                        updateLog("抽出完了 → " + saved.getName() + " (size=" + used + " bytes)");
                    }
                }
            }

            int uart_offset = 0x800000;
            if (data.length > uart_offset + 1024) {
                byte[] uart_raw = Arrays.copyOfRange(data, uart_offset, data.length);
                File saved = processAndSave(uart_raw, "expdb_09_UART_LOG", extractedFiles);
                if (saved != null) {
                    count++;
                    updateLog("抽出完了 → " + saved.getName() + " (fixed offset=0x800000, size=" + saved.length() + " bytes)");
                }
            }

            updateLog("\n合計 " + count + " 個の領域を抽出完了！");

            if (!extractedFiles.isEmpty()) {
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                String zipName = "expdb_extracted_" + timestamp + ".zip";
                File tempZip = new File(getCacheDir(), "temp_" + zipName);

                createZip(extractedFiles, tempZip);
                saveZipToDownload(tempZip, zipName);

                for (File f : extractedFiles) f.delete();
                tempZip.delete();
            }

        } catch (Exception e) {
            updateLog("エラー: " + e.getMessage());
        }
    }

    private String getCleanName(byte[] name_b) {
        int len = name_b.length;
        while (len > 0 && name_b[len - 1] == 0) len--;
        String name = (len == 0) ? "unnamed" : new String(name_b, 0, len, java.nio.charset.StandardCharsets.UTF_8);
        return name.trim();
    }

    private File processAndSave(byte[] section, String baseName, List<File> extractedFiles) {
        if (isAllFF(section)) {
            updateLog("スキップ: " + baseName + " （全0xFF領域）");
            return null;
        }

        byte[] trimmed;
        if (baseName.contains("UART_LOG")) {
            trimmed = trimLongZeroPadding(section, 60);
        } else {
            trimmed = trimToLastNonZero(section);
        }
        if (trimmed.length == 0) return null;

        String text = new String(trimmed, java.nio.charset.StandardCharsets.UTF_8);
        text = text.replace("��", "");

        byte[] firstPassBytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] finalTrimmed = trimLongZeroPadding(firstPassBytes, 60);

        String finalText = new String(finalTrimmed, java.nio.charset.StandardCharsets.UTF_8);

        if (finalText.trim().isEmpty()) return null;

        String fname = sanitizeFilename(baseName) + ".txt";
        File outFile = new File(getCacheDir(), fname);

        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(finalText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            extractedFiles.add(outFile);
            return outFile;
        } catch (IOException e) {
            updateLog("保存失敗: " + fname);
            return null;
        }
    }

    private boolean isAllFF(byte[] b) {
        for (byte bb : b) {
            if (bb != (byte) 0xFF) return false;
        }
        return true;
    }

    private byte[] trimTrailingZeros(byte[] data) {
        int i = data.length - 1;
        while (i >= 0 && data[i] == 0) i--;
        return Arrays.copyOf(data, i + 1);
    }

    private byte[] trimLongZeroPadding(byte[] data, int minPad) {
        for (int i = 0; i <= data.length - minPad; i++) {
            boolean isLongZero = true;
            for (int j = 0; j < minPad; j++) {
                if (data[i + j] != 0) {
                    isLongZero = false;
                    break;
                }
            }
            if (isLongZero) {
                return Arrays.copyOf(data, i);
            }
        }
        return trimTrailingZeros(data);
    }

    private byte[] trimToLastNonZero(byte[] data) {
        if (data == null || data.length == 0) return new byte[0];
        for (int i = data.length - 1; i >= 0; i--) {
            if (data[i] != 0) {
                return Arrays.copyOf(data, i + 1);
            }
        }
        return new byte[0];
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private byte[] readFileToBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) baos.write(buf, 0, len);
            return baos.toByteArray();
        }
    }

    private void createZip(List<File> files, File zipFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            for (File f : files) {
                if (!f.exists()) continue;
                ZipEntry ze = new ZipEntry(f.getName());
                zos.putNextEntry(ze);
                try (FileInputStream fis = new FileInputStream(f)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = fis.read(buf)) != -1) zos.write(buf, 0, len);
                }
                zos.closeEntry();
            }
        }
    }

    private void saveZipToDownload(File tempZip, String zipName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, zipName);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri);
                         FileInputStream fis = new FileInputStream(tempZip)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = fis.read(buf)) != -1) os.write(buf, 0, len);
                    }
                    updateLog("ZIP保存完了 → /sdcard/Download/" + zipName);
                }
            } else {
                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadDir.exists()) downloadDir.mkdirs();
                File dest = new File(downloadDir, zipName);
                try (FileInputStream fis = new FileInputStream(tempZip);
                     FileOutputStream fos = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = fis.read(buf)) != -1) fos.write(buf, 0, len);
                }
                updateLog("ZIP保存完了 → /sdcard/Download/" + zipName);
            }
        } catch (Exception e) {
            updateLog("ZIP保存エラー: " + e.getMessage());
        }
    }

    private void updateLog(String msg) {
        runOnUiThread(() -> {
            tvLog.append(msg + "\n");
            tvLog.post(() -> {
                if (tvLog.getLayout() != null) {
                    tvLog.scrollTo(0, tvLog.getLayout().getLineTop(tvLog.getLineCount()) - tvLog.getHeight());
                }
            });
        });
    }
}
