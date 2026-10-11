package com.rnd.inbox.shared.logging;

import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SqlParameterInlinerTest {

    // ---- value formatting ----

    @Test
    void inline_stringValue_isWrappedInSingleQuotes() throws Exception {
        String result = SqlParameterInliner.inline("select * from tasks where status = ?",
                List.of(setString(1, "OPEN")));

        assertThat(result).isEqualTo("select * from tasks where status = 'OPEN'");
    }

    @Test
    void inline_emptyString_becomesEmptyLiteral() throws Exception {
        String result = SqlParameterInliner.inline("select ?", List.of(setString(1, "")));

        assertThat(result).isEqualTo("select ''");
    }

    @Test
    void inline_integerLongAndDecimalValues_areWrittenWithoutQuotes() throws Exception {
        String result = SqlParameterInliner.inline("select ?, ?, ?", List.of(
                setObject(1, 3),
                set("setLong", long.class, 2, 9_000_000_000L),
                setObject(3, new BigDecimal("12.50"))));

        assertThat(result).isEqualTo("select 3, 9000000000, 12.50");
    }

    @Test
    void inline_negativeNumber_keepsItsSign() throws Exception {
        String result = SqlParameterInliner.inline("select ?", List.of(setObject(1, -7)));

        assertThat(result).isEqualTo("select -7");
    }

    @Test
    void inline_booleanValues_areWrittenWithoutQuotes() throws Exception {
        String result = SqlParameterInliner.inline("select ?, ?", List.of(
                set("setBoolean", boolean.class, 1, true),
                setObject(2, false)));

        assertThat(result).isEqualTo("select true, false");
    }

    @Test
    void inline_setNullOperation_becomesNullKeyword() throws Exception {
        // For setNull the second argument is the SQL type code, not a value - it must not leak
        // into the output as a number.
        String result = SqlParameterInliner.inline("update tasks set owner_id = ?",
                List.of(set("setNull", int.class, 1, Types.VARCHAR)));

        assertThat(result).isEqualTo("update tasks set owner_id = NULL");
    }

    @Test
    void inline_nullPassedAsValue_becomesNullKeyword() throws Exception {
        String result = SqlParameterInliner.inline("update tasks set owner_id = ?",
                List.of(setObject(1, null)));

        assertThat(result).isEqualTo("update tasks set owner_id = NULL");
    }

    @Test
    void inline_uuidValue_isQuoted() throws Exception {
        UUID id = UUID.fromString("a3f1c2d4-0000-4000-8000-000000000001");

        String result = SqlParameterInliner.inline("select * from tasks where id = ?",
                List.of(setObject(1, id)));

        assertThat(result)
                .isEqualTo("select * from tasks where id = 'a3f1c2d4-0000-4000-8000-000000000001'");
    }

    @Test
    void inline_dateAndTimeValues_areQuotedUsingTheirTextForm() throws Exception {
        OffsetDateTime offsetDateTime = OffsetDateTime.parse("2026-10-04T10:15:30+03:00");
        Timestamp timestamp = Timestamp.valueOf("2026-10-04 10:15:30");
        LocalDate localDate = LocalDate.of(2026, 10, 4);

        String result = SqlParameterInliner.inline("select ?, ?, ?", List.of(
                setObject(1, offsetDateTime),
                set("setTimestamp", Timestamp.class, 2, timestamp),
                setObject(3, localDate)));

        assertThat(result).isEqualTo(
                "select '2026-10-04T10:15:30+03:00', '2026-10-04 10:15:30.0', '2026-10-04'");
    }

    @Test
    void inline_enumValue_isQuotedUsingItsName() throws Exception {
        String result = SqlParameterInliner.inline("select ?", List.of(setObject(1, Sample.HIGH)));

        assertThat(result).isEqualTo("select 'HIGH'");
    }

    // ---- quoting / injection-shaped values ----

    @Test
    void inline_valueContainingSingleQuote_doublesTheQuote() throws Exception {
        String result = SqlParameterInliner.inline("select * from users where name = ?",
                List.of(setString(1, "O'Brien")));

        assertThat(result).isEqualTo("select * from users where name = 'O''Brien'");
    }

    @Test
    void inline_valueShapedLikeSqlInjection_staysInsideOneLiteral() throws Exception {
        String result = SqlParameterInliner.inline("select * from tasks where title = ?",
                List.of(setString(1, "x'; DROP TABLE tasks; --")));

        assertThat(result).isEqualTo("select * from tasks where title = 'x''; DROP TABLE tasks; --'");
    }

    @Test
    void inline_valueMadeOnlyOfQuotes_doublesEveryOne() throws Exception {
        String result = SqlParameterInliner.inline("select ?", List.of(setString(1, "'''")));

        assertThat(result).isEqualTo("select ''''''''");
    }

    @Test
    void inline_valueContainingQuestionMark_isNotTreatedAsAnotherPlaceholder() throws Exception {
        String result = SqlParameterInliner.inline("select ?, ?", List.of(
                setString(1, "why?"),
                setString(2, "second")));

        assertThat(result).isEqualTo("select 'why?', 'second'");
    }

    // ---- escape-string form: values that could break out of one log line ----

    @Test
    void inline_valueContainingNewline_isWrittenOnOneLineAsEscapeString() throws Exception {
        String result = SqlParameterInliner.inline("select ?", List.of(setString(1, "first\nsecond")));

        assertThat(result).isEqualTo("select E'first\\nsecond'").doesNotContain("\n");
    }

    @Test
    void inline_valueContainingCarriageReturnAndTab_escapesBoth() throws Exception {
        String result = SqlParameterInliner.inline("select ?", List.of(setString(1, "a\r\n\tb")));

        assertThat(result).isEqualTo("select E'a\\r\\n\\tb'");
    }

    @Test
    void inline_valueContainingBackslash_doublesItInEscapeString() throws Exception {
        String result = SqlParameterInliner.inline("select ?", List.of(setString(1, "C:\\temp")));

        assertThat(result).isEqualTo("select E'C:\\\\temp'");
    }

    @Test
    void inline_backslashBeforeQuote_cannotCloseTheLiteral() throws Exception {
        // With backslash escapes enabled on the server, \' would end the literal early.
        String result = SqlParameterInliner.inline("select ?", List.of(setString(1, "x\\'; DROP TABLE tasks; --")));

        assertThat(result).isEqualTo("select E'x\\\\''; DROP TABLE tasks; --'");
    }

    @Test
    void inline_otherControlCharacter_isWrittenAsUnicodeEscape() throws Exception {
        String result = SqlParameterInliner.inline("select ?", List.of(setString(1, "a\u0001b\u007fc")));

        assertThat(result).isEqualTo("select E'a\\u0001b\\u007fc'");
    }

    @Test
    void inline_unicodeLineSeparators_areEscapedToo() throws Exception {
        String result = SqlParameterInliner.inline("select ?", List.of(setString(1, "a\u2028b\u2029c\u0085d")));

        assertThat(result).isEqualTo("select E'a\\u2028b\\u2029c\\u0085d'");
    }

    @Test
    void inline_forgedLogLineInsideValue_staysOnTheSameLine() throws Exception {
        String forged = "x\n2026-10-04T10:00:00.000+03:00 DEBUG [main] 1 ms | OK | drop table tasks";

        String result = SqlParameterInliner.inline("select * from tasks where title = ?",
                List.of(setString(1, forged)));

        assertThat(result.lines()).hasSize(1);
        assertThat(result).startsWith("select * from tasks where title = E'x\\n2026-10-04");
    }

    @Test
    void inline_ordinaryValue_doesNotUseEscapeStringForm() throws Exception {
        String result = SqlParameterInliner.inline("select ?", List.of(setString(1, "plain text, 100%")));

        assertThat(result).isEqualTo("select 'plain text, 100%'");
    }

    @Test
    void inline_nonAsciiText_isKeptAsIs() throws Exception {
        String result = SqlParameterInliner.inline("select ?", List.of(setString(1, "משימה חדשה")));

        assertThat(result).isEqualTo("select 'משימה חדשה'");
    }

    // ---- placeholder scanning ----

    @Test
    void inline_severalPlaceholders_areFilledByIndexEvenWhenSetOutOfOrder() throws Exception {
        String result = SqlParameterInliner.inline("select * from tasks where a = ? and b = ? and c = ?",
                List.of(setString(3, "third"), setString(1, "first"), setString(2, "second")));

        assertThat(result)
                .isEqualTo("select * from tasks where a = 'first' and b = 'second' and c = 'third'");
    }

    @Test
    void inline_sameIndexSetTwice_usesTheLastValue() throws Exception {
        String result = SqlParameterInliner.inline("select ?",
                List.of(setString(1, "old"), setString(1, "new")));

        assertThat(result).isEqualTo("select 'new'");
    }

    @Test
    void inline_noPlaceholdersAndNoParameters_returnsSqlUnchanged() {
        String result = SqlParameterInliner.inline("select 1", List.of());

        assertThat(result).isEqualTo("select 1");
    }

    @Test
    void inline_emptySql_returnsEmptyString() {
        assertThat(SqlParameterInliner.inline("", List.of())).isEmpty();
    }

    @Test
    void inline_placeholderWithoutBoundValue_isLeftAsQuestionMark() throws Exception {
        String result = SqlParameterInliner.inline("select ?, ?", List.of(setString(1, "only")));

        assertThat(result).isEqualTo("select 'only', ?");
    }

    @Test
    void inline_moreValuesThanPlaceholders_ignoresTheExtraValues() throws Exception {
        String result = SqlParameterInliner.inline("select ?",
                List.of(setString(1, "used"), setString(2, "unused")));

        assertThat(result).isEqualTo("select 'used'");
    }

    @Test
    void inline_questionMarkInsideSqlStringLiteral_isNotReplaced() throws Exception {
        String result = SqlParameterInliner.inline("select 'really?' as q, ? as v",
                List.of(setString(1, "value")));

        assertThat(result).isEqualTo("select 'really?' as q, 'value' as v");
    }

    @Test
    void inline_sqlLiteralWithEscapedQuote_doesNotConfuseThePlaceholderScan() throws Exception {
        String result = SqlParameterInliner.inline("select 'it''s ?' as q, ? as v",
                List.of(setString(1, "value")));

        assertThat(result).isEqualTo("select 'it''s ?' as q, 'value' as v");
    }

    @Test
    void inline_doubledQuestionMark_becomesOneLiteralQuestionMarkAndConsumesNoValue() throws Exception {
        String result = SqlParameterInliner.inline("select data ?? 'key' from t where id = ?",
                List.of(setObject(1, 5)));

        assertThat(result).isEqualTo("select data ? 'key' from t where id = 5");
    }

    @Test
    void inline_placeholderAtStartAndEndOfSql_isReplaced() throws Exception {
        String result = SqlParameterInliner.inline("? = ?", List.of(setObject(1, 1), setObject(2, 2)));

        assertThat(result).isEqualTo("1 = 2");
    }

    @Test
    void inline_namedParameter_isIgnored() throws Exception {
        Method setByName = CallableStatement.class.getMethod("setString", String.class, String.class);
        ParameterSetOperation named = new ParameterSetOperation(setByName, new Object[]{"status", "OPEN"});

        String result = SqlParameterInliner.inline("call close_tasks(?)", List.of(named));

        assertThat(result).isEqualTo("call close_tasks(?)");
    }

    // ---- helpers ----

    private enum Sample { HIGH }

    private static ParameterSetOperation setString(int index, String value) throws NoSuchMethodException {
        return set("setString", String.class, index, value);
    }

    private static ParameterSetOperation setObject(int index, Object value) throws NoSuchMethodException {
        return set("setObject", Object.class, index, value);
    }

    private static ParameterSetOperation set(String methodName, Class<?> valueType, int index, Object value)
            throws NoSuchMethodException {
        Method method = PreparedStatement.class.getMethod(methodName, int.class, valueType);
        return new ParameterSetOperation(method, new Object[]{index, value});
    }
}
