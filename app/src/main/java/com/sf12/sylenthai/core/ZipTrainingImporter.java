package com.sf12.sylenthai.core;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Safely extracts a training ZIP, identifies Sylenth1 FXPs and supported audio,
 *  pairs by filename (or a single-FXP archive), then adds each pair to the local repository. */
public final class ZipTrainingImporter {
    private static final int MAX_ENTRIES = 3000;
    private static final long MAX_ENTRY_BYTES = 200L * 1024L * 1024L;
    private static final long MAX_TOTAL_UNCOMPRESSED = 750L * 1024L * 1024L;

    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<>();
    static {
        String[] values = {"wav", "wave", "aif", "aiff", "aifc", "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "3gp", "amr", "mp4", "caf"};
        for (String value : values) AUDIO_EXTENSIONS.add(value);
    }

    private ZipTrainingImporter() {}

    public static final class ImportResult {
        public int zipEntries;
        public int validPresets;
        public int audioCandidates;
        public int addedPairs;
        public int duplicatePairs;
        public int failedPairs;
        public int unmatchedAudio;
        public int unmatchedPresets;
        public int skippedFiles;
        public final List<String> notes = new ArrayList<>();

        public String toDisplayText() {
            StringBuilder s = new StringBuilder();
            s.append("ZIP import finished.\n")
             .append("Detected ").append(validPresets).append(" valid Sylenth1 FXP file(s) and ")
             .append(audioCandidates).append(" audio file(s).\n")
             .append("Added ").append(addedPairs).append(" training pair(s).");
            if (duplicatePairs > 0) s.append("\nIgnored ").append(duplicatePairs).append(" duplicate pair(s).");
            if (failedPairs > 0) s.append("\nCould not process ").append(failedPairs).append(" pair(s).");
            if (unmatchedAudio > 0) s.append("\nUnmatched audio files: ").append(unmatchedAudio).append(".");
            if (unmatchedPresets > 0) s.append("\nUnmatched FXP files: ").append(unmatchedPresets).append(".");
            if (skippedFiles > 0) s.append("\nSkipped unrelated or invalid files: ").append(skippedFiles).append(".");
            for (int i = 0; i < Math.min(notes.size(), 8); i++) s.append("\n• ").append(notes.get(i));
            if (notes.size() > 8) s.append("\n• …and ").append(notes.size() - 8).append(" more note(s).");
            return s.toString();
        }
    }

    private static final class Candidate {
        final File file;
        final String archivePath;
        final String stem;
        final String parent;
        Candidate(File file, String archivePath) {
            this.file = file;
            this.archivePath = archivePath;
            int slash = archivePath.lastIndexOf('/');
            this.parent = slash >= 0 ? archivePath.substring(0, slash).toLowerCase(Locale.ROOT) : "";
            String name = slash >= 0 ? archivePath.substring(slash + 1) : archivePath;
            int dot = name.lastIndexOf('.');
            this.stem = (dot > 0 ? name.substring(0, dot) : name).trim().toLowerCase(Locale.ROOT);
        }
    }

    private static final class PairTask {
        final Candidate audio;
        final Candidate preset;
        PairTask(Candidate audio, Candidate preset) { this.audio = audio; this.preset = preset; }
    }

    public static ImportResult importArchive(File archive, File stagingRoot, TrainingRepository repository) throws Exception {
        if (archive == null || !archive.isFile()) throw new IllegalArgumentException("The selected ZIP file could not be read.");
        if (repository == null) throw new IllegalArgumentException("Training library is unavailable.");
        if (stagingRoot == null) throw new IllegalArgumentException("Temporary import storage is unavailable.");
        if (!stagingRoot.exists() && !stagingRoot.mkdirs()) throw new IllegalArgumentException("Could not create temporary ZIP storage.");

        File stage = new File(stagingRoot, "import_" + UUID.randomUUID().toString().replace("-", ""));
        if (!stage.mkdirs()) throw new IllegalArgumentException("Could not prepare ZIP extraction folder.");
        ImportResult result = new ImportResult();
        List<Candidate> presets = new ArrayList<>();
        List<Candidate> audios = new ArrayList<>();
        try {
            extractCandidates(archive, stage, presets, audios, result);
            if (result.zipEntries == 0) throw new IllegalArgumentException("This file does not contain a readable ZIP archive.");
            result.validPresets = presets.size();
            result.audioCandidates = audios.size();

            List<PairTask> tasks = pairFiles(presets, audios, result);
            Set<String> assignedPresets = new HashSet<>();
            for (PairTask task : tasks) assignedPresets.add(task.preset.archivePath);
            for (PairTask task : tasks) {
                try {
                    repository.addPair(task.audio.file, task.preset.file, task.audio.archivePath);
                    result.addedPairs++;
                } catch (IllegalArgumentException e) {
                    String message = e.getMessage() == null ? "" : e.getMessage();
                    if (message.toLowerCase(Locale.ROOT).contains("already in the training library")) {
                        result.duplicatePairs++;
                    } else {
                        result.failedPairs++;
                        addNote(result, task.audio.archivePath + ": " + message);
                    }
                } catch (Exception e) {
                    result.failedPairs++;
                    addNote(result, task.audio.archivePath + ": " + safeMessage(e));
                }
            }
            result.unmatchedPresets = 0;
            for (Candidate preset : presets) if (!assignedPresets.contains(preset.archivePath)) result.unmatchedPresets++;
            return result;
        } finally {
            deleteRecursively(stage);
        }
    }

    private static void extractCandidates(File archive, File stage, List<Candidate> presets,
                                           List<Candidate> audios, ImportResult result) throws Exception {
        Set<String> seenPaths = new HashSet<>();
        long[] totalBytes = {0L};
        int entryNumber = 0;
        try (InputStream fileIn = new BufferedInputStream(new FileInputStream(archive));
             ZipInputStream zip = new ZipInputStream(fileIn)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryNumber++;
                result.zipEntries++;
                if (entryNumber > MAX_ENTRIES) throw new IllegalArgumentException("ZIP contains too many entries (limit: " + MAX_ENTRIES + ").");
                String safePath = sanitiseEntryName(entry.getName());
                if (safePath == null) {
                    drain(zip, totalBytes, 0L, MAX_ENTRY_BYTES, MAX_TOTAL_UNCOMPRESSED);
                    zip.closeEntry();
                    result.skippedFiles++;
                    continue;
                }
                if (entry.isDirectory()) {
                    drain(zip, totalBytes, 0L, MAX_ENTRY_BYTES, MAX_TOTAL_UNCOMPRESSED);
                    zip.closeEntry();
                    continue;
                }
                String lower = safePath.toLowerCase(Locale.ROOT);
                int dot = lower.lastIndexOf('.');
                String ext = dot >= 0 ? lower.substring(dot + 1) : "";
                boolean extensionSaysPreset = "fxp".equals(ext);
                boolean extensionSaysAudio = AUDIO_EXTENSIONS.contains(ext);
                if (!seenPaths.add(lower)) {
                    drain(zip, totalBytes, 0L, MAX_ENTRY_BYTES, MAX_TOTAL_UNCOMPRESSED);
                    zip.closeEntry();
                    result.skippedFiles++;
                    addNote(result, "Duplicate ZIP path skipped: " + safePath);
                    continue;
                }
                // Inspect a small header so audio can still be recognised when an archive
                // has extensionless files or a non-standard extension.
                byte[] prefix = readPrefix(zip, 16, totalBytes);
                boolean isPreset = extensionSaysPreset || looksLikeSylenthFxp(prefix);
                boolean isAudio = extensionSaysAudio || looksLikeAudio(prefix);
                if (!isPreset && !isAudio) {
                    drain(zip, totalBytes, prefix.length, MAX_ENTRY_BYTES, MAX_TOTAL_UNCOMPRESSED);
                    zip.closeEntry();
                    result.skippedFiles++;
                    continue;
                }
                File destination = new File(stage, safePath);
                File canonicalStage = stage.getCanonicalFile();
                File canonicalDestination = destination.getCanonicalFile();
                String rootPath = canonicalStage.getPath() + File.separator;
                if (!canonicalDestination.getPath().startsWith(rootPath)) {
                    drain(zip, totalBytes, prefix.length, MAX_ENTRY_BYTES, MAX_TOTAL_UNCOMPRESSED);
                    zip.closeEntry();
                    result.skippedFiles++;
                    continue;
                }
                File parent = canonicalDestination.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalArgumentException("Could not create an extraction folder.");
                long entryBytes = copyZipEntry(zip, destination, totalBytes, prefix);
                zip.closeEntry();
                if (entryBytes > MAX_ENTRY_BYTES) throw new IllegalArgumentException("A ZIP entry exceeds the 200 MB safety limit.");
                if (isPreset) {
                    try {
                        SylenthFxp.parse(TrainingRepository.readAll(destination));
                        presets.add(new Candidate(destination, safePath));
                    } catch (Exception e) {
                        result.skippedFiles++;
                        destination.delete();
                        addNote(result, "Invalid or unsupported FXP skipped: " + safePath);
                    }
                } else if (isAudio) {
                    audios.add(new Candidate(destination, safePath));
                }
            }
        }
    }

