package com.lucure.core.codec;

import com.lucure.core.AuthorizationsHolder;
import com.lucure.core.security.Authorizations;
import com.lucure.core.security.FieldVisibility;
import com.lucure.core.security.VisibilityParseException;
import org.apache.lucene.index.DocsAndPositionsEnum;
import org.apache.lucene.util.AttributeSource;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.util.Arrays;

import static com.lucure.core.codec.AccessFilteredDocsAndPositionsEnum
  .AllAuthorizationsHolder.ALLAUTHSHOLDER;

/**
 * Enum to read and restrict access to a document based on the payload which
 * is expected to store the visibility
 */
public class AccessFilteredDocsAndPositionsEnum extends DocsAndPositionsEnum {

    /**
     * This placeholder allows for lucene specific operations such as
     * merge to read data with all authorizations enabled. This should never
     * be used outside of the Codec.
     */
    static class AllAuthorizationsHolder extends AuthorizationsHolder {

        static final AllAuthorizationsHolder ALLAUTHSHOLDER = new AllAuthorizationsHolder();

        private AllAuthorizationsHolder() {
            super(Authorizations.EMPTY);
        }
    }

    static void enableMergeAuthorizations() {
        AuthorizationsHolder.threadAuthorizations.set(ALLAUTHSHOLDER);
    }

    static void disableMergeAuthorizations() {
        AuthorizationsHolder.threadAuthorizations.remove();
    }

    private final DocsAndPositionsEnum docsAndPositionsEnum;
    private final AuthorizationsHolder authorizationsHolder;

    public AccessFilteredDocsAndPositionsEnum(
      DocsAndPositionsEnum docsAndPositionsEnum) {
        this(docsAndPositionsEnum, AuthorizationsHolder.threadAuthorizations.get());
    }

    public AccessFilteredDocsAndPositionsEnum(
      DocsAndPositionsEnum docsAndPositionsEnum,
      AuthorizationsHolder authorizationsHolder) {
        this.docsAndPositionsEnum = docsAndPositionsEnum;
        this.authorizationsHolder = authorizationsHolder;
    }

    long cost;
    int endOffset, startOffset, currentPosition, freq, docId;
    BytesRef payload;
    boolean currentPositionConsumed = false;

    @Override
    public int nextPosition() throws IOException {

        if(!currentPositionConsumed) {
            currentPositionConsumed = true;
            return currentPosition;
        }

        while (!hasAccess()) {

        }

//        payload = docsAndPositionsEnum.getPayload();
//        endOffset = docsAndPositionsEnum.endOffset();
//        startOffset = docsAndPositionsEnum.startOffset();
//
//        return docsAndPositionsEnum.nextPosition();
        return currentPosition;
    }

    @Override
    public int startOffset() throws IOException {
        return startOffset;
    }

    @Override
    public int endOffset() throws IOException {
        return endOffset;
    }

    @Override
    public BytesRef getPayload() throws IOException {
        return payload;
    }

    @Override
    public int freq() throws IOException {
        return docsAndPositionsEnum.freq();
    }

    @Override
    public int docID() {
        return docsAndPositionsEnum.docID();
    }

    @Override
    public int nextDoc() throws IOException {
        currentPositionConsumed = false;
        do {
            docsAndPositionsEnum.nextDoc();
        } while (docID() != NO_MORE_DOCS && !isPositionAvailable());

        return docID();
    }

    @Override
    public int advance(int target) throws IOException {
        currentPositionConsumed = false;
        docsAndPositionsEnum.advance(target);
        while(docID() != NO_MORE_DOCS && !isPositionAvailable()) {
            docsAndPositionsEnum.nextDoc();
        }

        return docID();
    }

    private boolean isPositionAvailable() throws IOException{
        do {
            if(hasAccess()){
                return true;
            }
        } while(currentPosition < freq() - 1);

        return false;
    }

    @Override
    public long cost() {
        return docsAndPositionsEnum.cost();
    }

    protected boolean hasAccess() throws IOException/*, VisibilityParseException*/ {
        payload = docsAndPositionsEnum.getPayload();
        endOffset = docsAndPositionsEnum.endOffset();
        startOffset = docsAndPositionsEnum.startOffset();
//        freq = docsAndPositionsEnum.freq();
//        docId = docsAndPositionsEnum.docID();
//        cost = docsAndPositionsEnum.cost();
        //this totally botches up positional information!  Can we read the payload without incrementing the position?
        //save the current information
        currentPosition = docsAndPositionsEnum.nextPosition();

        BytesRef payload = docsAndPositionsEnum.getPayload();
        try {
            if (payload == null ||
                    AllAuthorizationsHolder.ALLAUTHSHOLDER.equals(authorizationsHolder) ||
                    this.authorizationsHolder.getVisibilityEvaluator().evaluate(
                            new FieldVisibility(Arrays.copyOfRange(payload.bytes,
                                    payload.offset,
                                    payload.offset +
                                            payload.length)))) {
                //granted access
//            docsAndPositionsEnum.advance(currentPosition);
                return true;
            }
        } catch(VisibilityParseException e) {

        }
        return false;

    }

    @Override
    public AttributeSource attributes() {
        return super.attributes();
    }
}