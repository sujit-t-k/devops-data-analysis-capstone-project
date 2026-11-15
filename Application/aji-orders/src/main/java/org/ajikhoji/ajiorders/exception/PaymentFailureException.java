package org.ajikhoji.ajiorders.exception;

public class PaymentFailureException extends RuntimeException {

    private final String errorCode;

    public PaymentFailureException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
