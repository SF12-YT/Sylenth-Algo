package com.sf12.sylenthai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.sf12.sylenthai.core.AndroidAudioDecoder;
import com.sf12.sylenthai.core.AudioFeatures;
import com.sf12.sylenthai.core.SylenthFxp;
import com.sf12.sylenthai.core.TrainingRepository;
import com.sf12.sylenthai.core.ZipTrainingImporter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_TRAIN_AUDIO = 101;
    private static final int REQ_TRAIN_PRESET = 102;
    private static final int REQ_TARGET_AUDIO = 103;
    private static final int REQ_EXPORT = 104;
    private static final int REQ_TRAIN_ZIP = 105;
    private static final int REQ_DIAGNOSTIC = 106;

    private final int bg = 0xFF08131F;
    private final int card = 0xFF112235;
    private final int cardAlt = 0xFF172D43;
    private final int accent = 0xFF168BD0;
    private final int accentLight = 0xFF43B3F2;
    private final int text = 0xFFF3F7FC;
    private final int muted = 0xFF9EB1C4;

    private ExecutorService worker;
    private Handler main;
    private TrainingRepository repository;
    private File pendingAudio;
    private File pendingPreset;
    private File targetAudio;
    private String pendingAudioName = "";
    private String targetAudioName = "";
    private byte[] pendingExport;
    private String pendingExportName = "SylenthAI_Match.fxp";

    private TextView statsText;
    private TextView pairStatusText;
    private TextView targetText;
    private TextView statusText;
    private ProgressBar progress;
    private Button exportButton;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        getWindow().getDecorView().setSystemUiVisibility(0);
        worker = Executors.newSingleThreadExecutor();
        main = new Handler(Looper.getMainLooper());
        try {
            repository = new TrainingRepository(getFilesDir());
        } catch (Exception e) {
            showFatal(e.getMessage());
            return;
        }
        buildInterface();
        refreshStats();
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(26));
        scroll.addView(root);

        TextView eyebrow = new TextView(this);
        eyebrow.setText("AUDIO → SYLENTH1 PRESET MATCHING");
        eyebrow.setTextColor(accentLight);
        eyebrow.setTextSize(11);
        eyebrow.setLetterSpacing(0.12f);
        eyebrow.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(eyebrow);

        TextView title = new TextView(this);
        title.setText("SYLENTH AI");
        title.setTextColor(text);
        title.setTextSize(31);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(5);
        root.addView(title, titleLp);

        TextView subtitle = new TextView(this);
        subtitle.setText("Build a local library of paired audio and Sylenth1 presets, then find a close match for a new sound.");
        subtitle.setTextColor(muted);
        subtitle.setTextSize(14);
        subtitle.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(5);
        subLp.bottomMargin = dp(22);
        root.addView(subtitle, subLp);

        LinearLayout libraryCard = cardContainer();
        root.addView(libraryCard, fullWidth());
        addSectionLabel(libraryCard, "TRAINING LIBRARY");
        statsText = new TextView(this);
        statsText.setText("0 audio examples · 0 unique presets");
        statsText.setTextColor(text);
        statsText.setTextSize(19);
        statsText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(-1, -2);
        statsLp.topMargin = dp(7);
        libraryCard.addView(statsText, statsLp);
        TextView libraryInfo = new TextView(this);
        libraryInfo.setText("Pair each exported audio recording with the exact FXP that produced it. You can add several recordings for the same preset.");
        libraryInfo.setTextColor(muted);
        libraryInfo.setTextSize(12);
        libraryInfo.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(-1, -2);
        infoLp.topMargin = dp(6);
        infoLp.bottomMargin = dp(14);
        libraryCard.addView(libraryInfo, infoLp);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        libraryCard.addView(row1, fullWidth());
        Button addAudio = makeButton("Add training audio", accent, 13);
        Button addPreset = makeButton("Add training preset", cardAlt, 13);
        row1.addView(addAudio, weightLp(1, dp(54), dp(6)));
        row1.addView(addPreset, weightLp(1, dp(54), 0));
        addAudio.setOnClickListener(v -> pickFile(REQ_TRAIN_AUDIO));
        addPreset.setOnClickListener(v -> pickFile(REQ_TRAIN_PRESET));

        Button importZip = makeButton("Import ZIP training pack", 0xFF155D82, 13);
        LinearLayout.LayoutParams zipButtonLp = fullWidth();
        zipButtonLp.topMargin = dp(9);
        libraryCard.addView(importZip, zipButtonLp);
        importZip.setOnClickListener(v -> pickFile(REQ_TRAIN_ZIP));

        Button diagnoseFxp = makeButton("Diagnose an FXP locally", 0xFF24445F, 13);
        LinearLayout.LayoutParams diagnosticLp = fullWidth();
        diagnosticLp.topMargin = dp(9);
        libraryCard.addView(diagnoseFxp, diagnosticLp);
        diagnoseFxp.setOnClickListener(v -> pickFile(REQ_DIAGNOSTIC));

        TextView diagnosticInfo = new TextView(this);
        diagnosticInfo.setText("Inspect an FXP on this device and copy a diagnostic report. The preset is not uploaded or added to your library.");
        diagnosticInfo.setTextColor(muted);
        diagnosticInfo.setTextSize(11);
        diagnosticInfo.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams diagnosticInfoLp = fullWidth();
        diagnosticInfoLp.topMargin = dp(6);
        libraryCard.addView(diagnosticInfo, diagnosticInfoLp);

        pairStatusText = new TextView(this);
        pairStatusText.setText("Choose a matching audio file and .fxp, or import a ZIP containing both. ZIP files are unpacked and paired automatically.");
        pairStatusText.setTextColor(muted);
        pairStatusText.setTextSize(12);
        pairStatusText.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams pairLp = new LinearLayout.LayoutParams(-1, -2);
        pairLp.topMargin = dp(11);
        libraryCard.addView(pairStatusText, pairLp);

        LinearLayout targetCard = cardContainer();
        LinearLayout.LayoutParams targetLp = fullWidth();
        targetLp.topMargin = dp(17);
        root.addView(targetCard, targetLp);
        addSectionLabel(targetCard, "RECREATE A SOUND");
        TextView targetInfo = new TextView(this);
        targetInfo.setText("Select the audio you want to recreate, then export the estimated Sylenth1 preset.");
        targetInfo.setTextColor(muted);
        targetInfo.setTextSize(13);
        targetInfo.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams targetInfoLp = new LinearLayout.LayoutParams(-1, -2);
        targetInfoLp.topMargin = dp(6);
        targetInfoLp.bottomMargin = dp(13);
        targetCard.addView(targetInfo, targetInfoLp);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        targetCard.addView(row2, fullWidth());
        Button inputAudio = makeButton("Input audio", cardAlt, 14);
        exportButton = makeButton("Export Sylenth preset", accent, 13);
        exportButton.setEnabled(false);
        row2.addView(inputAudio, weightLp(1, dp(56), dp(6)));
        row2.addView(exportButton, weightLp(1, dp(56), 0));
        inputAudio.setOnClickListener(v -> pickFile(REQ_TARGET_AUDIO));
        exportButton.setOnClickListener(v -> runMatching());

        targetText = new TextView(this);
        targetText.setText("No target audio selected.");
        targetText.setTextColor(muted);
        targetText.setTextSize(12);
        LinearLayout.LayoutParams targetNameLp = new LinearLayout.LayoutParams(-1, -2);
        targetNameLp.topMargin = dp(10);
        targetCard.addView(targetText, targetNameLp);

        LinearLayout statusCard = cardContainer();
        LinearLayout.LayoutParams statusCardLp = fullWidth();
        statusCardLp.topMargin = dp(17);
        root.addView(statusCard, statusCardLp);
        addSectionLabel(statusCard, "MATCHING STATUS");
        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        progressLp.topMargin = dp(9);
        statusCard.addView(progress, progressLp);
        statusText = new TextView(this);
        statusText.setText("Ready. Training examples are processed and stored on this device.");
        statusText.setTextColor(muted);
        statusText.setTextSize(12);
        statusText.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = dp(8);
        statusCard.addView(statusText, statusLp);

        TextView note = new TextView(this);
        note.setText("For best results, use isolated Sylenth1 audio without other instruments or extra DAW processing. This first version uses similarity-based matching; it cannot guarantee recovery of the exact original parameter values.");
        note.setTextColor(muted);
        note.setTextSize(11);
        note.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.topMargin = dp(16);
        root.addView(note, noteLp);

        setContentView(scroll);
    }

    private void pickFile(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (requestCode == REQ_TRAIN_AUDIO || requestCode == REQ_TARGET_AUDIO) {
            intent.setType("audio/*");
        } else {
            // File picker providers vary in their ZIP MIME declarations, so allow all files here.
            intent.setType("*/*");
        }
        try { startActivityForResult(intent, requestCode); }
        catch (Exception e) { Toast.makeText(this, "No file picker is available.", Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            if (pendingExport == null || uri == null) return;
            setBusy(true, "Writing the generated FXP file…");
            worker.execute(() -> {
                String message;
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                    if (out == null) throw new Exception("The selected destination could not be opened.");
                    out.write(pendingExport); out.flush();
                    message = "Exported " + pendingExportName + ". Load it in Sylenth1 to test the result.";
                } catch (Exception e) { message = "Could not save the FXP: " + safeMessage(e); }
                final String result = message;
                main.post(() -> { setBusy(false, result); Toast.makeText(this, result, Toast.LENGTH_LONG).show(); });
            });
            return;
        }
        if (uri == null) return;
        final int action = requestCode;
        setBusy(true, "Importing selected file…");
        worker.execute(() -> {
            try {
                String displayName = getDisplayName(uri);
                File imported = copyUriToWorkingFile(uri, displayName);
                main.post(() -> handleImportedFile(action, imported, displayName));
            } catch (Exception e) {
                main.post(() -> setBusy(false, "Import failed: " + safeMessage(e)));
            }
        });
    }

    private void handleImportedFile(int action, File file, String displayName) {
        setBusy(false, "");
        if (action == REQ_TRAIN_AUDIO) {
            if (pendingAudio != null) pendingAudio.delete();
            pendingAudio = file;
            pendingAudioName = displayName;
            pairStatusText.setText("Training audio selected: " + displayName + (pendingPreset == null ? "\nNow choose its matching .fxp preset." : "\nMatching preset selected; adding pair…"));
            maybeSaveTrainingPair();
        } else if (action == REQ_TRAIN_PRESET) {
            if (pendingPreset != null) pendingPreset.delete();
            pendingPreset = file;
            pairStatusText.setText("Training preset selected: " + displayName + (pendingAudio == null ? "\nNow choose its exported audio." : "\nMatching audio selected; adding pair…"));
            maybeSaveTrainingPair();
        } else if (action == REQ_TARGET_AUDIO) {
            if (targetAudio != null) targetAudio.delete();
            targetAudio = file;
            targetAudioName = displayName;
            targetText.setText("Selected: " + displayName);
            updateExportEnabled();
            statusText.setText("Target audio loaded. Export a match when your training library contains at least one pair.");
        } else if (action == REQ_TRAIN_ZIP) {
            importTrainingZip(file, displayName);
        } else if (action == REQ_DIAGNOSTIC) {
            diagnoseFxpLocally(file, displayName);
        }
    }

    private void diagnoseFxpLocally(File file, String displayName) {
        setBusy(true, "Inspecting the FXP locally. No file is being uploaded…");
        worker.execute(() -> {
            String report;
            try {
                if (file.length() > 8L * 1024L * 1024L) throw new Exception("FXP exceeds the 8 MB diagnostic safety limit.");
                byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                report = buildFxpDiagnostic(bytes, displayName);
            } catch (Exception e) {
                report = "Diagnostic could not be completed: " + safeMessage(e);
            } finally {
                if (file != null && file.exists()) file.delete();
            }
            final String finalReport = report;
            main.post(() -> {
                setBusy(false, "Local FXP diagnostic complete. The temporary copy was deleted.");
                showDiagnosticReport(finalReport);
            });
        });
    }

    private void showDiagnosticReport(String report) {
        TextView body = new TextView(this);
        body.setText(report);
        body.setTextColor(text);
        body.setTextSize(12);
        body.setTextIsSelectable(true);
        body.setTypeface(Typeface.MONOSPACE);
        body.setPadding(dp(14), dp(12), dp(14), dp(12));
        ScrollView container = new ScrollView(this);
        container.setBackgroundColor(card);
        container.addView(body);
        new AlertDialog.Builder(this).setTitle("Local FXP diagnostic").setView(container)
                .setPositiveButton("Copy report", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Sylenth FXP diagnostic", report));
                        Toast.makeText(this, "Diagnostic report copied. Paste it here when ready.", Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton("Close", null).show();
    }

    private String buildFxpDiagnostic(byte[] data, String displayName) {
        StringBuilder out = new StringBuilder();
        out.append("SYLENTH AI — LOCAL FXP DIAGNOSTIC\n")
                .append("File: ").append(displayName).append("\n")
                .append("File bytes: ").append(data.length).append("\n")
                .append("No file contents were uploaded. Report generated locally.\n\n");
        if (data.length < 60) {
            out.append("Outer VST header incomplete: 60 bytes required; ").append(data.length).append(" available.\n")
                    .append("File prefix: ").append(hexLine(data, 0, data.length)).append("\n");
            return out.toString();
        }

        long declaredFileBytes = unsignedIntBE(data, 4);
        long declaredChunkBytes = unsignedIntBE(data, 56);
        long actualChunkBytes = data.length - 60L;
        out.append("[VST OUTER HEADER]\n")
                .append("Magic @0: ").append(asciiField(data, 0, 4)).append("\n")
                .append("Declared file size @4 (BE): ").append(declaredFileBytes)
                .append(" | expected total: ").append(declaredFileBytes + 8).append("\n")
                .append("FX format @8: ").append(asciiField(data, 8, 4)).append("\n")
                .append("VST version @12 (BE): ").append(unsignedIntBE(data, 12)).append("\n")
                .append("Plugin ID @16: ").append(asciiField(data, 16, 4)).append("\n")
                .append("Plugin version @20 (BE): ").append(unsignedIntBE(data, 20)).append("\n")
                .append("Program count @24 (BE): ").append(unsignedIntBE(data, 24)).append("\n")
                .append("Outer name @28: ").append(asciiField(data, 28, 28)).append("\n")
                .append("Declared chunk bytes @56 (BE): ").append(declaredChunkBytes).append("\n")
                .append("Physically available after outer header: ").append(actualChunkBytes).append("\n")
                .append("Declared file size matches physical file: ").append(declaredFileBytes + 8 == data.length).append("\n")
                .append("Declared chunk size matches remaining bytes: ").append(declaredChunkBytes == actualChunkBytes).append("\n\n");

        int chunkLength = (int) Math.min(actualChunkBytes, Math.max(0L, declaredChunkBytes));
        if (chunkLength > 0) {
            out.append("[EMBEDDED CHUNK HEADER]\n")
                    .append("Chunk signature: ").append(asciiField(data, 60, Math.min(4, chunkLength))).append("\n");
            for (int rel = 0; rel < 20; rel += 4) {
                if (rel + 4 <= chunkLength) {
                    int abs = 60 + rel;
                    int word = readIntLE(data, abs);
                    out.append(String.format(java.util.Locale.US,
                            "LE word @ chunk+%d (file+%d): signed=%d unsigned=%s hex=%s\n",
                            rel, abs, word, Long.toString(Integer.toUnsignedLong(word)), hexLine(data, abs, 4)));
                }
            }
            if (chunkLength >= 20) {
                out.append("Current parser interpretation: type='").append(asciiField(data, 60, 4))
                        .append("', version=").append(readIntLE(data, 64))
                        .append(", following words=").append(readIntLE(data, 68)).append(", ")
                        .append(readIntLE(data, 72)).append(", ").append(readIntLE(data, 76)).append("\n");
            }
        } else {
            out.append("[EMBEDDED CHUNK] No embedded chunk bytes are available.\n");
        }

        out.append("\n[CHUNK HEX DUMP — relative offsets]\n");
        if (chunkLength == 0) out.append("(empty)\n");
        else {
            int dumpLength = Math.min(chunkLength, 4096);
            for (int offset = 0; offset < dumpLength; offset += 16) {
                int count = Math.min(16, dumpLength - offset);
                out.append(String.format(java.util.Locale.US, "%04X  ", offset));
                for (int i = 0; i < count; i++)
                    out.append(String.format(java.util.Locale.US, "%02X ", data[60 + offset + i] & 0xff));
                out.append("\n");
            }
            if (chunkLength > dumpLength) out.append("… truncated dump; total chunk length is ").append(chunkLength).append(" bytes.\n");
        }
        out.append("\n[BOUNDS]\n")
                .append("Available bytes after outer header: ").append(actualChunkBytes).append("\n")
                .append("Chunk bytes selected for dump: ").append(chunkLength).append("\n")
                .append("The current 992-byte assumption for version 3045 is unverified.\n");
        return out.toString();
    }

    private static String asciiField(byte[] data, int offset, int length) {
        if (offset < 0 || length <= 0 || offset >= data.length) return "";
        StringBuilder value = new StringBuilder();
        int end = Math.min(data.length, offset + length);
        for (int i = offset; i < end; i++) {
            int c = data[i] & 0xff;
            value.append(c >= 32 && c <= 126 ? (char) c : '.');
        }
        return value.toString();
    }

    private static long unsignedIntBE(byte[] data, int offset) {
        return Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(data, offset, 4)
                .order(java.nio.ByteOrder.BIG_ENDIAN).getInt());
    }

    private static int readIntLE(byte[] data, int offset) {
        return java.nio.ByteBuffer.wrap(data, offset, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static String hexLine(byte[] data, int offset, int length) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < length && offset + i < data.length; i++)
            value.append(String.format(java.util.Locale.US, "%02X", data[offset + i] & 0xff));
        return value.toString();
    }

    private void importTrainingZip(File archive, String displayName) {
        setBusy(true, "Opening " + displayName + ", identifying FXP presets and audio, then training…");
        worker.execute(() -> {
            String message;
            try {
                File staging = new File(getFilesDir(), "sylenth_ai/zip_staging");
                ZipTrainingImporter.ImportResult result = ZipTrainingImporter.importArchive(archive, staging, repository);
                message = result.toDisplayText();
            } catch (Exception e) {
                message = "ZIP import failed: " + safeMessage(e);
            } finally {
                if (archive != null && archive.exists()) archive.delete();
            }
            final String finalMessage = message;
            main.post(() -> {
                setBusy(false, finalMessage);
                pairStatusText.setText(finalMessage);
                refreshStats();
                Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void maybeSaveTrainingPair() {
        if (pendingAudio == null || pendingPreset == null) return;
        File audio = pendingAudio; File preset = pendingPreset; String audioName = pendingAudioName;
        pendingAudio = null; pendingPreset = null; pendingAudioName = "";
        setBusy(true, "Analysing the audio and adding its paired Sylenth1 preset…");
        worker.execute(() -> {
            String message;
            try {
                repository.addPair(audio, preset, audioName);
                message = "Training example added: " + audioName;
                audio.delete(); preset.delete();
            } catch (Exception e) {
                message = "Training pair not added: " + safeMessage(e);
                audio.delete(); preset.delete();
            }
            final String result = message;
            main.post(() -> {
                setBusy(false, result);
                pairStatusText.setText(result + "\nYou can also import a ZIP containing matched audio and FXP files.");
                refreshStats();
            });
        });
    }

    private void runMatching() {
        if (targetAudio == null) { Toast.makeText(this, "Select target audio first.", Toast.LENGTH_SHORT).show(); return; }
        setBusy(true, "Extracting audio features and searching the training library…");
        worker.execute(() -> {
            try {
                AndroidAudioDecoder.AudioData data = AndroidAudioDecoder.decode(targetAudio);
                float[] features = AudioFeatures.extract(data.samples, data.sampleRate, data.channels);
                TrainingRepository.MatchResult result = repository.matchAndBuild(features);
                pendingExport = result.fxpBytes;
                pendingExportName = result.outputName;
                String message = "Closest training preset: " + result.nearestPresetName
                        + "\nDistance-based match indicator: " + Math.round(result.relativeScore) + "/100 (not a probability)"
                        + "\nCompared against " + result.trainingExamples + " audio examples using up to "
                        + result.neighbourCount + " nearby preset(s).\n\nThe FXP is an estimated patch, not a verified exact recovery.";
                main.post(() -> {
                    setBusy(false, message);
                    launchExportPicker();
                });
            } catch (Exception e) {
                main.post(() -> setBusy(false, "Matching failed: " + safeMessage(e)));
            }
        });
    }

    private void launchExportPicker() {
        if (pendingExport == null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, pendingExportName);
        try { startActivityForResult(intent, REQ_EXPORT); }
        catch (Exception e) { setBusy(false, "Could not open the save dialog: " + safeMessage(e)); }
    }


    private String getDisplayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = c.getString(index);
            }
        } catch (Exception ignored) {}
        return name == null || name.trim().isEmpty() ? "audio_file" : name;
    }

    private File copyUriToWorkingFile(Uri uri, String displayName) throws Exception {
        File dir = new File(getFilesDir(), "sylenth_ai/incoming");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create import folder.");
        String ext = ".bin";
        int dot = displayName.lastIndexOf('.');
        if (dot >= 0 && displayName.length() - dot <= 8) ext = displayName.substring(dot).replaceAll("[^A-Za-z0-9.]", "").toLowerCase();
        File outFile = new File(dir, UUID.randomUUID().toString().replace("-", "") + ext);
        try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(outFile)) {
            if (in == null) throw new Exception("Could not read the selected file.");
            byte[] buf = new byte[65536]; int n;
            long copied = 0;
            while ((n = in.read(buf)) >= 0) {
                copied += n;
                if (copied > 1024L * 1024L * 1024L) throw new Exception("Selected file exceeds the 1 GB import limit.");
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
            throw e;
        }
        return outFile;
    }

    private void refreshStats() {
        worker.execute(() -> {
            try {
                TrainingRepository.TrainingStats stats = repository.getStats();
                main.post(() -> {
                    statsText.setText(stats.examples + " audio examples · " + stats.presets + " unique presets");
                    updateExportEnabled();
                });
            } catch (Exception e) { main.post(() -> statusText.setText("Could not read the training library: " + safeMessage(e))); }
        });
    }

    private void updateExportEnabled() {
        if (exportButton == null) return;
        try { exportButton.setEnabled(targetAudio != null && repository.getStats().examples > 0); }
        catch (Exception ignored) { exportButton.setEnabled(false); }
        exportButton.setAlpha(exportButton.isEnabled() ? 1f : 0.45f);
    }

    private void setBusy(boolean busy, String message) {
        if (progress != null) progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (statusText != null && message != null && !message.isEmpty()) statusText.setText(message);
        if (exportButton != null) {
            exportButton.setEnabled(!busy && targetAudio != null && hasTrainingExamples());
            exportButton.setAlpha(exportButton.isEnabled() ? 1f : 0.45f);
        }
    }

    private boolean hasTrainingExamples() {
        try { return repository.getStats().examples > 0; } catch (Exception e) { return false; }
    }

    private void showFatal(String message) {
        new AlertDialog.Builder(this).setTitle("Sylenth AI")
                .setMessage(message == null ? "Unable to initialise the app." : message)
                .setPositiveButton("Close", (d, w) -> finish()).show();
    }

    private LinearLayout cardContainer() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(15), dp(15), dp(15), dp(15));
        layout.setBackground(roundRect(card, dp(18)));
        return layout;
    }

    private void addSectionLabel(LinearLayout parent, String value) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextColor(accentLight);
        label.setTextSize(10);
        label.setLetterSpacing(0.12f);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(label);
    }

    private Button makeButton(String label, int color, int size) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(text);
        b.setTextSize(size);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setPadding(dp(7), dp(6), dp(7), dp(6));
        b.setMinHeight(dp(48));
        b.setBackground(roundRect(color, dp(13)));
        return b;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(radius);
        return d;
    }

    private LinearLayout.LayoutParams fullWidth() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams weightLp(float weight, int height, int rightMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, weight);
        if (rightMargin > 0) lp.rightMargin = rightMargin;
        return lp;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (worker != null) worker.shutdown();
    }
}
