package com.rnd.bff.client;

import com.rnd.bff.dto.ManagerProjectDTO;
import com.rnd.bff.dto.ProjectMemberDTO;
import com.rnd.bff.dto.WorkloadEntryDTO;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Manager Dashboard's team-workload breakdown and all-projects grid. No
 * Inbox/Chain-backed workload or project-aggregate source exists yet (see
 * frontend's managerApi.ts/projectsApi.ts), so this returns the same fixed
 * mock the frontend previously mocked itself via MSW, moved here so the
 * frontend can call a real endpoint instead.
 *
 * Distinct from ProjectDetailsService (com.rnd.bff.service): that backs the
 * separate per-project drill-down page (/admin/projects/{id}) with only 3
 * seeded projects; this backs the dashboard's project grid with the fuller
 * 12-project seed set the design was reviewed against.
 *
 * TODO: replace with real calls once a workload/project-aggregate source exists.
 */
@Service
public class ManagerClient {

    public List<WorkloadEntryDTO> getWorkload() {
        return List.of();
    }

    public List<ManagerProjectDTO> getProjects() {
        Instant now = Instant.now();
        return List.of(
                project("p1", "Checkout Revamp", "Payments", "Dana Cohen", "healthy", "Healthy",
                        List.of(member("Dana Cohen"), member("Noa Levi"), member("Omer Katz")),
                        72, 18, 6, 0, now.minus(2, ChronoUnit.HOURS)),
                project("p2", "Fraud Detection API", "Risk", "Roni Peretz", "warning", "At Risk",
                        List.of(member("Roni Peretz"), member("Itay Ben David")),
                        41, 22, 9, 2, now.minus(5, ChronoUnit.HOURS)),
                project("p3", "Mobile Onboarding", "Growth", "Shira Adler", "critical", "Critical",
                        List.of(member("Shira Adler"), member("Yotam Barak"), member("Maya Segal"), member("Eli Nahum")),
                        18, 15, 3, 5, now.minus(1, ChronoUnit.HOURS)),
                project("p4", "Search Relevance v2", "Platform", "Guy Refael", "healthy", "Healthy",
                        List.of(member("Guy Refael"), member("Tal Mizrahi")),
                        88, 12, 2, 0, now.minus(26, ChronoUnit.HOURS)),
                project("p5", "Billing Reconciliation", "Payments", "Dana Cohen", "warning", "At Risk",
                        List.of(member("Dana Cohen"), member("Roni Peretz"), member("Omer Katz")),
                        55, 20, 8, 1, now.minus(3, ChronoUnit.HOURS)),
                project("p6", "Design System Rollout", "Platform", "Tal Mizrahi", "healthy", "Healthy",
                        List.of(member("Tal Mizrahi"), member("Shira Adler")),
                        64, 10, 3, 0, now.minus(8, ChronoUnit.HOURS)),
                project("p7", "CRM Sync Pipeline", "Integrations", "Eli Nahum", "critical", "Critical",
                        List.of(member("Eli Nahum"), member("Maya Segal")),
                        9, 14, 1, 6, now.minus(30, ChronoUnit.MINUTES)),
                project("p8", "Notification Center", "Growth", "Yotam Barak", "healthy", "Healthy",
                        List.of(member("Yotam Barak"), member("Itay Ben David"), member("Noa Levi")),
                        79, 16, 4, 0, now.minus(30, ChronoUnit.HOURS)),
                project("p9", "Vendor Risk Scoring", "Risk", "Roni Peretz", "warning", "At Risk",
                        List.of(member("Roni Peretz")),
                        47, 11, 5, 2, now.minus(12, ChronoUnit.HOURS)),
                project("p10", "Internal Analytics Hub", "Platform", "Guy Refael", "healthy", "Healthy",
                        List.of(member("Guy Refael"), member("Dana Cohen")),
                        93, 9, 1, 0, now.minus(48, ChronoUnit.HOURS)),
                project("p11", "GitHub App Migration", "Integrations", "Maya Segal", "critical", "Critical",
                        List.of(member("Maya Segal"), member("Eli Nahum"), member("Omer Katz")),
                        22, 13, 2, 4, now.minus(4, ChronoUnit.HOURS)),
                project("p12", "Legacy Contract Import", "Integrations", "Unassigned", "neutral", "No data",
                        List.of(),
                        0, 0, 0, 0, now.minus(240, ChronoUnit.HOURS)));
    }

    private static ProjectMemberDTO member(String name) {
        return ProjectMemberDTO.builder().name(name).build();
    }

    private static ManagerProjectDTO project(
            String id, String name, String team, String owner, String status, String statusLabel,
            List<ProjectMemberDTO> members, int progressPercent, int totalCount, int inProgressCount,
            int blockedCount, Instant lastUpdatedAt) {
        return ManagerProjectDTO.builder()
                .id(id)
                .name(name)
                .team(team)
                .owner(owner)
                .status(status)
                .statusLabel(statusLabel)
                .members(members)
                .progressPercent(progressPercent)
                .totalCount(totalCount)
                .inProgressCount(inProgressCount)
                .blockedCount(blockedCount)
                .lastUpdatedAt(lastUpdatedAt.toString())
                .build();
    }
}
