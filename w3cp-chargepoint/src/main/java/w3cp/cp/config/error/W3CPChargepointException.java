package w3cp.cp.config.error;

public class W3CPChargepointException extends RuntimeException {
  public W3CPChargepointException(String message) {
    super(message);
  }

  public W3CPChargepointException(String message, Throwable e) {
    super(message, e);
  }
}
