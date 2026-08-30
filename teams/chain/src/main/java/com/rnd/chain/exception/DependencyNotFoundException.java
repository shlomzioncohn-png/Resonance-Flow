package com.rnd.chain.exception;
//DependencyNotFound
public class DependencyNotFoundException extends NotFoundException {

    public DependencyNotFoundException(String message) {
        super(message);
    }
}