package com.rnd.chain.exception;
//InvalidDependency - A depends on A
public class InvalidDependencyException extends RuntimeException {

    public InvalidDependencyException(String message) {
        super(message);
    }
}