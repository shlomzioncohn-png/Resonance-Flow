package com.rnd.bff.service;

import org.springframework.stereotype.Service;

/**
 * Maps Inbox roles to LENS roles.
 * 
 * Inbox role hierarchy:
 * - SYSTEM_ADMIN: Full system access (→ TENANT_ADMIN)
 * - TENANT_ADMIN: Tenant-level admin
 * - TEAM_LEAD: Team-level lead
 * - EMPLOYEE: Regular employee
 * 
 * LENS roles (passed back to Frontend):
 * - EMPLOYEE: Regular employee
 * - TENANT_ADMIN: Tenant admin
 * - TEAM_LEAD: Team lead
 */
@Service
public class RoleMapper {

    /**
     * Map Inbox role to LENS role.
     * 
     * @param inboxRole role from Inbox (SYSTEM_ADMIN, TENANT_ADMIN, TEAM_LEAD, EMPLOYEE)
     * @return LENS role (EMPLOYEE, TENANT_ADMIN, or TEAM_LEAD)
     */
    public String mapToLensRole(String inboxRole) {
        if (inboxRole == null) {
            return "EMPLOYEE";
        }

        return switch (inboxRole) {
            case "SYSTEM_ADMIN" -> "TENANT_ADMIN";
            case "TENANT_ADMIN" -> "TENANT_ADMIN";
            case "TEAM_LEAD" -> "TEAM_LEAD";
            case "EMPLOYEE" -> "EMPLOYEE";
            default -> "EMPLOYEE";
        };
    }
}
