package org.genapp.policy;

/**
 * One fixed-width record of {@code base/data/ksdspoly.txt} (LRECL 64), as written by LGAPVS01
 * ({@code WRITE FILE('KSDSPOLY') FROM(WF-Policy-Info) LENGTH(64)}).
 *
 * <p>Layout: {@code WF-Request-ID X(1)} (policy type), {@code WF-Customer-Num X(10)}, {@code WF-Policy-Num X(10)},
 * then {@code WF-Policy-Data X(43)}, which LGAPVS01 REDEFINES per type ({@code WF-E-/WF-H-/WF-M-/WF-C-Policy-Data}).
 * The 43-byte data part is only a summary of the policy; the remaining Db2 columns come from {@code db2cre.jcl}.
 *
 * @param policyType     {@code WF-Request-ID}: {@code E}, {@code H}, {@code M} or {@code C}
 * @param customerNumber {@code WF-Customer-Num}
 * @param policyNumber   {@code WF-Policy-Num}
 * @param data           {@code WF-Policy-Data}, always 43 characters (space-padded)
 */
public record KsdsPolyRecord(String policyType, long customerNumber, long policyNumber, String data) {

    public static final int LRECL = 64;
    public static final int DATA_OFFSET = 21;
    public static final int DATA_LENGTH = 43;

    public static KsdsPolyRecord parse(String line) {
        String r = String.format("%-" + LRECL + "s", line);
        return new KsdsPolyRecord(
                r.substring(0, 1),
                Long.parseLong(r.substring(1, 11)),
                Long.parseLong(r.substring(11, 21)),
                r.substring(DATA_OFFSET, LRECL));
    }

    /** Raw characters of a type-specific field; {@code offset} is relative to the start of {@code WF-Policy-Data}. */
    public String raw(int offset, int length) {
        return data.substring(offset, offset + length);
    }

    /** A {@code PIC X} field of {@code WF-Policy-Data} with trailing spaces removed. */
    public String text(int offset, int length) {
        return raw(offset, length).stripTrailing();
    }
}
