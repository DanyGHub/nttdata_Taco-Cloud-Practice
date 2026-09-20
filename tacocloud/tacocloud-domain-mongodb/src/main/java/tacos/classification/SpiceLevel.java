package tacos.classification;

public enum SpiceLevel {
  NONE(0),
  MILD(1),
  MEDIUM(2),
  HOT(3),
  EXTRA_HOT(4);

  private final int severity;

  SpiceLevel(int severity) {
    this.severity = severity;
  }

  public int getSeverity() {
    return severity;
  }

  public static SpiceLevel max(SpiceLevel a, SpiceLevel b) {
    if (a == null) return b != null ? b : NONE;
    if (b == null) return a;
    return a.severity >= b.severity ? a : b;
  }
}
