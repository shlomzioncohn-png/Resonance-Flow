package com.rnd.inbox.shared.logging;

import net.ttddyy.dsproxy.proxy.ParameterSetOperation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds a runnable SQL statement by replacing each {@code ?} placeholder with the value that
 * was bound to it. Meant for copying a logged query into a SQL console, not for execution by
 * the application.
 */
final class SqlParameterInliner {

    private SqlParameterInliner() {
    }

    static String inline(String sql, List<ParameterSetOperation> parameters) {
        Map<Integer, String> literals = new HashMap<>();
        for (ParameterSetOperation parameter : parameters) {
            Object[] args = parameter.getArgs();
            if (args[0] instanceof Integer index) {
                boolean isNull = ParameterSetOperation.isSetNullParameterOperation(parameter);
                literals.put(index, isNull ? "NULL" : toLiteral(args[1]));
            }
        }

        StringBuilder result = new StringBuilder(sql.length() + 64);
        boolean insideString = false;
        int nextIndex = 1;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                insideString = !insideString;
            }
            if (c != '?' || insideString) {
                result.append(c);
                continue;
            }
            // "??" is JDBC's escape for a literal question mark (Postgres JSON operators).
            if (i + 1 < sql.length() && sql.charAt(i + 1) == '?') {
                result.append('?');
                i++;
                continue;
            }
            String literal = literals.get(nextIndex++);
            result.append(literal != null ? literal : "?");
        }
        return result.toString();
    }

    private static String toLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return quote(value.toString());
    }

    /**
     * A value holding a backslash or a control character is written as a Postgres escape string
     * ({@code E'...'}): the value then always stays on one log line, so it cannot pose as a
     * separate log entry, and it reads the same under any standard_conforming_strings setting.
     */
    private static String quote(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 8);
        boolean needsEscapeSyntax = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                escaped.append("''");
            } else if (c == '\\') {
                escaped.append("\\\\");
                needsEscapeSyntax = true;
            } else if (c == '\n') {
                escaped.append("\\n");
                needsEscapeSyntax = true;
            } else if (c == '\r') {
                escaped.append("\\r");
                needsEscapeSyntax = true;
            } else if (c == '\t') {
                escaped.append("\\t");
                needsEscapeSyntax = true;
            } else if (Character.isISOControl(c) || c == ' ' || c == ' ') {
                escaped.append(String.format("\\u%04x", (int) c));
                needsEscapeSyntax = true;
            } else {
                escaped.append(c);
            }
        }
        return (needsEscapeSyntax ? "E'" : "'") + escaped + "'";
    }
}
