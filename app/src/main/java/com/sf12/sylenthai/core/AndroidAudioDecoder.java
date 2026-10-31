package com.sf12.sylenthai.core;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class AndroidAudioDecoder {
    private AndroidAudioDecoder() {}

    public static final class AudioData {
        public final float[] samples;
        public final int sampleRate;
        public final int channels;
        public AudioData(float[] samples, int sampleRate, int channels) {
            this.samples = samples; this.sampleRate = sampleRate; this.channels = channels;
        }
    }

    public static AudioData decode(File file) throws Exception {
        if (file == null || !file.isFile() || file.length() < 16) throw new IllegalArgumentException("Audio file is missing or empty.");
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] head = new byte[12]; raf.readFully(head);
            if (new String(head, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(head, 8, 4, StandardCharsets.US_ASCII).equals("WAVE")) {
                return decodeWav(file);
            }
        }
        return decodeCompressed(file);
    }

    private static AudioData decodeWav(File file) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(12);
            int format = -1, channels = 0, sampleRate = 0, bits = 0;
            long dataOffset = -1, dataLength = 0;
            while (raf.getFilePointer() + 8 <= raf.length()) {
                byte[] id = new byte[4]; raf.readFully(id);
                long size = readUnsignedIntLE(raf);
                long start = raf.getFilePointer();
                String chunk = new String(id, StandardCharsets.US_ASCII);
                if (chunk.equals("fmt ")) {
                    if (size < 16) throw new IllegalArgumentException("Invalid WAV format chunk.");
                    format = readUnsignedShortLE(raf);
                    channels = readUnsignedShortLE(raf);
                    sampleRate = (int) readUnsignedIntLE(raf);
                    readUnsignedIntLE(raf); // byte rate
                    readUnsignedShortLE(raf); // block align
                    bits = readUnsignedShortLE(raf);
                    if (format == 0xfffe && size >= 40) {
                        raf.seek(start + 24);
                        byte[] sub = new byte[2]; raf.readFully(sub);
                        format = (sub[0] & 0xff) | ((sub[1] & 0xff) << 8);
                    }
                } else if (chunk.equals("data")) {
                    dataOffset = start;
                    dataLength = Math.min(size, raf.length() - start);
                    break;
                }
                long next = start + size + (size & 1L);
                if (next > raf.length()) break;
                raf.seek(next);
            }
            if (dataOffset < 0 || channels < 1 || sampleRate < 8000) throw new IllegalArgumentException("WAV file is missing a valid audio stream.");
            if (format != 1 && format != 3) throw new IllegalArgumentException("Only PCM or IEEE-float WAV audio is supported.");
            if (format == 3 && bits != 32 && bits != 64) throw new IllegalArgumentException("IEEE-float WAV must use 32-bit or 64-bit samples.");
            if (format == 1 && bits != 8 && bits != 16 && bits != 24 && bits != 32) throw new IllegalArgumentException("Unsupported PCM WAV bit depth: " + bits);
            int bytesPerSample = bits / 8;
            long count = dataLength / bytesPerSample;
            long maxSamples = (long) sampleRate * 180 * channels;
            count = Math.min(count, maxSamples);
            count -= count % channels;
            if (count > 20_000_000L) throw new IllegalArgumentException("Audio is too long for on-device analysis (maximum 180 seconds).");
            float[] samples = new float[(int) count];
            raf.seek(dataOffset);
            // Decode in small blocks to avoid a second full-size copy of the WAV data.
            int chunkSamples = Math.max(channels, (65536 / bytesPerSample / channels) * channels);
            byte[] raw = new byte[chunkSamples * bytesPerSample];
            int sampleOffset = 0;
            while (sampleOffset < samples.length) {
                int sampleCount = Math.min(chunkSamples, samples.length - sampleOffset);
                int byteCount = sampleCount * bytesPerSample;
                raf.readFully(raw, 0, byteCount);
                ByteBuffer b = ByteBuffer.wrap(raw, 0, byteCount).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < sampleCount; i++) {
                    int destination = sampleOffset + i;
                    if (format == 3 && bits == 32) samples[destination] = clamp(b.getFloat());
                    else if (format == 3 && bits == 64) samples[destination] = clamp((float) b.getDouble());
                    else if (bits == 8) samples[destination] = ((b.get() & 0xff) - 128) / 128f;
                    else if (bits == 16) samples[destination] = b.getShort() / 32768f;
                    else if (bits == 24) {
                        int v = (b.get() & 0xff) | ((b.get() & 0xff) << 8) | ((b.get() & 0xff) << 16);
                        if ((v & 0x800000) != 0) v |= 0xff000000;
                        samples[destination] = v / 8388608f;
                    } else samples[destination] = b.getInt() / 2147483648f;
                }
                sampleOffset += sampleCount;
            }
            return new AudioData(samples, sampleRate, channels);
        }
    }

    private static AudioData decodeCompressed(File file) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        FloatBufferBuilder output = new FloatBufferBuilder();
        int sampleRate = 44100, channels = 2, pcmEncoding = 2;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int audioTrack = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { audioTrack = i; format = candidate; break; }
            }
            if (audioTrack < 0 || format == null) throw new IllegalArgumentException("No audio stream was found in this file.");
            extractor.selectTrack(audioTrack);
            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();
            boolean inputDone = false, outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long timeoutUs = 10_000;
            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(timeoutUs);
                    if (inIndex >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIndex);
                        if (in == null) throw new IllegalStateException("Decoder input buffer unavailable.");
                        in.clear();
                        int size = extractor.readSampleData(in, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long time = extractor.getSampleTime();
                            codec.queueInputBuffer(inIndex, 0, size, Math.max(0, time), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, timeoutUs);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat out = codec.getOutputFormat();
                    if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING);
                } else if (outIndex >= 0) {
                    ByteBuffer out = codec.getOutputBuffer(outIndex);
                    if (out != null && info.size > 0) {
                        out.position(info.offset); out.limit(info.offset + info.size);
                        out.order(ByteOrder.LITTLE_ENDIAN);
                        if (pcmEncoding == 4) {
                            while (out.remaining() >= 4) output.add(clamp(out.getFloat()));
                        } else if (pcmEncoding == 3) {
                            while (out.remaining() >= 1) output.add(((out.get() & 0xff) - 128) / 128f);
                        } else {
                            while (out.remaining() >= 2) output.add(out.getShort() / 32768f);
                        }
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outIndex, false);
                    if (eos) outputDone = true;
                    if (output.size() > sampleRate * Math.max(1, channels) * 180) {
                        outputDone = true;
                    }
                }
            }
            if (output.size() == 0) throw new IllegalArgumentException("Android could not decode this audio file.");
            int usable = output.size() - (output.size() % Math.max(1, channels));
            return new AudioData(output.toArray(usable), sampleRate, channels);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    private static int readUnsignedShortLE(RandomAccessFile raf) throws Exception {
        int a = raf.read(); int b = raf.read();
        if (a < 0 || b < 0) throw new IllegalArgumentException("Truncated WAV file.");
        return a | (b << 8);
    }
    private static long readUnsignedIntLE(RandomAccessFile raf) throws Exception {
        long a = raf.read(), b = raf.read(), c = raf.read(), d = raf.read();
        if (d < 0) throw new IllegalArgumentException("Truncated WAV file.");
        return a | (b << 8) | (c << 16) | (d << 24);
    }
    private static float clamp(float v) { return Float.isFinite(v) ? Math.max(-1f, Math.min(1f, v)) : 0f; }

    private static final class FloatBufferBuilder {
        private float[] data = new float[65536];
        private int size = 0;
        void add(float v) {
            if (size >= data.length) data = Arrays.copyOf(data, Math.min(data.length * 2, 20_000_000));
            if (size < data.length) data[size++] = v;
        }
        int size() { return size; }
        float[] toArray(int length) { return Arrays.copyOf(data, length); }
    }
}