    private static List<PairTask> pairFiles(List<Candidate> presets, List<Candidate> audios, ImportResult result) {
        List<PairTask> tasks = new ArrayList<>();
        Map<String, List<Candidate>> byStem = new HashMap<>();
        for (Candidate preset : presets) byStem.computeIfAbsent(preset.stem, key -> new ArrayList<>()).add(preset);

        for (Candidate audio : audios) {
            Candidate selected = null;
            List<Candidate> matches = byStem.get(audio.stem);
            if (matches != null && matches.size() == 1) {
                selected = matches.get(0);
            } else if (matches != null && matches.size() > 1) {
                List<Candidate> sameFolder = new ArrayList<>();
                for (Candidate candidate : matches) if (candidate.parent.equals(audio.parent)) sameFolder.add(candidate);
                if (sameFolder.size() == 1) selected = sameFolder.get(0);
            }
            // A pack with one preset and several rendered examples is unambiguous: all audio belongs to it.
            if (selected == null && presets.size() == 1) selected = presets.get(0);
            if (selected != null) {
                tasks.add(new PairTask(audio, selected));
            } else {
                result.unmatchedAudio++;
                addNote(result, "No unambiguous matching FXP for audio: " + audio.archivePath);
            }
        }
        return tasks;
    }

    private static String sanitiseEntryName(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String name = raw.replace('\\', '/');
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) return null;
        String[] components = name.split("/");
        List<String> safe = new ArrayList<>();
        for (String component : components) {
            if (component.isEmpty() || component.equals(".")) continue;
            if (component.equals("..") || component.indexOf('\0') >= 0) return null;
            for (int i = 0; i < component.length(); i++) if (Character.isISOControl(component.charAt(i))) return null;
            safe.add(component);
        }
        if (safe.isEmpty()) return null;
        return String.join(File.separator, safe);
    }

    private static byte[] readPrefix(ZipInputStream in, int maxBytes, long[] total) throws Exception {
        byte[] prefix = new byte[maxBytes];
        int used = 0;
        while (used < maxBytes) {
            int count = in.read(prefix, used, maxBytes - used);
            if (count < 0) break;
            used += count;
            total[0] += count;
            if (total[0] > MAX_TOTAL_UNCOMPRESSED) throw new IllegalArgumentException("ZIP expands beyond the 750 MB safety limit.");
        }
        return used == prefix.length ? prefix : java.util.Arrays.copyOf(prefix, used);
    }

    private static long copyZipEntry(ZipInputStream in, File destination, long[] total, byte[] prefix) throws Exception {
        long entryBytes = prefix.length;
        try (FileOutputStream out = new FileOutputStream(destination)) {
            if (prefix.length > 0) out.write(prefix);
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) {
                entryBytes += count;
                total[0] += count;
                if (entryBytes > MAX_ENTRY_BYTES) throw new IllegalArgumentException("A ZIP entry exceeds the 200 MB safety limit.");
                if (total[0] > MAX_TOTAL_UNCOMPRESSED) throw new IllegalArgumentException("ZIP expands beyond the 750 MB safety limit.");
                out.write(buffer, 0, count);
            }
            out.flush();
        }
        return entryBytes;
    }

    private static void drain(ZipInputStream in, long[] total, long initialEntryBytes, long entryLimit, long totalLimit) throws Exception {
        long entryBytes = initialEntryBytes;
        byte[] buffer = new byte[32768];
        int count;
        while ((count = in.read(buffer)) != -1) {
            entryBytes += count;
            total[0] += count;
            if (entryBytes > entryLimit || total[0] > totalLimit) {
                throw new IllegalArgumentException("ZIP expands beyond the safety limits.");
            }
        }
    }

    private static boolean looksLikeSylenthFxp(byte[] header) {
        return header.length >= 12 && ascii(header, 0, 4).equals("CcnK") && ascii(header, 8, 4).equals("FPCh");
    }

    private static boolean looksLikeAudio(byte[] header) {
        if (header.length >= 12) {
            String first = ascii(header, 0, 4);
            String fourth = ascii(header, 8, 4);
            if ((first.equals("RIFF") && fourth.equals("WAVE"))
                    || (first.equals("FORM") && (fourth.equals("AIFF") || fourth.equals("AIFC")))
                    || first.equals("fLaC") || first.equals("OggS") || first.equals("#!AM")) return true;
            if (ascii(header, 4, 4).equals("ftyp")) return true;
        }
        if (header.length >= 3 && ascii(header, 0, 3).equals("ID3")) return true;
        // MPEG audio frames and ADTS AAC commonly begin with a 11-bit sync word.
        return header.length >= 2 && (header[0] & 0xff) == 0xff && ((header[1] & 0xe0) == 0xe0);
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static void addNote(ImportResult result, String note) {
        if (note != null && !note.trim().isEmpty() && result.notes.size() < 100) result.notes.add(note);
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
