package com.rnd.bff.service;

import com.rnd.bff.dto.ProjectDetailsDTO;
import com.rnd.bff.dto.ProjectSummaryDTO;
import com.rnd.bff.dto.ProjectsListResponseDTO;
import com.rnd.bff.exception.ProjectNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Project Details Service
 * Retrieves project details for admin/manager views
 * Currently backed by mock data aligned with Manager Dashboard projects
 */
@Service
public class ProjectDetailsService {
    
    private static final Logger logger = LoggerFactory.getLogger(ProjectDetailsService.class);
    private final Map<String, ProjectDetailsDTO> projectsById = new HashMap<>();
    
    public ProjectDetailsService() {
        initializeMockData();
    }
    
    public ProjectDetailsDTO getProjectDetails(String projectId) {
        logger.info("Fetching project details - projectId: {}", projectId);
        
        ProjectDetailsDTO project = projectsById.get(projectId);
        if (project == null) {
            logger.warn("Project not found - projectId: {}", projectId);
            throw new ProjectNotFoundException(projectId);
        }
        
        return project;
    }
    
    public ProjectsListResponseDTO listProjects() {
        logger.info("Fetching projects list");
        
        return ProjectsListResponseDTO.builder()
                .projects(projectsById.values().stream()
                        .map(this::toProjectSummary)
                        .toList())
                .total(projectsById.size())
                .build();
    }
    
    private ProjectSummaryDTO toProjectSummary(ProjectDetailsDTO project) {
        return ProjectSummaryDTO.builder()
                .projectId(project.getProjectId())
                .projectName(project.getProjectName())
                .description(project.getDescription())
                .status(project.getStatus())
                .totalTasks(project.getTotalTasks())
                .completedTasks(project.getCompletedTasks())
                .team(project.getTeam().stream()
                        .limit(6)
                        .toList())
                .build();
    }
    
    private void initializeMockData() {
        projectsById.put("p1", createHealthyProject());
        projectsById.put("p2", createAtRiskProject());
        projectsById.put("p3", createCriticalProject());
    }
    
    private ProjectDetailsDTO createHealthyProject() {
        return ProjectDetailsDTO.builder()
                .projectId("p1")
                .projectName("Checkout Revamp")
                .description("Modernizing the checkout flow with improved UX and faster processing")
                .status("healthy")
                .startDate("Feb 1, 2025")
                .endDate("Jun 30, 2025")
                .totalTasks(18)
                .inProgressTasks(6)
                .blockedTasks(0)
                .completedTasks(13)
                .progressPercentage(72)
                .processStages(List.of(
                        Map.of("name", "Planning", "count", 2),
                        Map.of("name", "In Progress", "count", 3),
                        Map.of("name", "Review", "count", 2),
                        Map.of("name", "Testing", "count", 1),
                        Map.of("name", "Done", "count", 10)
                ))
                .team(List.of(
                        Map.of("id", "1", "name", "Dana Cohen", "role", "Tech Lead", 
                               "avatar", "https://i.pravatar.cc/150?img=1", "activeTasks", 4, "workload", "At Capacity"),
                        Map.of("id", "2", "name", "Noa Levi", "role", "Senior Engineer",
                               "avatar", "https://i.pravatar.cc/150?img=3", "activeTasks", 3, "workload", "Available"),
                        Map.of("id", "3", "name", "Omer Katz", "role", "Backend Engineer",
                               "avatar", "https://i.pravatar.cc/150?img=5", "activeTasks", 2, "workload", "Available")
                ))
                .recentActivities(List.of(
                        Map.of("id", "1", "action", "completed", "description", "Payment form validation", 
                               "actor", Map.of("name", "Dana Cohen", "avatar", "https://i.pravatar.cc/150?img=1"),
                               "timestamp", "1h ago", "type", "success"),
                        Map.of("id", "2", "action", "merged", "description", "Address auto-fill feature",
                               "actor", Map.of("name", "Noa Levi", "avatar", "https://i.pravatar.cc/150?img=3"),
                               "timestamp", "3h ago", "type", "success"),
                        Map.of("id", "3", "action", "commented", "description", "on security review",
                               "actor", Map.of("name", "Omer Katz", "avatar", "https://i.pravatar.cc/150?img=5"),
                               "timestamp", "5h ago")
                ))
                .build();
    }
    
