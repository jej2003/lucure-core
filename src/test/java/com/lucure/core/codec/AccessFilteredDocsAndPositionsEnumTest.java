package com.lucure.core.codec;

import com.lucure.core.AuthorizationsHolder;
import com.lucure.core.security.Authorizations;
import org.apache.lucene.index.DocsAndPositionsEnum;
import org.apache.lucene.util.BytesRef;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static com.lucure.core.codec.AccessFilteredDocsAndPositionsEnum
  .AllAuthorizationsHolder.ALLAUTHSHOLDER;
import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class AccessFilteredDocsAndPositionsEnumTest {

    @Test
    public void testDocsBasedOnVisibility() throws Exception {
        final DocsAndPositionsEnum docsAndPositionsEnum = mock(
          DocsAndPositionsEnum.class);
        when(docsAndPositionsEnum.nextDoc()).thenReturn(0);
        when(docsAndPositionsEnum.nextPosition()).thenReturn(0);
        when(docsAndPositionsEnum.getPayload()).thenReturn(new BytesRef("A"));

        final AccessFilteredDocsAndPositionsEnum accessFilteredDocsAndPositionsEnum
          = new AccessFilteredDocsAndPositionsEnum(docsAndPositionsEnum,
                                                   new AuthorizationsHolder(
                                                     new Authorizations("A")));
        assertEquals(0, accessFilteredDocsAndPositionsEnum.nextDoc());
    }

    @Test
    public void testFilterDocsBasedOnVisibility() throws Exception {
        final DocsAndPositionsEnum docsAndPositionsEnum = mock(
          DocsAndPositionsEnum.class);
        when(docsAndPositionsEnum.docID()).thenReturn(0);
        when(docsAndPositionsEnum.nextDoc()).thenReturn(0);
        when(docsAndPositionsEnum.freq()).thenReturn(1);
        when(docsAndPositionsEnum.nextPosition()).thenAnswer(new Answer<Integer>() {
            int i = -1;

            @Override
            public Integer answer(InvocationOnMock invocationOnMock) throws Throwable {
                return ++i;
            }
        });
        when(docsAndPositionsEnum.getPayload()).thenReturn(new BytesRef("B"));
        when(docsAndPositionsEnum.docID()).thenReturn(NO_MORE_DOCS);
        when(docsAndPositionsEnum.nextDoc()).thenReturn(NO_MORE_DOCS);

        final AccessFilteredDocsAndPositionsEnum accessFilteredDocsAndPositionsEnum
          = new AccessFilteredDocsAndPositionsEnum(docsAndPositionsEnum,
                                                   new AuthorizationsHolder(
                                                     new Authorizations("A")));
        assertEquals(NO_MORE_DOCS, accessFilteredDocsAndPositionsEnum.nextDoc());
    }

    @Test
    public void testAllAuths() throws Exception {
        final DocsAndPositionsEnum docsAndPositionsEnum = mock(
          DocsAndPositionsEnum.class);
        when(docsAndPositionsEnum.nextDoc()).thenReturn(0);
        when(docsAndPositionsEnum.nextPosition()).thenReturn(0);
        when(docsAndPositionsEnum.freq()).thenReturn(1);
        when(docsAndPositionsEnum.getPayload()).thenReturn(new BytesRef("A"));

        final AccessFilteredDocsAndPositionsEnum accessFilteredDocsAndPositionsEnum
          = new AccessFilteredDocsAndPositionsEnum(docsAndPositionsEnum,
                                                   ALLAUTHSHOLDER);
        assertEquals(0, accessFilteredDocsAndPositionsEnum.nextDoc());
    }
}