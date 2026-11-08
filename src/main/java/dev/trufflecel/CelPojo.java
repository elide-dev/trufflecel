package dev.trufflecel;

import java.lang.annotation.*;

/**
 * Generate a reflection-free CEL snapshot adapter for public fields, getters or record components.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface CelPojo {
  /** CEL object type name; defaults to the qualified Java name. */
  String value() default "";
}
