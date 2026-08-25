package biz.brumm.domain.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Minimaler Cron-Expression-Parser (5 Felder: Minute Stunde Tag Monat Wochentag).
 * Unterstuetzt: *, Zahlen, Ranges (1-5), Steps (X/Y), Listen (1,3,5).
 *
 * Felder: minute hour day-of-month month day-of-week
 * Werte:  0-59   0-23  1-31         1-12  0-7 (0 und 7 = Sonntag)
 */
public final class CronExpression {

    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet daysOfMonth;
    private final BitSet months;
    private final BitSet daysOfWeek;

    private CronExpression(BitSet minutes, BitSet hours, BitSet daysOfMonth,
                            BitSet months, BitSet daysOfWeek) {
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
    }

    /**
     * Parst einen Cron-Ausdruck.
     */
    public static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Cron-Ausdruck darf nicht leer sein.");
        }
        String[] parts = expression.trim().split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException("Cron-Ausdruck muss genau 5 Felder haben: " + expression);
        }
        BitSet minutes = parseField(parts[0], 0, 59);
        BitSet hours = parseField(parts[1], 0, 23);
        BitSet daysOfMonth = parseField(parts[2], 1, 31);
        BitSet months = parseField(parts[3], 1, 12);
        BitSet daysOfWeek = parseField(parts[4], 0, 7);

        return new CronExpression(minutes, hours, daysOfMonth, months, daysOfWeek);
    }

    /**
     * Prüft, ob der Cron-Ausdruck gültig ist.
     */
    public static boolean isValid(String expression) {
        try {
            parse(expression);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Berechnet die nächste Ausführungszeit nach dem angegebenen Zeitpunkt.
     */
    public Instant nextExecutionAfter(Instant after) {
        LocalDateTime dateTime = LocalDateTime.ofInstant(after, ZoneId.systemDefault())
                .plusMinutes(1)
                .withSecond(0)
                .withNano(0);

        // Maximal 366 Tage in die Zukunft suchen
        for (int i = 0; i < 366 * 24 * 60; i++) {
            if (matches(dateTime)) {
                return dateTime.atZone(ZoneId.systemDefault()).toInstant();
            }
            dateTime = dateTime.plusMinutes(1);
        }
        throw new IllegalStateException("Kein passender Zeitpunkt innerhalb eines Jahres gefunden.");
    }

    /**
     * Prüft, ob die angegebene Zeit dem Cron-Ausdruck entspricht.
     */
    public boolean matches(LocalDateTime dateTime) {
        int minute = dateTime.getMinute();
        int hour = dateTime.getHour();
        int dayOfMonth = dateTime.getDayOfMonth();
        int month = dateTime.getMonthValue();
        int dayOfWeek = dateTime.getDayOfWeek().getValue() % 7; // Mo=1..So=7 -> 0=So..6=Sa

        return minutes.get(minute) && hours.get(hour)
                && daysOfMonth.get(dayOfMonth) && months.get(month)
                && daysOfWeek.get(dayOfWeek);
    }

    private static BitSet parseField(String field, int min, int max) {
        BitSet bits = new BitSet(max + 1);
        String[] parts = field.split(",");
        for (String part : parts) {
            parseSubField(part.trim(), min, max, bits);
        }
        return bits;
    }

    private static void parseSubField(String subField, int min, int max, BitSet bits) {
        // Step: */5 oder 1-10/2
        String[] stepParts = subField.split("/");
        String range = stepParts[0];
        int step = stepParts.length > 1 ? Integer.parseInt(stepParts[1]) : 1;

        if (step < 1) {
            throw new IllegalArgumentException("Step muss mindestens 1 sein: " + subField);
        }

        if ("*".equals(range)) {
            for (int i = min; i <= max; i += step) {
                bits.set(i);
            }
        } else if (range.contains("-")) {
            String[] rangeParts = range.split("-");
            int rangeMin = Integer.parseInt(rangeParts[0]);
            int rangeMax = Integer.parseInt(rangeParts[1]);
            if (rangeMin < min || rangeMax > max || rangeMin > rangeMax) {
                throw new IllegalArgumentException("Ungültiger Bereich: " + range);
            }
            for (int i = rangeMin; i <= rangeMax; i += step) {
                bits.set(i);
            }
        } else {
            int value = Integer.parseInt(range);
            if (value < min || value > max) {
                throw new IllegalArgumentException("Wert außerhalb des Bereichs [" + min + "-" + max + "]: " + value);
            }
            bits.set(value);
        }
    }

    @Override
    public String toString() {
        return "CronExpression{minutes=" + bitsToString(minutes) +
                ", hours=" + bitsToString(hours) +
                ", daysOfMonth=" + bitsToString(daysOfMonth) +
                ", months=" + bitsToString(months) +
                ", daysOfWeek=" + bitsToString(daysOfWeek) + "}";
    }

    private static String bitsToString(BitSet bits) {
        Set<Integer> set = new HashSet<>();
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            set.add(i);
        }
        return set.toString();
    }
}
