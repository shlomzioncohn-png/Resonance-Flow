package com.rnd.chain.exception;
//DependencyNotFound
public class DependencyNotFoundException extends RuntimeException {

    public DependencyNotFoundException(String message) {
        super(message);
    }
}