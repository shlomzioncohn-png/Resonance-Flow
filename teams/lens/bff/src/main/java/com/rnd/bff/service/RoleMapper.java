package com.rnd.bff.service;

import org.springframework.stereotype.Service;

/**
 * Maps Inbox roles to LENS roles.
 * 
 * Inbox role hierarchy:
 * - SYSTEM_ADMIN: Full system access
 * - TENANT_ADMIN: Tenant-level admin
 * - TEAM_LEAD: Team-level lead (mapped to EMPLOYEE for now, awaiting future decision)
 * - EMPLOYEE: Regular employee
 * 
 * LENS role hierarchy:
 * - MANAGER: Can see and manage other employees' tasks
 * - EMPLOYEE: Can see and manage only their own tasks
 */
@Service
public class RoleMapper {

    /**
     * Map Inbox role to LENS role.
     * 
     * @param inboxRole role from Inbox (SYSTEM_ADMIN, TENANT_ADMIN, TEAM_LEAD, EMPLOYEE)
     * @return LENS role (MANAGER or EMPLOYEE)
     */
    public String mapToLensRole(String inboxRole) {
        if (inboxRole == null) {
            return "EMPLOYEE";
        }

        return switch (inboxRole) {
            case "SYSTEM_ADMIN" -> "MANAGER";
            case "TENANT_ADMIN" -> "EMPLOYEE";  // Awaiting decision on this mapping
            case "TEAM_LEAD" -> "EMPLOYEE";     // Awaiting decision on this mapping
            case "EMPLOYEE" -> "EMPLOYEE";
            default -> "EMPLOYEE";
        };
    }
}
