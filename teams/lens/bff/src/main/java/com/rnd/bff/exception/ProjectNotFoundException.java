package com.rnd.bff.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when a project is not found
 */
@ResponseStatus(value = HttpStatus.NOT_FOUND)
public class ProjectNotFoundException extends RuntimeException {
    
    private final String projectId;
    
    public ProjectNotFoundException(String projectId) {
        super("Project not found: " + projectId);
        this.projectId = projectId;
    }
    
    public String getProjectId() {
        return projectId;
    }
}
