package com.rnd.bff.service;

import java.util.Set;

/**
 * Maps between Inbox's `users.role` (com.rnd.inbox.user.UserRole:
 * SYSTEM_ADMIN, TENANT_ADMIN, TEAM_LEAD, EMPLOYEE) and LENS's two roles
 * (MANAGER/EMPLOYEE) — task spec §3: "role בתשובות הוא תפקיד LENS... לפי
 * המיפוי שכבר מוגדר ב-BFF במשימת ה-auth". That auth-task mapping doesn't
 * exist in this repo yet, so this is a best-effort stand-in:
 *
 * Inbox -> LENS: SYSTEM_ADMIN and TENANT_ADMIN are LENS MANAGER; TEAM_LEAD,
 * EMPLOYEE and anything unrecognized are LENS EMPLOYEE.
 *
 * LENS -> Inbox (creating a user): MANAGER becomes TENANT_ADMIN — the Users
 * screen is a manager's screen, and Inbox only lets SYSTEM_ADMIN/TENANT_ADMIN
 * create users, so a LENS manager has to be at least TENANT_ADMIN for the
 * screen to work. EMPLOYEE stays EMPLOYEE. Round-trips through toLensRole.
 */
public final class LensRoleMapper {

    private static final Set<String> MANAGER_INBOX_ROLES = Set.of("SYSTEM_ADMIN", "TENANT_ADMIN");

    private LensRoleMapper() {
    }

    public static String toLensRole(String inboxRole) {
        if (inboxRole == null) {
            return "EMPLOYEE";
        }
        return MANAGER_INBOX_ROLES.contains(inboxRole.toUpperCase()) ? "MANAGER" : "EMPLOYEE";
    }

    /** @return the Inbox role for a LENS role, or null if lensRole isn't MANAGER or EMPLOYEE. */
    public static String toInboxRole(String lensRole) {
        if ("MANAGER".equals(lensRole)) {
            return "TENANT_ADMIN";
        }
        if ("EMPLOYEE".equals(lensRole)) {
            return "EMPLOYEE";
        }
        return null;
    }
}
