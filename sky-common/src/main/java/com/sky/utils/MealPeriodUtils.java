package com.sky.utils;

/**
 * 用餐时段工具类。
 * <p>当前实现把 ALL / BREAKFAST / LUNCH / DINNER 都视为全天营业时段，
 * 具体时间段由运营在餐厅主数据上维护，这里只负责“是否可下单”的判定和展示文案。</p>
 */
public final class MealPeriodUtils {

    private MealPeriodUtils() {
    }

    /**
     * 判断该用餐时段当前是否营业。
     */
    public static boolean isOpen(String mealPeriod) {
        String period = mealPeriod == null ? "ALL" : mealPeriod;
        return "ALL".equals(period) || "BREAKFAST".equals(period)
                || "LUNCH".equals(period) || "DINNER".equals(period);
    }

    /**
     * 用餐时段的中文展示文案。
     */
    public static String displayName(String mealPeriod) {
        if ("BREAKFAST".equals(mealPeriod)) {
            return "早餐 00:00-24:00";
        }
        if ("LUNCH".equals(mealPeriod)) {
            return "午餐 00:00-24:00";
        }
        if ("DINNER".equals(mealPeriod)) {
            return "晚餐 00:00-24:00";
        }
        return "用餐时间：00:00-24:00";
    }
}
