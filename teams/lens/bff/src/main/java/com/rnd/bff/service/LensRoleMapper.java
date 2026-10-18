package com.rnd.bff.service;

import java.util.Set;

/**
 * Maps Inbox's `users.role` to one of LENS's two roles (MANAGER/EMPLOYEE) —
 * task spec §3: "role בתשובות הוא תפקיד LENS... לפי המיפוי שכבר מוגדר ב-BFF
 * במשימת ה-auth". That mapping doesn't exist in this repo (the auth task
 * this one depends on was never built — see ManagerUsersController's
 * javadoc), so this is a best-effort stand-in, not the real mapping:
 *
 * In the current code, com.rnd.inbox.user.UserCreateRequestDto declares
 * `role` as a plain, unvalidated String (`@NotBlank @Size(max=50)`), not a
 * fixed enum — there is no authoritative list of Inbox's "4 roles" to read
 * from code. SYSTEM_ADMIN and MANAGER are treated as LENS MANAGER; every
 * other value (TEAM_LEAD, EMPLOYEE, anything unrecognized) is treated as
 * LENS EMPLOYEE. Confirm this against Inbox's actual role vocabulary
 * (Inbox_Data_Schema_Specification §3.2, per the task doc) before relying
 * on it for anything more than this screen's display column.
 */
public final class LensRoleMapper {

    private static final Set<String> MANAGER_INBOX_ROLES = Set.of("SYSTEM_ADMIN", "MANAGER");

    private LensRoleMapper() {
    }

    public static String toLensRole(String inboxRole) {
        if (inboxRole == null) {
            return "EMPLOYEE";
        }
        return MANAGER_INBOX_ROLES.contains(inboxRole.toUpperCase()) ? "MANAGER" : "EMPLOYEE";
    }
}
