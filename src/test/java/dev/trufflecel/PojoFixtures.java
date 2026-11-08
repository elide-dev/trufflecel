package dev.trufflecel;

import java.time.*;
import java.util.*;

public final class PojoFixtures {
  private PojoFixtures() {}

  public enum State {
    ACTIVE,
    DISABLED
  }

  @CelPojo("example.Address")
  public record Address(@CelField("zip") int postalCode, String street) {}

  @CelPojo("example.User")
  public record User(
      long id,
      List<Address> addresses,
      Map<String, List<Integer>> groups,
      State state,
      byte[] payload,
      @CelField(ignore = true) Object ignored) {}

  @CelPojo
  public record Envelope(User user) {}

  @CelPojo
  public static final class Bean {
    public int count;
    public static int unrelated;

    @CelField(ignore = true)
    public Object ignored;

    private String label;

    public Bean(String label) {
      this.label = label;
    }

    public String getLabel() {
      return label;
    }

    public void setLabel(String label) {
      this.label = label;
    }

    public boolean isReady() {
      return true;
    }

    public Boolean isEnabled() {
      return Boolean.TRUE;
    }

    public String getURL() {
      return "https://example.test";
    }

    @CelField("renamed")
    public int custom() {
      return 4;
    }

    @CelField(ignore = true)
    public Object getIgnored() {
      return ignored;
    }

    public int size() {
      return 1;
    }
  }

  @CelPojo
  public static final class Recursive {
    public Recursive next;
    public List<Recursive> children = List.of();
  }

  @CelPojo
  public record Scalars(
      byte a,
      short b,
      int c,
      long d,
      float e,
      double f,
      boolean g,
      char h,
      Byte i,
      Short j,
      Integer k,
      Long l,
      Float m,
      Double n,
      Boolean o,
      Character p,
      String q,
      UInt r,
      Bytes s,
      Instant t,
      Duration u) {}
}
