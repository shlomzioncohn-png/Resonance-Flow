package com.rnd.chain.exception;

public class ConstraintNotFoundException extends RuntimeException {

    public ConstraintNotFoundException(String message) {
        super(message);
    }
}