package com.sf12.sylenthai.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Local-only training pair store. Audio examples are kept separately from deduplicated FXP files. */
public final class TrainingRepository {
    private final File root;
    private final File audioDir;
    private final File presetDir;
    private final File indexFile;

    public TrainingRepository(File filesDir) throws Exception {
        root = new File(filesDir, "sylenth_ai");
        audioDir = new File(root, "audio_examples");
        presetDir = new File(root, "presets");
        if (!audioDir.exists() && !audioDir.mkdirs()) throw new Exception("Could not create the local training folder.");
        if (!presetDir.exists() && !presetDir.mkdirs()) throw new Exception("Could not create the preset folder.");
        indexFile = new File(root, "training_index.json");
        if (!indexFile.exists()) writeIndex(new JSONArray());
    }

    public static final class Sample {
        public String id;
        public String presetHash;
        public String audioName;
        public float[] features;
    }

    public static final class PresetRecord {
        public String hash;
        public String fileName;
        public SylenthFxp.Preset preset;
    }

    public static final class TrainingStats {
        public final int examples;
        public final int presets;
        public TrainingStats(int examples, int presets) { this.examples = examples; this.presets = presets; }
    }

    public static final class MatchResult {
        public final byte[] fxpBytes;
        public final String nearestPresetName;
        public final String outputName;
        public final double relativeScore;
        public final int neighbourCount;
        public final int trainingExamples;
        public MatchResult(byte[] bytes, String name, String outputName, double score, int neighbours, int examples) {
            this.fxpBytes = bytes; this.nearestPresetName = name; this.outputName = outputName;
            this.relativeScore = score; this.neighbourCount = neighbours; this.trainingExamples = examples;
        }
    }

    public synchronized void addPair(File audioFile, File presetFile, String audioDisplayName) throws Exception {
        if (audioFile == null || !audioFile.isFile()) throw new IllegalArgumentException("Select a training audio file first.");
        if (presetFile == null || !presetFile.isFile()) throw new IllegalArgumentException("Select the matching Sylenth1 FXP file.");
        byte[] fxpBytes = readAll(presetFile);
        SylenthFxp.Preset parsed = SylenthFxp.parse(fxpBytes);
        AndroidAudioDecoder.AudioData decoded = AndroidAudioDecoder.decode(audioFile);
        float[] features = AudioFeatures.extract(decoded.samples, decoded.sampleRate, decoded.channels);

        String presetHash = sha256(fxpBytes);
        String audioHash = sha256File(audioFile);
        String id = audioHash.substring(0, 16) + "_" + presetHash.substring(0, 12);
        JSONArray pairs = readIndex();
        for (int i = 0; i < pairs.length(); i++) {
            JSONObject existing = pairs.getJSONObject(i);
            if (id.equals(existing.optString("id"))) {
                throw new IllegalArgumentException("That exact audio/preset pair is already in the training library.");
            }
        }

        File presetDestination = new File(presetDir, presetHash + ".fxp");
        if (!presetDestination.exists()) writeBytes(presetDestination, fxpBytes);
        String ext = extension(audioDisplayName);
        File audioDestination = new File(audioDir, id + ext);
        copy(audioFile, audioDestination);

        JSONObject row = new JSONObject();
        row.put("id", id);
        row.put("presetHash", presetHash);
        row.put("presetName", parsed.name);
        row.put("audioName", audioDisplayName == null ? audioFile.getName() : audioDisplayName);
        row.put("audioFile", audioDestination.getName());
        JSONArray featureJson = new JSONArray();
        for (float v : features) featureJson.put(v);
        row.put("features", featureJson);
        pairs.put(row);
        writeIndex(pairs);
    }

    public synchronized TrainingStats getStats() throws Exception {
        JSONArray pairs = readIndex();
        Map<String, Boolean> presetSet = new HashMap<>();
        for (int i = 0; i < pairs.length(); i++) presetSet.put(pairs.getJSONObject(i).optString("presetHash"), Boolean.TRUE);
        return new TrainingStats(pairs.length(), presetSet.size());
    }

    public synchronized MatchResult matchAndBuild(float[] targetFeatures) throws Exception {
        JSONArray rows = readIndex();
        if (rows.length() == 0) throw new IllegalArgumentException("Add at least one audio/FXP training pair first.");
        List<Sample> samples = new ArrayList<>();
        Map<String, PresetRecord> presets = new LinkedHashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            Sample s = new Sample();
            s.id = row.getString("id");
            s.presetHash = row.getString("presetHash");
            s.audioName = row.optString("audioName", "Training audio");
            JSONArray values = row.getJSONArray("features");
            s.features = new float[values.length()];
            for (int j = 0; j < values.length(); j++) s.features[j] = (float) values.getDouble(j);
            if (s.features.length != AudioFeatures.DIMENSIONS) continue;
            if (!presets.containsKey(s.presetHash)) {
                File f = new File(presetDir, s.presetHash + ".fxp");
                if (!f.isFile()) continue;
                PresetRecord pr = new PresetRecord();
                pr.hash = s.presetHash;
                pr.fileName = f.getName();
                pr.preset = SylenthFxp.parse(readAll(f));
                presets.put(s.presetHash, pr);
            }
            samples.add(s);
        }
        if (samples.isEmpty() || presets.isEmpty()) throw new IllegalArgumentException("The training library has no readable examples.");

