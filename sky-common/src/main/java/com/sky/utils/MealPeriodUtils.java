package com.sky.utils;

/** Test-environment meal schedule: every configured meal period is open all day. */
public final class MealPeriodUtils {
    private MealPeriodUtils() { }

    public static boolean isOpen(String mealPeriod) {
        String period = mealPeriod == null ? "ALL" : mealPeriod;
        return "ALL".equals(period) || "BREAKFAST".equals(period)
                || "LUNCH".equals(period) || "DINNER".equals(period);
    }

    public static String displayName(String mealPeriod) {
        if ("BREAKFAST".equals(mealPeriod)) return "早餐 00:00-24:00";
        if ("LUNCH".equals(mealPeriod)) return "午餐 00:00-24:00";
        if ("DINNER".equals(mealPeriod)) return "晚餐 00:00-24:00";
        return "用餐时间：00:00-24:00";
    }
}
