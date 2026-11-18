package com.tridev.familyhub.feature.quickhub;

final class CalculatorExpression {
    private final String input;
    private final boolean degrees;
    private int index;

    private CalculatorExpression(String input, boolean degrees) {
        this.input = input;
        this.degrees = degrees;
    }

    static double evaluate(String input, boolean degrees) {
        if (input.length() > 256) throw new IllegalArgumentException();
        CalculatorExpression parser = new CalculatorExpression(input, degrees);
        double result = parser.sum();
        if (parser.index != input.length() || !Double.isFinite(result)) {
            throw new IllegalArgumentException();
        }
        return result;
    }

    private boolean take(char token) {
        if (index == input.length() || input.charAt(index) != token) return false;
        index++;
        return true;
    }

    private double sum() {
        double result = product();
        while (true) {
            if (take('+')) result += product();
            else if (take('-')) result -= product();
            else return result;
        }
    }

    private double product() {
        double result = signed();
        while (true) {
            if (take('×')) result *= signed();
            else if (take('÷')) result /= signed();
            else return result;
        }
    }

    private double signed() {
        if (take('-')) return -signed();
        if (take('+')) return signed();
        double value = atom();
        while (take('%')) value /= 100;
        return take('^') ? Math.pow(value, signed()) : value;
    }

    private double atom() {
        if (take('(')) return group();
        if (take('π')) return Math.PI;
        if (take('e')) return Math.E;
        int start = index;
        while (index < input.length() && Character.isLowerCase(input.charAt(index))) index++;
        if (index != start) {
            String name = input.substring(start, index);
            if (!take('(')) throw new IllegalArgumentException();
            return function(name, group());
        }
        while (index < input.length()
                && (Character.isDigit(input.charAt(index)) || input.charAt(index) == '.')) index++;
        return Double.parseDouble(input.substring(start, index));
    }

    private double group() {
        double value = sum();
        if (!take(')')) throw new IllegalArgumentException();
        return value;
    }

    private double function(String name, double value) {
        double angle = degrees ? Math.toRadians(value) : value;
        switch (name) {
            case "sin": return Math.sin(angle);
            case "cos": return Math.cos(angle);
            case "tan": return Math.tan(angle);
            case "sqrt": return Math.sqrt(value);
            case "log": return Math.log10(value);
            case "ln": return Math.log(value);
            default: throw new IllegalArgumentException();
        }
    }
}
