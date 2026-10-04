package org.genapp.policy;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;

/** Conversions from Db2 column values to the COMMAREA string/number representation. */
public final class Db2Format {

    /** Db2 TIMESTAMP as returned into a {@code PIC X(26)} host variable. */
    static final DateTimeFormatter DB2_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

    private Db2Format() {
    }

    /** {@code PIC 9(10)} key fields (customer and policy numbers), zero-padded like the COMMAREA. */
    public static String key(long number) {
        return String.format("%010d", number);
    }

    /** Db2 DATE into {@code PIC X(10)}: ISO {@code yyyy-MM-dd}; NULL gives "". */
    public static String date(Date date) {
        return date == null ? "" : date.toLocalDate().toString();
    }

    /** Db2 TIMESTAMP into {@code PIC X(26)}; NULL gives "". */
    public static String timestamp(Timestamp timestamp) {
        return timestamp == null ? "" : DB2_TIMESTAMP.format(timestamp.toLocalDateTime());
    }

    /** CHAR column with trailing padding removed (leading spaces kept); NULL gives "". */
    public static String text(String value) {
        return value == null ? "" : value.stripTrailing();
    }

    /** SMALLINT/INTEGER column moved to an unsigned {@code PIC 9(n)} field; NULL gives 0. */
    public static long number(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? 0 : value;
    }
}
