package biz.brumm.infrastructure.adapter.in.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Markiert einen Endpoint als deprecated (P4-16): der Interceptor setzt
 * {@code Deprecation}, {@code Sunset} und {@code Link}-Header.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiDeprecation {
    /** Version, seit der deprecated (z. B. "v1"). */
    String since() default "";

    /** Sunset-Datum (ISO-8601, z. B. "2027-01-01"). */
    String sunset() default "";

    /** Nachfolger-Endpoint/Pfad. */
    String replacement() default "";
}