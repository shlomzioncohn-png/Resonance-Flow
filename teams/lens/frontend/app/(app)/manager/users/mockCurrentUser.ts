/**
 * TODO: this screen doesn't know who's logged in — Auth stage 3 (real
 * Keycloak session in the Frontend) hasn't landed, so there is no real
 * "current manager" to read. Gateway's ManagerUsersController forwards
 * every request under the same mock actor id (app.mock-actor-id,
 * default below) regardless of who's actually signed in — see that
 * class's javadoc. This constant exists only so the one row that *is*
 * that mock actor can have its status-change action disabled, matching
 * the backend's own "can't change your own status" rule. Replace with
 * the real signed-in user's id once a session exists.
 */
export const MOCK_CURRENT_USER_ID = '00000000-0000-0000-0000-000000000001';
