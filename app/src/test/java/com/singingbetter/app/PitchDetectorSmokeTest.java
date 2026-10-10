package com.singingbetter.app;

public final class PitchDetectorSmokeTest {
    public static void main(String[] args) {
        int sampleRate = 44100;
        float[] cases = {82.41f, 110.0f, 146.83f, 220.0f, 329.63f, 440.0f, 659.25f, 880.0f};
        for (float expected : cases) {
            short[] samples = new short[4096];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = (short) (10000 * Math.sin(2 * Math.PI * expected * i / sampleRate));
            }
            float actual = PitchDetector.estimateHz(samples, samples.length, sampleRate);
            float cents = Math.abs(PitchDetector.centsFrom(actual, expected));
            if (actual <= 0 || cents > 20) {
                throw new AssertionError("Expected " + expected + " Hz, got " + actual + " Hz (" + cents + " cents)");
            }
            System.out.println(expected + " Hz -> " + actual + " Hz, error " + cents + " cents");

            short[] harmonics = new short[4096];
            for (int i = 0; i < harmonics.length; i++) {
                double phase = 2 * Math.PI * expected * i / sampleRate;
                harmonics[i] = (short) (8000 * Math.sin(phase) + 2800 * Math.sin(2 * phase) + 1200 * Math.sin(3 * phase));
            }
            float mixed = PitchDetector.estimateHz(harmonics, harmonics.length, sampleRate);
            float mixedCents = Math.abs(PitchDetector.centsFrom(mixed, expected));
            if (mixed <= 0 || mixedCents > 45) {
                throw new AssertionError("Harmonics: expected " + expected + " Hz, got " + mixed + " Hz (" + mixedCents + " cents)");
            }
            System.out.println("  harmonic mix -> " + mixed + " Hz, error " + mixedCents + " cents");
        }
        short[] silence = new short[4096];
        if (PitchDetector.estimateHz(silence, silence.length, sampleRate) != 0) {
            throw new AssertionError("Silence should not produce a pitch");
        }
        System.out.println("Silence -> no pitch");
    }
}
