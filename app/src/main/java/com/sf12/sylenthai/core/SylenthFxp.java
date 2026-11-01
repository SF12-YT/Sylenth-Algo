package com.sf12.sylenthai.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Parser/writer for the Sylenth1 single-preset FPCh/Sylenth chunk format.
 *  Unknown bytes and discrete parameter encodings are preserved from the best neighbour.
 */
public final class SylenthFxp {
    private SylenthFxp() {}

    public static final int OUTER_HEADER_SIZE = 60;
    public static final int[] FLOAT_OFFSETS = new int[] {
        20,24,28,32,36,40,44,48,52,60,64,72,76,80,84,88,96,100,
        104,108,112,116,120,124,128,132,140,144,148,152,156,160,164,
        172,176,180,184,188,192,200,210,212,216,224,234,236,240,244,
        256,260,264,276,280,284,292,296,300,304,308,312,316,320,324,
        328,332,336,344,348,356,360,364,368,376,384,392,396,404,408,
        412,416,424,432,440,444,452,456,460,464,472,480,488,492,500,
        504,508,512,520,528,536,540,544,548,552,556,560,564,568,572,
        578,584,588,592,596,600,604,680,712,716,720,724,728,732,736,
        740,776,780,784,788,792,796,800,804,808,812,816,820,824,828,
        832,836,840,844,852,856,864,868,876,880
    };

    // Float-valued fields that represent discrete choices or note divisions are kept
    // from the nearest real preset instead of being averaged.
    private static final int[] DO_NOT_INTERPOLATE = new int[] {
        60,64,72,148,152,210,234,356,360,404,408,452,456,500,504,572,578,680,712,716,720,724,
        728,732,736,740,776,780,784,788,792,796,800,804
    };
    private static final int[] LOG_INTERPOLATE = new int[] {
        128,132,176,184,188,212,236,264,284,536,552
    };

    // Only interpolate between patches with the same discrete architecture. These are the
    // non-float enum, switch, waveform, routing and per-step fields from the chunk map.
    private static final int[][] TOPOLOGY_FIELDS = new int[][] {
        {56,4},{70,2},{94,2},{138,2},{170,2},{198,2},{204,4},{222,2},{228,4},
        {250,2},{254,2},{268,4},{274,2},{288,4},{342,2},
        {354,2},{374,2},{382,2},{388,4},{402,2},{422,2},{430,2},{436,4},
        {450,2},{470,2},{478,2},{484,4},{498,2},{518,2},{526,2},{532,4},
        {582,2},{610,2},{614,2},
        {618,2},{622,2},{626,2},{630,2},{634,2},{638,2},{642,2},{646,2},
        {650,2},{654,2},{658,2},{662,2},{666,2},{670,2},{674,2},{678,2},
        {848,4},{860,4},{872,4},{884,4},
        {890,2},{894,2},{898,2},{902,2},{906,2},{910,2},{914,2},{918,2},
        {922,2},{926,2},{930,2},{934,2},{939,2},{942,2},{946,2},{950,2},
        {954,2},{958,2},{962,2},{966,2},{970,2},{974,2},{978,2},{982,2},{986,2},{990,2}
    };

    public static final class Preset {
        public final byte[] bytes;
        public final int version;
        public final int chunkSize;
        public final int innerNameOffset;
        public final String name;
        public final float[] params;

        private Preset(byte[] data, int version, int chunkSize, int innerNameOffset, String name) {
            this.bytes = data;
            this.version = version;
            this.chunkSize = chunkSize;
            this.innerNameOffset = innerNameOffset;
            this.name = name;
            this.params = readParams(data);
        }

