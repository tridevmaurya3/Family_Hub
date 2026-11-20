package com.tridev.familyhub.feature.quickhub;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;

final class QuickUnitValues {
    static final MathContext PRECISION = MathContext.DECIMAL64;
    static final String CURRENCY = "Currency";
    static final String BASES = "Number bases";
    private static final String TEMPERATURE = "Temperature";
    private static final String FUEL = "Fuel economy";
    private static final Map<String, Map<String, BigDecimal>> FACTORS = new LinkedHashMap<>();

    static {
        define(CURRENCY, "INR:1,USD:1,EUR:1,GBP:1,AED:1,JPY:1,CAD:1,AUD:1,CHF:1,CNY:1,SGD:1,HKD:1,NZD:1,SAR:1,ZAR:1,SEK:1,NOK:1,DKK:1,PLN:1,MYR:1,THB:1,IDR:1,KRW:1,BRL:1,MXN:1,TRY:1,PHP:1");
        define("Length", "mm:.001,cm:.01,m:1,km:1000,in:.0254,ft:.3048,yd:.9144,mi:1609.344,nmi:1852");
        define("Area", "mm²:.000001,cm²:.0001,m²:1,km²:1000000,ha:10000,acre:4046.8564224,ft²:.09290304,yd²:.83612736,mi²:2589988.110336");
        define("Volume", "mL:.001,L:1,m³:1000,US fl oz:.0295735295625,US cup:.2365882365,US pint:.473176473,US gal:3.785411784,UK gal:4.54609");
        define("Weight", "g:1,kg:1000,mg:.001,t:1000000,oz:28.349523125,lb:453.59237,stone:6350.29318");
        define(TEMPERATURE, "°C:1,°F:1,K:1");
        define("Speed", "km/h:1,m/s:3.6,mph:1.609344,knot:1.852,ft/s:1.09728");
        define("Pressure", "Pa:1,kPa:1000,MPa:1000000,bar:100000,mbar:100,atm:101325,psi:6894.757293168,mmHg:133.322387415");
        define("Power", "W:1,kW:1000,MW:1000000,hp:745.699871582,PS:735.49875");
        define(BASES, "Decimal:10,Binary:2,Octal:8,Hex:16");
        define("Time", "ms:.001,s:1,min:60,h:3600,day:86400,week:604800");
        define("Data size", "bit:.125,B:1,kB:1000,MB:1000000,GB:1000000000,TB:1000000000000,KiB:1024,MiB:1048576,GiB:1073741824,TiB:1099511627776");
        define("Energy", "J:1,kJ:1000,cal:4.184,kcal:4184,Wh:3600,kWh:3600000,eV:.0000000000000000001602176634");
        define("Angle", "deg:1,rad:57.29577951308232,grad:.9,turn:360,arcmin:.01666666666666667");
        define(FUEL, "km/L:1,L/100 km:1,US mpg:.425143707430272,UK mpg:.354006189934648");
    }

    private QuickUnitValues() {}

    private static void define(String name, String specification) {
        Map<String, BigDecimal> units = new LinkedHashMap<>();
        for (String entry : specification.split(",")) {
            String[] parts = entry.split(":");
            units.put(parts[0], new BigDecimal(parts[1]));
        }
        FACTORS.put(name, units);
    }

    static String[] categories() {
        return FACTORS.keySet().toArray(new String[0]);
    }

    static String[] units(String category) {
        return FACTORS.get(category).keySet().toArray(new String[0]);
    }

    static String convert(String category, String input, String from, String to) {
        Map<String, BigDecimal> factors = FACTORS.get(category);
        if (BASES.equals(category)) {
            return new BigInteger(input, factors.get(from).intValueExact())
                    .toString(factors.get(to).intValueExact()).toUpperCase(java.util.Locale.ROOT);
        }
        BigDecimal value = new BigDecimal(input);
        if (TEMPERATURE.equals(category)) return format(temperature(value, from, to));
        if (FUEL.equals(category)) {
            if (value.signum() <= 0) throw new NumberFormatException();
            BigDecimal consumption = new BigDecimal("100");
            BigDecimal distancePerLitre = "L/100 km".equals(from)
                    ? consumption.divide(value, PRECISION) : value.multiply(factors.get(from));
            return format("L/100 km".equals(to) ? consumption.divide(distancePerLitre, PRECISION)
                    : distancePerLitre.divide(factors.get(to), PRECISION));
        }
        return format(value.multiply(factors.get(from)).divide(factors.get(to), PRECISION));
    }

    private static BigDecimal temperature(BigDecimal value, String from, String to) {
        BigDecimal offset = new BigDecimal("273.15");
        BigDecimal freezing = new BigDecimal("32");
        BigDecimal nine = new BigDecimal("9");
        BigDecimal five = new BigDecimal("5");
        BigDecimal celsius = "K".equals(from) ? value.subtract(offset)
                : "°F".equals(from) ? value.subtract(freezing).multiply(five).divide(nine, PRECISION)
                : value;
        return "K".equals(to) ? celsius.add(offset)
                : "°F".equals(to) ? celsius.multiply(nine).divide(five, PRECISION).add(freezing)
                : celsius;
    }

    static String format(BigDecimal value) {
        BigDecimal rounded = value.round(PRECISION).stripTrailingZeros();
        return Math.abs((long) rounded.scale()) <= 32 ? rounded.toPlainString() : rounded.toString();
    }
}
