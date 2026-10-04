package org.genapp.policy;

/**
 * House policy section of the COMMAREA ({@code CA-HOUSE REDEFINES CA-POLICY-SPECIFIC} in
 * {@code base/src/lgcmarea.cpy}), as filled by LGIPDB01 {@code GET-HOUSE-DB2-INFO} from {@code DB2-HOUSE}
 * ({@code base/src/lgpolicy.cpy}).
 *
 * <p>Text fields carry the CHAR value with trailing padding removed (leading spaces kept, for example
 * {@code houseNumber} {@code "   5"}).
 *
 * @param propertyType {@code CA-H-PROPERTY-TYPE PIC X(15)} from {@code HOUSE.PROPERTYTYPE CHAR(15)}
 * @param bedrooms     {@code CA-H-BEDROOMS PIC 9(3)} from {@code HOUSE.BEDROOMS SMALLINT} (via
 *                     {@code DB2-H-BEDROOMS-SINT PIC S9(4) COMP})
 * @param value        {@code CA-H-VALUE PIC 9(8)} from {@code HOUSE.VALUE INTEGER} (via
 *                     {@code DB2-H-VALUE-INT PIC S9(9) COMP})
 * @param houseName    {@code CA-H-HOUSE-NAME PIC X(20)} from {@code HOUSE.HOUSENAME CHAR(20)}
 * @param houseNumber  {@code CA-H-HOUSE-NUMBER PIC X(4)} from {@code HOUSE.HOUSENUMBER CHAR(4)}
 * @param postcode     {@code CA-H-POSTCODE PIC X(8)} from {@code HOUSE.POSTCODE CHAR(8)}
 */
public record HouseDetails(
        String propertyType,
        long bedrooms,
        long value,
        String houseName,
        String houseNumber,
        String postcode) implements PolicyDetails {
}
