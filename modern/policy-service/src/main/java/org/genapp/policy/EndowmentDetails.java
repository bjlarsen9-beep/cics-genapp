package org.genapp.policy;

/**
 * Endowment policy section of the COMMAREA ({@code CA-ENDOWMENT REDEFINES CA-POLICY-SPECIFIC} in
 * {@code base/src/lgcmarea.cpy}), as filled by LGIPDB01 {@code GET-ENDOW-DB2-INFO} from table ENDOWMENT.
 *
 * <p>Text fields carry the CHAR value with trailing padding removed. Numeric fields carry the value after the COBOL
 * {@code MOVE} from the binary host variable to the unsigned display field (sign dropped, high-order digits
 * truncated to the PIC size).
 *
 * @param withProfits {@code CA-E-WITH-PROFITS PIC X} ({@code ENDOWMENT.WITHPROFITS CHAR(1)})
 * @param equities    {@code CA-E-EQUITIES PIC X} ({@code ENDOWMENT.EQUITIES CHAR(1)})
 * @param managedFund {@code CA-E-MANAGED-FUND PIC X} ({@code ENDOWMENT.MANAGEDFUND CHAR(1)})
 * @param fundName    {@code CA-E-FUND-NAME PIC X(10)} ({@code ENDOWMENT.FUNDNAME CHAR(10)})
 * @param term        {@code CA-E-TERM PIC 99} ({@code ENDOWMENT.TERM SMALLINT})
 * @param sumAssured  {@code CA-E-SUM-ASSURED PIC 9(6)} ({@code ENDOWMENT.SUMASSURED INTEGER})
 * @param lifeAssured {@code CA-E-LIFE-ASSURED PIC X(31)} ({@code ENDOWMENT.LIFEASSURED CHAR(31)})
 * @param paddingData {@code CA-E-PADDING-DATA PIC X(32348)} ({@code ENDOWMENT.PADDINGDATA VARCHAR(32606)}),
 *                    "" when the column is NULL; the COMMAREA {@code 'FINAL'} end marker is not included
 */
public record EndowmentDetails(
        String withProfits,
        String equities,
        String managedFund,
        String fundName,
        int term,
        long sumAssured,
        String lifeAssured,
        String paddingData) implements PolicyDetails {
}
