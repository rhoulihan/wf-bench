package com.wf.benchmark.search;

/**
 * Result from a phonetic (SOUNDEX) search operation.
 * Contains the matched customer identifier, the matched name, and the SOUNDEX codes.
 */
public class PhoneticSearchResult {

    private final String customerNumber;
    private final String matchedValue;
    private final String soundexCode;

    public PhoneticSearchResult(String customerNumber, String matchedValue, String soundexCode) {
        this.customerNumber = customerNumber;
        this.matchedValue = matchedValue;
        this.soundexCode = soundexCode;
    }

    public String getCustomerNumber() {
        return customerNumber;
    }

    public String getMatchedValue() {
        return matchedValue;
    }

    public String getSoundexCode() {
        return soundexCode;
    }

    @Override
    public String toString() {
        return "PhoneticSearchResult{" +
                "customerNumber='" + customerNumber + '\'' +
                ", matchedValue='" + matchedValue + '\'' +
                ", soundexCode='" + soundexCode + '\'' +
                '}';
    }
}
