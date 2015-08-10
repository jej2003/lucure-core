package com.lucure.core.codec;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import org.apache.lucene.codecs.BlockTermState;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.PostingsReaderBase;
import org.apache.lucene.index.*;
import org.apache.lucene.index.FieldInfo.IndexOptions;
import org.apache.lucene.store.DataInput;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.RamUsageEstimator;

import java.io.IOException;
import java.util.Arrays;

import static com.lucure.core.codec.ForUtil.MAX_DATA_SIZE;
import static com.lucure.core.codec.ForUtil.MAX_ENCODED_SIZE;
import static com.lucure.core.codec.LucurePostingsFormat.BLOCK_SIZE;
import static com.lucure.core.codec.LucurePostingsWriter.IntBlockTermState;

/**
 * Concrete class that reads docId(maybe frq,pos,offset,payloads) list
 * with postings format.
 *
 * @see LucureSkipReader for details
 * @lucene.experimental
 */
public final class LucurePostingsReader extends PostingsReaderBase {

  private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(LucurePostingsReader.class);

  PostingsReaderBase delegate;

  public LucurePostingsReader(PostingsReaderBase delegate){
    this.delegate = delegate;
  }

  @Override
  public void init(IndexInput termsIn) throws IOException {
    delegate.init(termsIn);
  }

  @Override
  public BlockTermState newTermState() throws IOException {
    return delegate.newTermState();
  }

  @Override
  public void decodeTerm(long[] longs, DataInput in, FieldInfo fieldInfo, BlockTermState state, boolean absolute) throws IOException {
    delegate.decodeTerm(longs, in, fieldInfo, state, absolute);
  }

  @Override
  public DocsEnum docs(FieldInfo fieldInfo, BlockTermState state, Bits skipDocs, DocsEnum reuse, int flags) throws IOException {
    return docsAndPositions(fieldInfo, state, skipDocs, null, flags | DocsAndPositionsEnum.FLAG_PAYLOADS);
  }

  @Override
  public DocsAndPositionsEnum docsAndPositions(FieldInfo fieldInfo, BlockTermState state, Bits skipDocs, DocsAndPositionsEnum reuse, int flags) throws IOException {
    return new AccessFilteredDocsAndPositionsEnum(delegate.docsAndPositions(fieldInfo, state, skipDocs, reuse, flags | DocsAndPositionsEnum.FLAG_PAYLOADS));
  }

  @Override
  public void checkIntegrity() throws IOException {
    delegate.checkIntegrity();
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }

  @Override
  public long ramBytesUsed() {
    return BASE_RAM_BYTES_USED + delegate.ramBytesUsed();
  }
}
