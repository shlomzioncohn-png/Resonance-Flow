package com.rnd.chain.exception;

public class InvalidConstraintException extends BadRequestException {

    public InvalidConstraintException(String message) {
        super(message);
    }
}