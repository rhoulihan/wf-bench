package com.wf.benchmark.generator;

import org.bson.Document;

/**
 * Interface for generating test documents.
 */
public interface DataGenerator {

    /**
     * Generate a document with the given sequence number.
     *
     * @param sequenceNumber unique sequence number for this document
     * @return generated BSON document
     */
    Document generate(long sequenceNumber);

    /**
     * Get the name of the collection this generator creates documents for.
     *
     * @return collection name
     */
    String getCollectionName();
}