    private ProjectDetailsDTO createAtRiskProject() {
        return ProjectDetailsDTO.builder()
                .projectId("p2")
                .projectName("Fraud Detection API")
                .description("Implementing ML-based fraud detection with real-time alerting")
                .status("warning")
                .startDate("Jan 15, 2025")
                .endDate("May 31, 2025")
                .totalTasks(22)
                .inProgressTasks(9)
                .blockedTasks(2)
                .completedTasks(9)
                .progressPercentage(41)
                .processStages(List.of(
                        Map.of("name", "Planning", "count", 3),
                        Map.of("name", "In Progress", "count", 8),
                        Map.of("name", "Review", "count", 2),
                        Map.of("name", "Testing", "count", 1),
                        Map.of("name", "Done", "count", 8)
                ))
                .team(List.of(
                        Map.of("id", "4", "name", "Roni Peretz", "role", "Tech Lead", 
                               "avatar", "https://i.pravatar.cc/150?img=2", "activeTasks", 6, "workload", "Overloaded"),
                        Map.of("id", "5", "name", "Itay Ben David", "role", "ML Engineer",
                               "avatar", "https://i.pravatar.cc/150?img=4", "activeTasks", 4, "workload", "At Capacity")
                ))
                .recentActivities(List.of(
                        Map.of("id", "1", "action", "blocked", "description", "ML model training - waiting for data",
                               "actor", Map.of("name", "Roni Peretz", "avatar", "https://i.pravatar.cc/150?img=2"),
                               "timestamp", "2h ago", "type", "warning"),
                        Map.of("id", "2", "action", "assigned", "description", "Performance optimization to Itay",
                               "actor", Map.of("name", "System", "avatar", "https://i.pravatar.cc/150?img=0"),
                               "timestamp", "1d ago")
                ))
                .build();
    }
    
    private ProjectDetailsDTO createCriticalProject() {
        return ProjectDetailsDTO.builder()
                .projectId("p3")
                .projectName("Mobile Onboarding")
                .description("New mobile app onboarding flow - critical for Q2 launch")
                .status("critical")
                .startDate("Dec 1, 2024")
                .endDate("Mar 31, 2025")
                .totalTasks(15)
                .inProgressTasks(3)
                .blockedTasks(5)
                .completedTasks(2)
                .progressPercentage(13)
                .processStages(List.of(
                        Map.of("name", "Planning", "count", 4),
                        Map.of("name", "In Progress", "count", 2),
                        Map.of("name", "Review", "count", 1),
                        Map.of("name", "Testing", "count", 0),
                        Map.of("name", "Done", "count", 2)
                ))
                .team(List.of(
                        Map.of("id", "6", "name", "Shira Adler", "role", "Product Manager", 
                               "avatar", "https://i.pravatar.cc/150?img=6", "activeTasks", 5, "workload", "Overloaded"),
                        Map.of("id", "7", "name", "Yotam Barak", "role", "iOS Lead",
                               "avatar", "https://i.pravatar.cc/150?img=7", "activeTasks", 4, "workload", "Overloaded"),
                        Map.of("id", "8", "name", "Maya Segal", "role", "Android Lead",
                               "avatar", "https://i.pravatar.cc/150?img=8", "activeTasks", 3, "workload", "At Capacity"),
                        Map.of("id", "9", "name", "Eli Nahum", "role", "Backend Engineer",
                               "avatar", "https://i.pravatar.cc/150?img=9", "activeTasks", 2, "workload", "Available")
                ))
                .recentActivities(List.of(
                        Map.of("id", "1", "action", "escalated", "description", "Critical blocker - API design mismatch",
                               "actor", Map.of("name", "Shira Adler", "avatar", "https://i.pravatar.cc/150?img=6"),
                               "timestamp", "30m ago", "type", "error"),
                        Map.of("id", "2", "action", "blocked", "description", "Android build failing - awaiting fix",
                               "actor", Map.of("name", "Yotam Barak", "avatar", "https://i.pravatar.cc/150?img=7"),
                               "timestamp", "1h ago", "type", "error"),
                        Map.of("id", "3", "action", "commented", "description", "Needs design review before coding",
                               "actor", Map.of("name", "Eli Nahum", "avatar", "https://i.pravatar.cc/150?img=9"),
                               "timestamp", "3h ago")
                ))
                .build();
    }
}
