package com.cmex.bolt.spot.util;

import java.util.HashMap;
import java.util.Map;

public class NumberUtils {

    public static final Map<Integer, Double> CACHED_DOUBLE_NUMBER = new HashMap<>();
    public static final Map<Integer, Long> CACHED_LONG_NUMBER = new HashMap<>();

    static {
        for (int i = 0; i <= 18; i++) {
            CACHED_DOUBLE_NUMBER.put(i, Math.pow(10, i));
            CACHED_LONG_NUMBER.put(i, (long) Math.pow(10, i));
        }
    }

    public static double powDouble(int i) {
        if (i < 0 || i > 18) {
            throw new IllegalArgumentException("i must between 0 and 18");
        }
        return CACHED_DOUBLE_NUMBER.get(i);
    }

    public static long powLong(int i) {
        if (i < 0 || i > 18) {
            throw new IllegalArgumentException("i must between 0 and 18");
        }
        return CACHED_LONG_NUMBER.get(i);
    }

}