        private float[] readParams(byte[] data) {
            float[] values = new float[FLOAT_OFFSETS.length];
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < FLOAT_OFFSETS.length; i++) {
                int pos = OUTER_HEADER_SIZE + FLOAT_OFFSETS[i];
                values[i] = (pos + 4 <= data.length) ? b.getFloat(pos) : Float.NaN;
            }
            return values;
        }
    }

    public static Preset parse(byte[] data) throws IllegalArgumentException {
        if (data == null || data.length < OUTER_HEADER_SIZE) {
            throw new IllegalArgumentException("File is too short to contain a complete VST FXP header (60 bytes required).");
        }
        if (!ascii(data, 0, 4).equals("CcnK")) throw new IllegalArgumentException("This is not a VST FXP file (missing CcnK header).");
        if (!ascii(data, 8, 4).equals("FPCh")) throw new IllegalArgumentException("This FXP is not a chunk-based single preset (FPCh).");
        if (!ascii(data, 16, 4).equals("syl1")) throw new IllegalArgumentException("This FXP does not identify itself as a Sylenth1 preset.");

        long declaredFileSize = Integer.toUnsignedLong(readIntBE(data, 4));
        if (declaredFileSize > data.length - 8L) throw new IllegalArgumentException("The FXP header declares a file size larger than the file received; it may be truncated.");
        long declaredChunkSize = Integer.toUnsignedLong(readIntBE(data, 56));
        if (declaredChunkSize < 8 || declaredChunkSize > data.length - OUTER_HEADER_SIZE) {
            throw new IllegalArgumentException("The FXP chunk size is invalid or the preset data is truncated.");
        }
        int chunkSize = (int) declaredChunkSize;
        String innerMagic = ascii(data, OUTER_HEADER_SIZE, 4);
        if (!innerMagic.equals("1lys") && !innerMagic.equals("syl1")) throw new IllegalArgumentException("The embedded Sylenth1 data header is not recognised.");
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int version = b.getInt(OUTER_HEADER_SIZE + 4);
        if (version != 2011 && version != 2202 && version != 2211) throw new IllegalArgumentException("Unsupported Sylenth1 preset data version: " + version);
        int nameOffset = version == 2011 ? 988 : 992;
        if (chunkSize < nameOffset) {
            throw new IllegalArgumentException("Sylenth1 preset data is truncated: version " + version
                    + " requires at least " + nameOffset + " bytes for its parameter data, but only " + chunkSize + " are present.");
        }
        int innerNameLength = Math.max(0, Math.min(36, chunkSize - nameOffset));
        String name = innerNameLength > 0 ? readFixedAscii(data, OUTER_HEADER_SIZE + nameOffset, innerNameLength).trim() : "";
        if (name.isEmpty()) name = readFixedAscii(data, 28, 28).trim();
        if (name.isEmpty()) name = "Unnamed Sylenth1 preset";
        return new Preset(Arrays.copyOf(data, data.length), version, chunkSize, nameOffset, name);
    }

    public static byte[] synthesize(Preset base, Preset[] neighbours, double[] weights, String outputName) {
        int outputChunkSize = Math.max(base.chunkSize, base.innerNameOffset + 36);
        int addedBytes = outputChunkSize - base.chunkSize;
        byte[] out;
        if (addedBytes > 0) {
            int oldChunkEnd = OUTER_HEADER_SIZE + base.chunkSize;
            out = new byte[base.bytes.length + addedBytes];
            System.arraycopy(base.bytes, 0, out, 0, oldChunkEnd);
            System.arraycopy(base.bytes, oldChunkEnd, out, oldChunkEnd + addedBytes, base.bytes.length - oldChunkEnd);
        } else {
            out = Arrays.copyOf(base.bytes, base.bytes.length);
        }
        String safeName = sanitizeName(outputName == null ? "Sylenth AI Match" : outputName);
        writeFixedAscii(out, 28, 28, safeName);
        writeFixedAscii(out, OUTER_HEADER_SIZE + base.innerNameOffset, 36, safeName);
        if (neighbours != null && weights != null && neighbours.length == weights.length && neighbours.length > 1) {
            boolean compatible = true;
            for (Preset p : neighbours) {
                if (p == null || p.version != base.version || p.chunkSize != base.chunkSize || !hasSameTopology(base, p)) { compatible = false; break; }
            }
            if (compatible) {
                ByteBuffer target = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
                for (int j = 0; j < FLOAT_OFFSETS.length; j++) {
                    int offset = FLOAT_OFFSETS[j];
                    if (contains(DO_NOT_INTERPOLATE, offset)) continue;
                    int pos = OUTER_HEADER_SIZE + offset;
                    double wSum = 0.0, valueSum = 0.0;
                    boolean valid = true;
                    for (int n = 0; n < neighbours.length; n++) {
                        float v = neighbours[n].params[j];
                        if (!Float.isFinite(v) || !Double.isFinite(weights[n]) || weights[n] <= 0) { valid = false; break; }
                        double transformed = contains(LOG_INTERPOLATE, offset) && v > 0 ? Math.log(v) : v;
                        valueSum += transformed * weights[n];
                        wSum += weights[n];
                    }
                    if (!valid || wSum <= 0) continue;
                    double mean = valueSum / wSum;
                    if (contains(LOG_INTERPOLATE, offset)) mean = Math.exp(mean);
                    if (Double.isFinite(mean) && Math.abs(mean) < 1.0e7) target.putFloat(pos, (float) mean);
                }
            }
        }
        ByteBuffer be = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        be.putInt(4, out.length - 8);
        be.putInt(56, outputChunkSize);
        return out;
    }

    public static boolean hasSameTopology(Preset a, Preset b) {
        for (int[] field : TOPOLOGY_FIELDS) {
            // ZEQMode was added only in newer preset data; in v2011 these bytes belong to the name.
            if (a.version == 2011 && field[0] == 990) continue;
            int offset = OUTER_HEADER_SIZE + field[0];
            int length = field[1];
            if (offset + length > a.bytes.length || offset + length > b.bytes.length) return false;
            for (int i = 0; i < length; i++) {
                if (a.bytes[offset + i] != b.bytes[offset + i]) return false;
            }
        }
        return true;
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) if (v == value) return true;
        return false;
    }

    private static String sanitizeName(String value) {
        String s = value.replaceAll("[^A-Za-z0-9 _-]", "_").trim();
        if (s.isEmpty()) s = "Sylenth AI Match";
        return s.length() > 27 ? s.substring(0, 27) : s;
    }

    private static String ascii(byte[] data, int offset, int length) {
        return new String(data, offset, length, StandardCharsets.US_ASCII);
    }

    private static String readFixedAscii(byte[] data, int offset, int length) {
        int end = offset;
        int limit = Math.min(data.length, offset + length);
        while (end < limit && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static void writeFixedAscii(byte[] data, int offset, int length, String value) {
        Arrays.fill(data, offset, Math.min(data.length, offset + length), (byte) 0);
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(encoded, 0, data, offset, Math.min(encoded.length, length - 1));
    }

    private static int readIntBE(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }
}
