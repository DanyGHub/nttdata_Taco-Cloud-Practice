package tacos.outbox;

public enum OutboxStatus {
  NEW,
  PUBLISHING,
  PUBLISHED,
  FAILED
}
