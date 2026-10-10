package com.singingbetter.app;

final class PitchDetector {
    private PitchDetector() {}

    static float estimateHz(short[] samples, int length, int sampleRate) {
        if (samples == null || length < 512) return 0f;
        double mean = 0;
        for (int i = 0; i < length; i++) mean += samples[i];
        mean /= length;

        double energy = 0;
        for (int i = 0; i < length; i++) {
            double value = samples[i] - mean;
            energy += value * value;
        }
        double rms = Math.sqrt(energy / length);
        if (rms < 180) return 0f;

        int minLag = Math.max(2, sampleRate / 950);
        int maxLag = Math.min(length / 2, sampleRate / 65);
        double[] correlations = new double[maxLag + 2];
        double best = -1;
        int bestLag = -1;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double cross = 0;
            double leftEnergy = 0;
            double rightEnergy = 0;
            int limit = length - lag;
            for (int i = 0; i < limit; i += 2) {
                double left = samples[i] - mean;
                double right = samples[i + lag] - mean;
                cross += left * right;
                leftEnergy += left * left;
                rightEnergy += right * right;
            }
            double denominator = Math.sqrt(leftEnergy * rightEnergy);
            double correlation = denominator > 0 ? cross / denominator : 0;
            correlations[lag] = correlation;
            if (correlation > best) {
                best = correlation;
                bestLag = lag;
            }
        }
        if (bestLag <= 0 || best < 0.58) return 0f;

        int fundamentalLag = bestLag;
        double confidenceThreshold = Math.max(0.58, best * 0.92);
        for (int lag = minLag + 1; lag < bestLag; lag++) {
            if (correlations[lag] >= confidenceThreshold
                    && correlations[lag] >= correlations[lag - 1]
                    && correlations[lag] > correlations[lag + 1]) {
                fundamentalLag = lag;
                break;
            }
        }

        double before = fundamentalLag > minLag ? correlations[fundamentalLag - 1] : correlations[fundamentalLag];
        double center = correlations[fundamentalLag];
        double after = fundamentalLag < maxLag ? correlations[fundamentalLag + 1] : center;
        double shift = 0;
        double curvature = before - 2 * center + after;
        if (Math.abs(curvature) > 1e-9) shift = 0.5 * (before - after) / curvature;
        return (float) (sampleRate / (fundamentalLag + shift));
    }

    private static double correlationAt(short[] samples, int length, int lag, double mean) {
        double cross = 0;
        double leftEnergy = 0;
        double rightEnergy = 0;
        for (int i = 0; i < length - lag; i += 2) {
            double left = samples[i] - mean;
            double right = samples[i + lag] - mean;
            cross += left * right;
            leftEnergy += left * left;
            rightEnergy += right * right;
        }
        double denominator = Math.sqrt(leftEnergy * rightEnergy);
        return denominator > 0 ? cross / denominator : 0;
    }

    static int midi(float hz) {
        if (hz <= 0) return -1;
        return Math.round((float) (69 + 12 * (Math.log(hz / 440.0) / Math.log(2))));
    }

    static String noteName(float hz) {
        int midi = midi(hz);
        if (midi < 0) return "—";
        String[] notes = {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"};
        return notes[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1);
    }

    static float centsFrom(float hz, float targetHz) {
        if (hz <= 0 || targetHz <= 0) return 0;
        return (float) (1200 * (Math.log(hz / targetHz) / Math.log(2)));
    }
}
