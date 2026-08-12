package com.tridev.familyhub.feature.grocery;

import androidx.annotation.NonNull;
import java.util.Locale;

/** Adds compatible grocery quantities while preserving human-friendly units. */
public final class GroceryQuantityCalculator {
    private GroceryQuantityCalculator() { }

    @NonNull
    public static String add(@NonNull String first, @NonNull String second) {
        Quantity a = parse(first); Quantity b = parse(second);
        if (!a.valid) return second;
        if (!b.valid) return first;
        if (!a.family.equals(b.family)) return first + " + " + second;
        double base = a.baseAmount + b.baseAmount;
        if ("mass".equals(a.family)) return formatMetric(base, "g", "kg", 1000D);
        if ("volume".equals(a.family)) return formatMetric(base, "ml", "L", 1000D);
        if ("count".equals(a.family)) {
            if ("dozen".equals(a.unit) && "dozen".equals(b.unit))
                return clean(base / 12D) + " dozen";
            if (base >= 12D && base % 12D == 0D) return clean(base / 12D) + " dozen";
            return clean(base) + " pcs";
        }
        return clean(base) + " " + a.unit;
    }

    private static String formatMetric(double base, String small, String large,
                                       double factor) {
        return base >= factor ? clean(base / factor) + " " + large
                : clean(base) + " " + small;
    }

    private static Quantity parse(String raw) {
        String[] parts = raw.trim().split("\\s+", 2);
        if (parts.length < 2) return Quantity.invalid();
        double value;
        try { value = Double.parseDouble(parts[0]); }
        catch (NumberFormatException error) { return Quantity.invalid(); }
        String unit = parts[1].trim().toLowerCase(Locale.ENGLISH);
        if (unit.equals("kg")) return new Quantity(true,"mass","kg",value*1000D);
        if (unit.equals("g") || unit.equals("gram") || unit.equals("grams"))
            return new Quantity(true,"mass","g",value);
        if (unit.equals("l") || unit.equals("liter") || unit.equals("litre"))
            return new Quantity(true,"volume","L",value*1000D);
        if (unit.equals("ml")) return new Quantity(true,"volume","ml",value);
        if (unit.equals("dozen")) return new Quantity(true,"count","dozen",value*12D);
        if (unit.equals("pcs") || unit.equals("pc") || unit.equals("piece")
                || unit.equals("pieces")) return new Quantity(true,"count","pcs",value);
        return new Quantity(true,"same:"+unit,unit,value);
    }

    private static String clean(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.00001D)
            return String.valueOf((long)Math.rint(value));
        return String.format(Locale.ENGLISH,"%.2f",value)
                .replaceAll("0+$","").replaceAll("\\.$","");
    }

    private static final class Quantity {
        final boolean valid; final String family; final String unit; final double baseAmount;
        Quantity(boolean valid,String family,String unit,double baseAmount){
            this.valid=valid;this.family=family;this.unit=unit;this.baseAmount=baseAmount;
        }
        static Quantity invalid(){return new Quantity(false,"","",0D);}
    }
}
