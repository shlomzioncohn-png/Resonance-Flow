package com.rnd.bff.controller;

import com.rnd.bff.dto.ProjectDetailsDTO;
import com.rnd.bff.dto.ProjectsListResponseDTO;
import com.rnd.bff.service.ProjectDetailsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project Details Controller
 * Routes project detail requests
 */
@RestController
@RequestMapping("/admin/projects")
@Tag(name = "Admin Projects", description = "Project details for admins/managers")
public class ProjectDetailsController {
    
    private static final Logger logger = LoggerFactory.getLogger(ProjectDetailsController.class);
    private final ProjectDetailsService projectDetailsService;
    
    public ProjectDetailsController(ProjectDetailsService projectDetailsService) {
        this.projectDetailsService = projectDetailsService;
    }
    
    @Operation(summary = "Get project details", description = "Get full project details for admin view")
    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectDetailsDTO> getProjectDetails(@PathVariable String projectId) {
        logger.info("Project details request - projectId: {}", projectId);
        ProjectDetailsDTO project = projectDetailsService.getProjectDetails(projectId);
        return ResponseEntity.ok(project);
    }
    
    @Operation(summary = "List all projects", description = "Get list of all projects for admin dashboard")
    @GetMapping
    public ResponseEntity<ProjectsListResponseDTO> listProjects() {
        logger.info("Projects list request");
        ProjectsListResponseDTO response = projectDetailsService.listProjects();
        return ResponseEntity.ok(response);
    }
}
