package org.genapp.policy;

/**
 * Motor policy section of the COMMAREA ({@code CA-MOTOR REDEFINES CA-POLICY-SPECIFIC} in
 * {@code base/src/lgcmarea.cpy}), as filled by LGIPDB01 {@code GET-MOTOR-DB2-INFO} from the {@code MOTOR} table.
 *
 * <p>Text fields carry the COBOL value with trailing CHAR padding removed. Numeric fields hold the value after the
 * COBOL {@code MOVE} from the signed binary host variable into the unsigned display field (sign dropped, high-order
 * digits truncated to the PIC length).
 *
 * @param make         {@code CA-M-MAKE PIC X(15)} ({@code MOTOR.MAKE CHAR(15)})
 * @param model        {@code CA-M-MODEL PIC X(15)} ({@code MOTOR.MODEL CHAR(15)})
 * @param value        {@code CA-M-VALUE PIC 9(6)} ({@code MOTOR.VALUE INTEGER})
 * @param regNumber    {@code CA-M-REGNUMBER PIC X(7)} ({@code MOTOR.REGNUMBER CHAR(7)})
 * @param colour       {@code CA-M-COLOUR PIC X(8)} ({@code MOTOR.COLOUR CHAR(8)})
 * @param cc           {@code CA-M-CC PIC 9(4)} ({@code MOTOR.CC SMALLINT})
 * @param manufactured {@code CA-M-MANUFACTURED PIC X(10)} ({@code MOTOR.YEAROFMANUFACTURE DATE}), ISO {@code yyyy-MM-dd}
 * @param premium      {@code CA-M-PREMIUM PIC 9(6)} ({@code MOTOR.PREMIUM INTEGER})
 * @param accidents    {@code CA-M-ACCIDENTS PIC 9(6)} ({@code MOTOR.ACCIDENTS INTEGER})
 */
public record MotorDetails(
        String make,
        String model,
        long value,
        String regNumber,
        String colour,
        int cc,
        String manufactured,
        long premium,
        long accidents) implements PolicyDetails {
}
