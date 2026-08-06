package biz.brumm.infrastructure.adapter.out.ai.tool;

/**
 * Minimaler arithmetischer Ausdrucks-Parser (rekursiver Abstieg).
 * Unterstützt +, -, *, /, Klammern und unäres Minus für ganzzahlige und
 * dezimale Zahlen.
 */
final class ArithmeticExpressionEvaluator {

    private final String expression;
    private int position;

    private ArithmeticExpressionEvaluator(String expression) {
        this.expression = expression;
    }

    static double evaluate(String expression) {
        return new ArithmeticExpressionEvaluator(expression).parse();
    }

    private double parse() {
        skipWhitespace();
        if (position >= expression.length()) {
            throw new IllegalArgumentException("Ausdruck ist leer.");
        }
        double result = parseAdditive();
        skipWhitespace();
        if (position < expression.length()) {
            throw new IllegalArgumentException("Unerwartetes Zeichen an Position " + position + ".");
        }
        return result;
    }

    private double parseAdditive() {
        double left = parseMultiplicative();
        while (true) {
            skipWhitespace();
            if (peek('+')) {
                position++;
                left += parseMultiplicative();
            } else if (peek('-')) {
                position++;
                left -= parseMultiplicative();
            } else {
                return left;
            }
        }
    }

    private double parseMultiplicative() {
        double left = parseUnary();
        while (true) {
            skipWhitespace();
            if (peek('*')) {
                position++;
                left *= parseUnary();
            } else if (peek('/')) {
                position++;
                double divisor = parseUnary();
                if (divisor == 0) {
                    throw new IllegalArgumentException("Division durch null.");
                }
                left /= divisor;
            } else {
                return left;
            }
        }
    }

    private double parseUnary() {
        skipWhitespace();
        if (peek('+')) {
            position++;
            return parseUnary();
        }
        if (peek('-')) {
            position++;
            return -parseUnary();
        }
        return parsePrimary();
    }

    private double parsePrimary() {
        skipWhitespace();
        if (peek('(')) {
            position++;
            double value = parseAdditive();
            skipWhitespace();
            expect(')');
            return value;
        }
        return parseNumber();
    }

    private double parseNumber() {
        int start = position;
        while (position < expression.length()
                && (Character.isDigit(expression.charAt(position)) || expression.charAt(position) == '.')) {
            position++;
        }
        if (start == position) {
            throw new IllegalArgumentException("Zahl erwartet an Position " + position + ".");
        }
        try {
            return Double.parseDouble(expression.substring(start, position));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Ungültige Zahl: '" + expression.substring(start, position) + "'.");
        }
    }

    private void skipWhitespace() {
        while (position < expression.length() && Character.isWhitespace(expression.charAt(position))) {
            position++;
        }
    }

    private boolean peek(char expected) {
        return position < expression.length() && expression.charAt(position) == expected;
    }

    private void expect(char expected) {
        if (position >= expression.length() || expression.charAt(position) != expected) {
            throw new IllegalArgumentException("Erwartet '" + expected + "' an Position " + position + ".");
        }
        position++;
    }

    static String format(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
