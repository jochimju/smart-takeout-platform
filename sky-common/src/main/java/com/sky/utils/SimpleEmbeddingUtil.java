package com.sky.utils;

import java.util.Locale;

public class SimpleEmbeddingUtil {

    private static final int VECTOR_SIZE = 128;

    private SimpleEmbeddingUtil() {
    }

    public static double[] embed(String text) {
        double[] vector = new double[VECTOR_SIZE];
        if (text == null || text.length() == 0) {
            return vector;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            int index = Math.abs(c) % VECTOR_SIZE;
            vector[index] += 1D;
        }
        normalize(vector);
        return vector;
    }

    public static double cosine(double[] left, double[] right) {
        if (left == null || right == null || left.length != right.length) {
            return 0D;
        }
        double score = 0D;
        for (int i = 0; i < left.length; i++) {
            score += left[i] * right[i];
        }
        return score;
    }

    private static void normalize(double[] vector) {
        double sum = 0D;
        for (double value : vector) {
            sum += value * value;
        }
        if (sum == 0D) {
            return;
        }
        double norm = Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }
}
