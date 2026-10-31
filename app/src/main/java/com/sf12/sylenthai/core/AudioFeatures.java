package com.sf12.sylenthai.core;

import java.util.Arrays;

/** Pitch-tolerant-ish timbre fingerprint from log-mel, MFCC and temporal/stereo statistics. */
public final class AudioFeatures {
    private AudioFeatures() {}
    public static final int DIMENSIONS = 119;
    private static final int TARGET_RATE = 22050;
    private static final int FFT_SIZE = 2048;
    private static final int HOP = 512;
    private static final int MEL_BANDS = 40;
    private static final int MFCC = 12;

    public static float[] extract(float[] interleaved, int sampleRate, int channels) {
        if (interleaved == null || interleaved.length < Math.max(512, channels * 256)) {
            throw new IllegalArgumentException("Audio file is empty or too short.");
        }
        if (sampleRate < 8000 || channels < 1) throw new IllegalArgumentException("Unsupported audio format.");
        int sourceFrames = interleaved.length / channels;
        int maxFrames = sampleRate * 180;
        sourceFrames = Math.min(sourceFrames, maxFrames);
        float[] mono = new float[sourceFrames];
        double sideEnergy = 0, midEnergy = 0;
        for (int i = 0; i < sourceFrames; i++) {
            double sum = 0;
            for (int c = 0; c < channels; c++) sum += interleaved[i * channels + c];
            mono[i] = (float) (sum / channels);
            if (channels >= 2) {
                double l = interleaved[i * channels];
                double r = interleaved[i * channels + 1];
                double mid = (l + r) * 0.5;
                double side = (l - r) * 0.5;
                midEnergy += mid * mid;
                sideEnergy += side * side;
            }
        }
        float stereoWidth = channels >= 2
                ? (float) Math.sqrt(sideEnergy / Math.max(1.0e-12, midEnergy + sideEnergy)) : 0f;

        float[] signal = resample(mono, sampleRate, TARGET_RATE);
        double dc = 0;
        for (float v : signal) dc += v;
        dc /= Math.max(1, signal.length);
        for (int i = 0; i < signal.length; i++) signal[i] -= (float) dc;

        double[][] melFilters = buildMelFilters(TARGET_RATE, FFT_SIZE, MEL_BANDS);
        double[] bandSum = new double[MEL_BANDS];
        double[] bandSq = new double[MEL_BANDS];
        double[] mfccSum = new double[MFCC];
        double[] mfccSq = new double[MFCC];
        double[] globalSum = new double[7];
        double[] globalSq = new double[7];
        double[] prevMag = new double[FFT_SIZE / 2 + 1];
        double maxRms = 0;
        int count = 0;
        double[] real = new double[FFT_SIZE];
        double[] imag = new double[FFT_SIZE];
        double[] mag = new double[FFT_SIZE / 2 + 1];
        double[] bands = new double[MEL_BANDS];
        double[] logBands = new double[MEL_BANDS];
        double[] globalFrame = new double[7];
        double[] window = new double[FFT_SIZE];
        for (int i = 0; i < FFT_SIZE; i++) window[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1));

        int frameCount = Math.max(1, (signal.length - FFT_SIZE) / HOP + 1);
        frameCount = Math.min(frameCount, 8000);
        for (int f = 0; f < frameCount; f++) {
            int start = f * HOP;
            double rms2 = 0;
            for (int n = 0; n < FFT_SIZE; n++) {
                int ix = start + n;
                double v = ix < signal.length ? signal[ix] : 0;
                rms2 += v * v;
            }
            double rms = Math.sqrt(rms2 / FFT_SIZE);
            maxRms = Math.max(maxRms, rms);
        }
        if (maxRms < 1.0e-7) throw new IllegalArgumentException("The selected audio is silent.");

