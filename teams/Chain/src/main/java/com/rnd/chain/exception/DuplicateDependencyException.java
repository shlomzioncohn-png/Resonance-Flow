package com.rnd.chain.exception;
//DuplicateDependency
public class DuplicateDependencyException extends RuntimeException {

    public DuplicateDependencyException(String message) {
        super(message);
    }
}