        float[] scale = calculateScales(samples);
        Map<String, List<Double>> distancesByPreset = new HashMap<>();
        for (Sample s : samples) {
            if (!presets.containsKey(s.presetHash)) continue;
            double d = distance(targetFeatures, s.features, scale);
            distancesByPreset.computeIfAbsent(s.presetHash, k -> new ArrayList<>()).add(d);
        }
        List<PresetDistance> ranked = new ArrayList<>();
        for (Map.Entry<String, List<Double>> e : distancesByPreset.entrySet()) {
            List<Double> ds = e.getValue();
            Collections.sort(ds);
            int use = Math.min(3, ds.size());
            double sum = 0;
            for (int i = 0; i < use; i++) sum += ds.get(i);
            ranked.add(new PresetDistance(e.getKey(), sum / use));
        }
        ranked.sort(Comparator.comparingDouble(x -> x.distance));
        if (ranked.isEmpty()) throw new IllegalArgumentException("No compatible Sylenth1 presets were found.");

        PresetRecord base = presets.get(ranked.get(0).hash);
        // Restrict interpolation to patches with matching Sylenth1 version, chunk size and
        // discrete synth topology. If no peers match, the nearest real preset is exported intact.
        List<PresetDistance> compatible = new ArrayList<>();
        for (PresetDistance candidate : ranked) {
            SylenthFxp.Preset p = presets.get(candidate.hash).preset;
            if (p.version == base.preset.version && p.chunkSize == base.preset.chunkSize
                    && SylenthFxp.hasSameTopology(base.preset, p)) compatible.add(candidate);
        }
        int k = Math.min(3, compatible.size());
        SylenthFxp.Preset[] neighbours = new SylenthFxp.Preset[k];
        double[] weights = new double[k];
        double weightSum = 0;
        for (int i = 0; i < k; i++) {
            PresetDistance candidate = compatible.get(i);
            PresetRecord pr = presets.get(candidate.hash);
            neighbours[i] = pr.preset;
            double w = 1.0 / Math.pow(candidate.distance + 0.10, 2.0);
            weights[i] = w;
            weightSum += w;
        }
        for (int i = 0; i < weights.length; i++) weights[i] /= weightSum;

        // Blend continuous controls from the most similar examples. The closest preset supplies
        // every discrete switch, waveform, routing destination and version-specific field.
        byte[] output = SylenthFxp.synthesize(base.preset, neighbours, weights, "Sylenth AI Match");
        double score = 100.0 * Math.exp(-ranked.get(0).distance / 2.25);
        score = Math.max(0, Math.min(99, score));
        return new MatchResult(output, base.preset.name, "SylenthAI_Match.fxp", score, k, samples.size());
    }

    private static final class PresetDistance {
        final String hash; final double distance;
        PresetDistance(String hash, double distance) { this.hash = hash; this.distance = distance; }
    }

    private static float[] calculateScales(List<Sample> samples) {
        int dims = AudioFeatures.DIMENSIONS;
        double[] mean = new double[dims], sq = new double[dims];
        for (Sample s : samples) for (int i = 0; i < dims; i++) { mean[i] += s.features[i]; sq[i] += s.features[i] * s.features[i]; }
        float[] scale = new float[dims];
        for (int i = 0; i < dims; i++) {
            mean[i] /= samples.size();
            double variance = Math.max(0, sq[i] / samples.size() - mean[i] * mean[i]);
            scale[i] = (float) Math.max(0.18, Math.sqrt(variance));
        }
        return scale;
    }

    private static double distance(float[] a, float[] b, float[] scale) {
        int[] groups = {40, 40, 24, 15};
        double[] weights = {0.43, 0.18, 0.24, 0.15};
        int offset = 0;
        double total = 0;
        for (int g = 0; g < groups.length; g++) {
            double sum = 0;
            for (int i = 0; i < groups[g]; i++) {
                double z = Math.abs(a[offset + i] - b[offset + i]) / scale[offset + i];
                sum += Math.min(5.0, z);
            }
            total += weights[g] * (sum / groups[g]);
            offset += groups[g];
        }
        return total;
    }

    private JSONArray readIndex() throws Exception {
        if (!indexFile.exists()) return new JSONArray();
        byte[] bytes = readAll(indexFile);
        if (bytes.length == 0) return new JSONArray();
        JSONObject rootJson = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        return rootJson.optJSONArray("pairs") == null ? new JSONArray() : rootJson.getJSONArray("pairs");
    }

    private void writeIndex(JSONArray pairs) throws Exception {
        JSONObject rootJson = new JSONObject();
        rootJson.put("schema", 1);
        rootJson.put("pairs", pairs);
        try (FileOutputStream out = new FileOutputStream(indexFile, false)) {
            out.write(rootJson.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    public static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    public static String sha256(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder s = new StringBuilder();
        for (byte b : hash) s.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return s.toString();
    }


    private static String sha256File(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) >= 0) digest.update(buffer, 0, n);
        }
        byte[] hash = digest.digest();
        StringBuilder s = new StringBuilder();
        for (byte b : hash) s.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return s.toString();
    }

    private static void copy(File source, File destination) throws Exception {
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        }
    }

    private static void writeBytes(File destination, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(destination)) { out.write(bytes); out.flush(); }
    }

    private static String extension(String name) {
        if (name == null) return ".audio";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || name.length() - dot > 8) return ".audio";
        String ext = name.substring(dot).replaceAll("[^A-Za-z0-9.]", "");
        return ext.isEmpty() ? ".audio" : ext.toLowerCase(java.util.Locale.US);
    }
}
