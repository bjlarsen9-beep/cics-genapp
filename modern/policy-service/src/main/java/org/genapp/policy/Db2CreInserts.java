package org.genapp.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sample-data {@code Insert Into <DB2DBID>.<table> (columns) Values (values);} statements of
 * {@code base/cntl/db2cre.jcl}, parsed into rows. Table and column names are lower-cased; values are the SQL
 * literals with string quotes removed ({@code null} for a SQL NULL).
 */
public final class Db2CreInserts {

    private static final Pattern INSERT = Pattern.compile("Insert\\s+Into\\s+<DB2DBID>\\.(\\w+)\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUES = Pattern.compile("\\G\\s*Values\\s*\\(", Pattern.CASE_INSENSITIVE);

    private final Map<String, List<Map<String, String>>> rowsByTable;

    private Db2CreInserts(Map<String, List<Map<String, String>>> rowsByTable) {
        this.rowsByTable = rowsByTable;
    }

    public static Db2CreInserts parse(String jcl) {
        Map<String, List<Map<String, String>>> rows = new HashMap<>();
        Matcher m = INSERT.matcher(jcl);
        int pos = 0;
        while (m.find(pos)) {
            String table = m.group(1).toLowerCase(Locale.ROOT);
            Scan columns = list(jcl, m.end());
            Matcher v = VALUES.matcher(jcl);
            if (!v.find(columns.end())) {
                throw new IllegalArgumentException("No Values clause for insert into " + table);
            }
            Scan values = list(jcl, v.end());
            if (columns.items().size() != values.items().size()) {
                throw new IllegalArgumentException("Column/value count mismatch in insert into " + table);
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.items().size(); i++) {
                row.put(columns.items().get(i).toLowerCase(Locale.ROOT), values.items().get(i));
            }
            rows.computeIfAbsent(table, t -> new ArrayList<>()).add(Collections.unmodifiableMap(row));
            pos = values.end();
        }
        return new Db2CreInserts(rows);
    }

    /** All rows inserted into {@code table} (case-insensitive), in file order. */
    public List<Map<String, String>> rows(String table) {
        return rowsByTable.getOrDefault(table.toLowerCase(Locale.ROOT), List.of());
    }

    /** The row of {@code table} whose {@code policynumber} column equals {@code policyNumber}. */
    public Optional<Map<String, String>> findByPolicyNumber(String table, long policyNumber) {
        String key = Long.toString(policyNumber);
        return rows(table).stream().filter(r -> key.equals(r.get("policynumber"))).findFirst();
    }

    private record Scan(List<String> items, int end) {
    }

    /** Reads a comma-separated list up to the closing parenthesis; {@code start} is just after the "(". */
    private static Scan list(String s, int start) {
        List<String> items = new ArrayList<>();
        StringBuilder item = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    item.append('\'');
                    i++;
                } else if (c == '\'') {
                    quoted = false;
                } else {
                    item.append(c);
                }
            } else if (c == '\'') {
                quoted = true;
                wasQuoted = true;
            } else if (c == ',' || c == ')') {
                String token = wasQuoted ? item.toString() : item.toString().trim();
                items.add(!wasQuoted && token.equalsIgnoreCase("NULL") ? null : token);
                item.setLength(0);
                wasQuoted = false;
                if (c == ')') {
                    return new Scan(items, i + 1);
                }
            } else if (!Character.isWhitespace(c)) {
                item.append(c);
            }
        }
        throw new IllegalArgumentException("Unterminated list in db2cre.jcl at offset " + start);
    }
}
