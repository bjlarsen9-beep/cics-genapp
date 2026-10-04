package org.genapp.policy;

/**
 * Commercial policy section of the COMMAREA ({@code CA-COMMERCIAL REDEFINES CA-POLICY-SPECIFIC} in
 * {@code base/src/lgcmarea.cpy}), as filled by LGIPDB01 {@code GET-Commercial-DB2-INFO-1} from the
 * {@code DB2-COMMERCIAL} host structure ({@code base/src/lgpolicy.cpy}).
 *
 * <p>Text fields carry the CHAR column with trailing padding removed. Numeric fields are the signed Db2 SMALLINT /
 * INTEGER moved into an unsigned {@code PIC 9(n)} field: the sign is dropped and high-order digits beyond
 * {@code n} are truncated, as a COBOL {@code MOVE} does. {@code CA-B-FILLER} (and its {@code 'FINAL'} end marker)
 * is not part of the REST contract.
 *
 * @param address        {@code CA-B-Address PIC X(255)} ({@code COMMERCIAL.Address})
 * @param postcode       {@code CA-B-Postcode PIC X(8)} ({@code COMMERCIAL.Zipcode})
 * @param latitude       {@code CA-B-Latitude PIC X(11)} ({@code COMMERCIAL.LatitudeN})
 * @param longitude      {@code CA-B-Longitude PIC X(11)} ({@code COMMERCIAL.LongitudeW})
 * @param customer       {@code CA-B-Customer PIC X(255)} ({@code COMMERCIAL.Customer})
 * @param propertyType   {@code CA-B-PropType PIC X(255)} ({@code COMMERCIAL.PropertyType})
 * @param firePeril      {@code CA-B-FirePeril PIC 9(4)} ({@code COMMERCIAL.FirePeril})
 * @param firePremium    {@code CA-B-FirePremium PIC 9(8)} ({@code COMMERCIAL.FirePremium})
 * @param crimePeril     {@code CA-B-CrimePeril PIC 9(4)} ({@code COMMERCIAL.CrimePeril})
 * @param crimePremium   {@code CA-B-CrimePremium PIC 9(8)} ({@code COMMERCIAL.CrimePremium})
 * @param floodPeril     {@code CA-B-FloodPeril PIC 9(4)} ({@code COMMERCIAL.FloodPeril})
 * @param floodPremium   {@code CA-B-FloodPremium PIC 9(8)} ({@code COMMERCIAL.FloodPremium})
 * @param weatherPeril   {@code CA-B-WeatherPeril PIC 9(4)} ({@code COMMERCIAL.WeatherPeril})
 * @param weatherPremium {@code CA-B-WeatherPremium PIC 9(8)} ({@code COMMERCIAL.WeatherPremium})
 * @param status         {@code CA-B-Status PIC 9(4)} ({@code COMMERCIAL.Status})
 * @param rejectReason   {@code CA-B-RejectReason PIC X(255)} ({@code COMMERCIAL.RejectionReason})
 */
public record CommercialDetails(
        String address,
        String postcode,
        String latitude,
        String longitude,
        String customer,
        String propertyType,
        int firePeril,
        int firePremium,
        int crimePeril,
        int crimePremium,
        int floodPeril,
        int floodPremium,
        int weatherPeril,
        int weatherPremium,
        int status,
        String rejectReason) implements PolicyDetails {
}