        for (int f = 0; f < frameCount; f++) {
            int start = f * HOP;
            Arrays.fill(imag, 0.0);
            mag[0] = 0.0;
            double rms2 = 0;
            int crossings = 0;
            double previousSample = 0;
            for (int n = 0; n < FFT_SIZE; n++) {
                int ix = start + n;
                double v = ix < signal.length ? signal[ix] : 0;
                rms2 += v * v;
                if (n > 0 && ((v >= 0 && previousSample < 0) || (v < 0 && previousSample >= 0))) crossings++;
                previousSample = v;
                real[n] = v * window[n];
            }
            double rms = Math.sqrt(rms2 / FFT_SIZE);
            if (rms < maxRms * 0.025) continue;
            fft(real, imag);
            double total = 1.0e-16;
            double weightedHz = 0;
            double rolloffTarget;
            for (int k = 1; k < mag.length; k++) {
                double p = real[k] * real[k] + imag[k] * imag[k];
                mag[k] = p;
                total += p;
                weightedHz += p * (k * (double) TARGET_RATE / FFT_SIZE);
            }
            double centroid = weightedHz / Math.max(1.0e-16, total) / (TARGET_RATE / 2.0);
            double cumulative = 0;
            double rolloff = 0;
            double logSum = 0;
            double arithmeticSum = 0;
            double flux = 0;
            double high = 0;
            for (int k = 1; k < mag.length; k++) {
                cumulative += mag[k];
                if (rolloff == 0 && cumulative >= total * 0.85) rolloff = k / (double) (mag.length - 1);
                double val = mag[k] / total;
                if (val > 0) logSum += Math.log(val);
                arithmeticSum += val;
                if (k > (mag.length * 0.55)) high += val;
                double normNow = Math.sqrt(mag[k] / total);
                double normPrev = Math.sqrt(prevMag[k] / Math.max(1.0e-16, total));
                flux += Math.max(0, normNow - normPrev);
            }
            double flatness = Math.exp(logSum / Math.max(1, mag.length - 1))
                    / Math.max(1.0e-12, arithmeticSum / Math.max(1, mag.length - 1));
            double rmsNorm = Math.min(1.0, rms / maxRms);
            double bandTotal = 1.0e-16;
            for (int b = 0; b < MEL_BANDS; b++) {
                double v = 0;
                for (int k = 0; k < mag.length; k++) v += mag[k] * melFilters[b][k];
                bands[b] = v;
                bandTotal += v;
            }
            for (int b = 0; b < MEL_BANDS; b++) {
                // Normalise per frame to down-weight master volume and emphasise timbre.
                logBands[b] = Math.log(1.0e-7 + (bands[b] / bandTotal) * 100.0);
                bandSum[b] += logBands[b];
                bandSq[b] += logBands[b] * logBands[b];
            }
            for (int m = 0; m < MFCC; m++) {
                double c = 0;
                for (int b = 0; b < MEL_BANDS; b++) {
                    c += logBands[b] * Math.cos(Math.PI * m * (b + 0.5) / MEL_BANDS);
                }
                c *= Math.sqrt(2.0 / MEL_BANDS);
                mfccSum[m] += c;
                mfccSq[m] += c * c;
            }
            globalFrame[0] = Math.log(1.0e-5 + rmsNorm);
            globalFrame[1] = centroid;
            globalFrame[2] = rolloff;
            globalFrame[3] = Math.log(1.0e-6 + Math.max(1.0e-6, flatness));
            globalFrame[4] = crossings / (double) FFT_SIZE;
            globalFrame[5] = Math.min(2.0, flux);
            globalFrame[6] = high;
            for (int j = 0; j < globalFrame.length; j++) {
                globalSum[j] += globalFrame[j];
                globalSq[j] += globalFrame[j] * globalFrame[j];
            }
            System.arraycopy(mag, 0, prevMag, 0, mag.length);
            count++;
        }
        if (count == 0) throw new IllegalArgumentException("No usable sound frames were found.");

        float[] out = new float[DIMENSIONS];
        int p = 0;
        for (int i = 0; i < MEL_BANDS; i++) out[p++] = (float) (bandSum[i] / count);
        for (int i = 0; i < MEL_BANDS; i++) out[p++] = (float) Math.sqrt(Math.max(0, bandSq[i] / count - Math.pow(bandSum[i] / count, 2)));
        for (int i = 0; i < MFCC; i++) out[p++] = (float) (mfccSum[i] / count);
        for (int i = 0; i < MFCC; i++) out[p++] = (float) Math.sqrt(Math.max(0, mfccSq[i] / count - Math.pow(mfccSum[i] / count, 2)));
        for (int i = 0; i < 7; i++) out[p++] = (float) (globalSum[i] / count);
        for (int i = 0; i < 7; i++) out[p++] = (float) Math.sqrt(Math.max(0, globalSq[i] / count - Math.pow(globalSum[i] / count, 2)));
        out[p] = stereoWidth;
        return out;
    }

    private static float[] resample(float[] input, int sourceRate, int targetRate) {
        if (sourceRate == targetRate) return input;
        int length = Math.max(1, (int) Math.round(input.length * (targetRate / (double) sourceRate)));
        float[] output = new float[length];
        double ratio = sourceRate / (double) targetRate;
        for (int i = 0; i < length; i++) {
            double pos = i * ratio;
            int a = Math.min(input.length - 1, (int) pos);
            int b = Math.min(input.length - 1, a + 1);
            double frac = pos - a;
            output[i] = (float) (input[a] * (1.0 - frac) + input[b] * frac);
        }
        return output;
    }

    private static double[][] buildMelFilters(int sampleRate, int fftSize, int bands) {
        int bins = fftSize / 2 + 1;
        double minMel = hzToMel(35);
        double maxMel = hzToMel(sampleRate / 2.0);
        double[][] filters = new double[bands][bins];
        for (int b = 0; b < bands; b++) {
            double left = melToHz(minMel + (maxMel - minMel) * b / (bands + 1.0));
            double center = melToHz(minMel + (maxMel - minMel) * (b + 1) / (bands + 1.0));
            double right = melToHz(minMel + (maxMel - minMel) * (b + 2) / (bands + 1.0));
            for (int k = 0; k < bins; k++) {
                double hz = k * sampleRate / (double) fftSize;
                if (hz >= left && hz <= center && center > left) filters[b][k] = (hz - left) / (center - left);
                else if (hz > center && hz <= right && right > center) filters[b][k] = (right - hz) / (right - center);
            }
        }
        return filters;
    }

    private static double hzToMel(double hz) { return 2595.0 * Math.log10(1.0 + hz / 700.0); }
    private static double melToHz(double mel) { return 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0); }

    private static void fft(double[] real, double[] imag) {
        int n = real.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = real[i]; real[i] = real[j]; real[j] = tr;
                double ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double wLenR = Math.cos(angle), wLenI = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double wr = 1, wi = 0;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j, v = u + len / 2;
                    double vr = real[v] * wr - imag[v] * wi;
                    double vi = real[v] * wi + imag[v] * wr;
                    real[v] = real[u] - vr; imag[v] = imag[u] - vi;
                    real[u] += vr; imag[u] += vi;
                    double nextWr = wr * wLenR - wi * wLenI;
                    wi = wr * wLenI + wi * wLenR;
                    wr = nextWr;
                }
            }
        }
    }
}
