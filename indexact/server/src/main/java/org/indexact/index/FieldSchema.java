package org.indexact.index;

import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.IndexOptions;

/** Lucene field names and immutable types for an IndexAct snapshot. */
public final class FieldSchema {
    public static final String DOC_KEY = "doc_key";
    public static final String BODY = "body";
    public static final String RAW = "raw";
    public static final String LEN_TOKENS = "len_tokens";
    public static final String LEN_CODEPOINTS = "len_codepoints";
    public static final String TOKEN_SPANS = "token_spans";

    public static final FieldType BODY_TYPE;

    static {
        FieldType body = new FieldType();
        body.setTokenized(true);
        body.setStored(false);
        body.setOmitNorms(true);
        body.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        body.freeze();
        BODY_TYPE = body;
    }

    private FieldSchema() {}
}
