package dev.trufflecel;

import java.lang.annotation.*;

/** Override a generated CEL field name, or omit the member from the snapshot. */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface CelField {
  String value() default "";

  boolean ignore() default false;
}
