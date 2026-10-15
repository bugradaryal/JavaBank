package com.example.bank.common.error;

public class BankException extends RuntimeException {

    private final ErrorCode code;

    public BankException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
