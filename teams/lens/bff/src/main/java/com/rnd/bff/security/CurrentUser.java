package com.rnd.bff.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to inject the current user ID from X-User-Id header into a controller method.
 * Used by CurrentUserArgumentResolver to extract and provide the UUID.
 * 
 * Separates user resolution logic into one place, making it easier to switch auth mechanisms later.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
