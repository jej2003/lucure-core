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

import com.lucure.core.AuthorizationsHolder;
import com.lucure.core.index.DelegatingRestrictedFieldVisitor;
import com.lucure.core.index.RestrictedStoredFieldVisitor;
import com.lucure.core.security.ColumnVisibility;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.StoredFieldsReader;
import org.apache.lucene.codecs.compressing.CompressionMode;
import org.apache.lucene.codecs.compressing.Decompressor;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.packed.PackedInts;

import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

import static com.lucure.core.codec.CompressingStoredFieldsWriter.*;
import static org.apache.lucene.codecs.lucene40.Lucene40StoredFieldsWriter
  .FIELDS_EXTENSION;
import static org.apache.lucene.codecs.lucene40.Lucene40StoredFieldsWriter
  .FIELDS_INDEX_EXTENSION;

/**
 * {@link StoredFieldsReader} impl for {@link CompressingStoredFieldsFormat}.
 * @lucene.experimental
 */
public final class CompressingStoredFieldsReader extends StoredFieldsReader {

  // Do not reuse the decompression buffer when there is more than 32kb to decompress
  private static final int BUFFER_REUSE_THRESHOLD = 1 << 15;

  private final int version;
  private final FieldInfos fieldInfos;
  private final CompressingStoredFieldsIndexReader indexReader;
  private final long maxPointer;
  private final IndexInput fieldsStream;
  private final int chunkSize;
  private final int packedIntsVersion;
  private final CompressionMode compressionMode;
  private final Decompressor decompressor;
  private final BytesRef bytes;
  private final int numDocs;
  private boolean closed;

  // used by clone
  private CompressingStoredFieldsReader(CompressingStoredFieldsReader reader) {
    this.version = reader.version;
    this.fieldInfos = reader.fieldInfos;
    this.fieldsStream = reader.fieldsStream.clone();
    this.indexReader = reader.indexReader.clone();
    this.maxPointer = reader.maxPointer;
    this.chunkSize = reader.chunkSize;
    this.packedIntsVersion = reader.packedIntsVersion;
    this.compressionMode = reader.compressionMode;
    this.decompressor = reader.decompressor.clone();
    this.numDocs = reader.numDocs;
    this.bytes = new BytesRef(reader.bytes.bytes.length);
    this.closed = false;
  }

  /** Sole constructor. */
  public CompressingStoredFieldsReader(Directory d, SegmentInfo si, String segmentSuffix, FieldInfos fn,
      IOContext context, String formatName, CompressionMode compressionMode) throws IOException {
    this.compressionMode = compressionMode;
    final String segment = si.name;
    boolean success = false;
    fieldInfos = fn;
    numDocs = si.getDocCount();
    ChecksumIndexInput indexStream = null;
    try {
      final String indexStreamFN = IndexFileNames.segmentFileName(segment, segmentSuffix, FIELDS_INDEX_EXTENSION);
      final String fieldsStreamFN = IndexFileNames.segmentFileName(segment, segmentSuffix, FIELDS_EXTENSION);
      // Load the index into memory
      indexStream = d.openChecksumInput(indexStreamFN, context);
      final String codecNameIdx = formatName + CODEC_SFX_IDX;
      version = CodecUtil.checkHeader(indexStream, codecNameIdx, VERSION_START, VERSION_CURRENT);
      assert CodecUtil.headerLength(codecNameIdx) == indexStream.getFilePointer();
      indexReader = new CompressingStoredFieldsIndexReader(indexStream, si);

      long maxPointer = -1;

      if (version >= VERSION_CHECKSUM) {
        maxPointer = indexStream.readVLong();
        CodecUtil.checkFooter(indexStream);
      } else {
        CodecUtil.checkEOF(indexStream);
      }
      indexStream.close();
      indexStream = null;

      // Open the data file and read metadata
      fieldsStream = d.openInput(fieldsStreamFN, context);
      if (version >= VERSION_CHECKSUM) {
        if (maxPointer + CodecUtil.footerLength() != fieldsStream.length()) {
          throw new CorruptIndexException("Invalid fieldsStream maxPointer (file truncated?): maxPointer=" + maxPointer + ", length=" + fieldsStream.length());
        }
      } else {
        maxPointer = fieldsStream.length();
      }
      this.maxPointer = maxPointer;
      final String codecNameDat = formatName + CODEC_SFX_DAT;
      final int fieldsVersion = CodecUtil.checkHeader(fieldsStream, codecNameDat, VERSION_START, VERSION_CURRENT);
      if (version != fieldsVersion) {
        throw new CorruptIndexException("Version mismatch between stored fields index and data: " + version + " != " + fieldsVersion);
      }
      assert CodecUtil.headerLength(codecNameDat) == fieldsStream.getFilePointer();

      if (version >= VERSION_BIG_CHUNKS) {
        chunkSize = fieldsStream.readVInt();
      } else {
        chunkSize = -1;
      }
      packedIntsVersion = fieldsStream.readVInt();
      decompressor = compressionMode.newDecompressor();
      this.bytes = new BytesRef();

      if (version >= VERSION_CHECKSUM) {
        // NOTE: data file is too costly to verify checksum against all the bytes on open,
        // but for now we at least verify proper structure of the checksum footer: which looks
        // for FOOTER_MAGIC + algorithmID. This is cheap and can detect some forms of corruption
        // such as file truncation.
        CodecUtil.retrieveChecksum(fieldsStream);
      }

      success = true;
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(this, indexStream);
      }
    }
  }

  /**
   * @throws AlreadyClosedException if this FieldsReader is closed
   */
  private void ensureOpen() throws AlreadyClosedException {
    if (closed) {
      throw new AlreadyClosedException("this FieldsReader is closed");
    }
  }

  /** 
   * Close the underlying {@link IndexInput}s.
   */
  @Override
  public void close() throws IOException {
    if (!closed) {
      IOUtils.close(fieldsStream);
      closed = true;
    }
  }

  private static void readField(
    DataInput in, RestrictedStoredFieldVisitor visitor, FieldInfo info,
    int bits, ColumnVisibility columnVisibility) throws IOException {

    switch (bits & TYPE_MASK) {
      case BYTE_ARR:
        int length = in.readVInt();
        byte[] data = new byte[length];
        in.readBytes(data, 0, length);
        visitor.binaryField(info, data, columnVisibility);
        break;
      case STRING:
        length = in.readVInt();
        data = new byte[length];
        in.readBytes(data, 0, length);
        visitor.stringField(info, new String(data, IOUtils.CHARSET_UTF_8), columnVisibility);
        break;
      case NUMERIC_INT:
          int intValue = in.readInt();
          visitor.intField(info, intValue, columnVisibility);
        break;
      case NUMERIC_FLOAT:
          float floatValue = Float.intBitsToFloat(in.readInt());
          visitor.floatField(info, floatValue, columnVisibility);
        break;
      case NUMERIC_LONG:
          long longValue = in.readLong();
          visitor.longField(info, longValue, columnVisibility);
        break;
      case NUMERIC_DOUBLE:
          double doubleValue = Double.longBitsToDouble(in.readLong());
          visitor.doubleField(info, doubleValue, columnVisibility);
        break;
      default:
        throw new AssertionError("Unknown type flag: " + Integer.toHexString(bits));
    }
  }

  private static void skipField(DataInput in, int bits, ColumnVisibility cv) throws IOException {
    switch (bits & TYPE_MASK) {
      case BYTE_ARR:
      case STRING:
        final int length = in.readVInt();
        in.skipBytes(length);
        break;
      case NUMERIC_INT:
      case NUMERIC_FLOAT:
        in.readInt();
        break;
      case NUMERIC_LONG:
      case NUMERIC_DOUBLE:
        in.readLong();
        break;
      default:
        throw new AssertionError("Unknown type flag: " + Integer.toHexString(bits));
    }
  }

  @Override
  public void visitDocument(int docID, StoredFieldVisitor visitor)
      throws IOException {
    fieldsStream.seek(indexReader.getStartPointer(docID));

    final int docBase = fieldsStream.readVInt();
    final int chunkDocs = fieldsStream.readVInt();
    if (docID < docBase
        || docID >= docBase + chunkDocs
        || docBase + chunkDocs > numDocs) {
      throw new CorruptIndexException("Corrupted: docID=" + docID
          + ", docBase=" + docBase + ", chunkDocs=" + chunkDocs
          + ", numDocs=" + numDocs + " (resource=" + fieldsStream + ")");
    }

    final int numStoredFields, offset, length, totalLength;
    if (chunkDocs == 1) {
      numStoredFields = fieldsStream.readVInt();
      offset = 0;
      length = fieldsStream.readVInt();
      totalLength = length;
    } else {
      final int bitsPerStoredFields = fieldsStream.readVInt();
      if (bitsPerStoredFields == 0) {
        numStoredFields = fieldsStream.readVInt();
      } else if (bitsPerStoredFields > 31) {
        throw new CorruptIndexException("bitsPerStoredFields=" + bitsPerStoredFields + " (resource=" + fieldsStream + ")");
      } else {
        final long filePointer = fieldsStream.getFilePointer();
        final PackedInts.Reader reader = PackedInts.getDirectReaderNoHeader(fieldsStream, PackedInts.Format.PACKED, packedIntsVersion, chunkDocs, bitsPerStoredFields);
        numStoredFields = (int) (reader.get(docID - docBase));
        fieldsStream.seek(filePointer + PackedInts.Format.PACKED.byteCount(packedIntsVersion, chunkDocs, bitsPerStoredFields));
      }

      final int bitsPerLength = fieldsStream.readVInt();
      if (bitsPerLength == 0) {
        length = fieldsStream.readVInt();
        offset = (docID - docBase) * length;
        totalLength = chunkDocs * length;
      } else if (bitsPerStoredFields > 31) {
        throw new CorruptIndexException("bitsPerLength=" + bitsPerLength + " (resource=" + fieldsStream + ")");
      } else {
        final PackedInts.ReaderIterator it = PackedInts.getReaderIteratorNoHeader(fieldsStream, PackedInts.Format.PACKED, packedIntsVersion, chunkDocs, bitsPerLength, 1);
        int off = 0;
        for (int i = 0; i < docID - docBase; ++i) {
          off += it.next();
        }
        offset = off;
        length = (int) it.next();
        off += length;
        for (int i = docID - docBase + 1; i < chunkDocs; ++i) {
          off += it.next();
        }
        totalLength = off;
      }
    }

    if ((length == 0) != (numStoredFields == 0)) {
      throw new CorruptIndexException("length=" + length + ", numStoredFields=" + numStoredFields + " (resource=" + fieldsStream + ")");
    }
    if (numStoredFields == 0) {
      // nothing to do
      return;
    }

    final DataInput documentInput;
    if (version >= VERSION_BIG_CHUNKS && totalLength >= 2 * chunkSize) {
      assert chunkSize > 0;
      assert offset < chunkSize;

      decompressor.decompress(fieldsStream, chunkSize, offset, Math.min(length, chunkSize - offset), bytes);
      documentInput = new DataInput() {

        int decompressed = bytes.length;

        void fillBuffer() throws IOException {
          assert decompressed <= length;
          if (decompressed == length) {
            throw new EOFException();
          }
          final int toDecompress = Math.min(length - decompressed, chunkSize);
          decompressor.decompress(fieldsStream, toDecompress, 0, toDecompress, bytes);
          decompressed += toDecompress;
        }

        @Override
        public byte readByte() throws IOException {
          if (bytes.length == 0) {
            fillBuffer();
          }
          --bytes.length;
          return bytes.bytes[bytes.offset++];
        }

        @Override
        public void readBytes(byte[] b, int offset, int len) throws IOException {
          while (len > bytes.length) {
            System.arraycopy(bytes.bytes, bytes.offset, b, offset, bytes.length);
            len -= bytes.length;
            offset += bytes.length;
            fillBuffer();
          }
          System.arraycopy(bytes.bytes, bytes.offset, b, offset, len);
          bytes.offset += len;
          bytes.length -= len;
        }

      };
    } else {
      final BytesRef bytes = totalLength <= BUFFER_REUSE_THRESHOLD ? this.bytes : new BytesRef();
      decompressor.decompress(fieldsStream, totalLength, offset, length, bytes);
      assert bytes.length == length;
      documentInput = new ByteArrayDataInput(bytes.bytes, bytes.offset, bytes.length);
    }

    for (int fieldIDX = 0; fieldIDX < numStoredFields; fieldIDX++) {
      final long infoAndBits = documentInput.readVLong();
      final int fieldNumber = (int) (infoAndBits >>> TYPE_BITS);
      final FieldInfo fieldInfo = fieldInfos.fieldInfo(fieldNumber);

      final int bits = (int) (infoAndBits & TYPE_MASK);
      assert bits <= NUMERIC_DOUBLE: "bits=" + Integer.toHexString(bits);

        //get restricted
        ColumnVisibility cv = RestrictedStoredFieldVisitor.EMPTY;
        boolean isRestricted = documentInput.readByte() == 1;
        if(isRestricted) {
            int cv_length = documentInput.readVInt();
            byte[] cv_bytes = new byte[cv_length];
            documentInput.readBytes(cv_bytes, 0, cv_length);
            cv = new ColumnVisibility(cv_bytes);
        }

        RestrictedStoredFieldVisitor restrictedStoredFieldVisitor =
          visitor instanceof RestrictedStoredFieldVisitor ?
          (RestrictedStoredFieldVisitor) visitor :
          new DelegatingRestrictedFieldVisitor(visitor,
                                               AuthorizationsHolder
                                                 .threadAuthorizations
                                                 .get()
                                                 .getVisibilityEvaluator());
      switch(restrictedStoredFieldVisitor.needsField(fieldInfo, cv)) {
        case YES:
            readField(documentInput, restrictedStoredFieldVisitor, fieldInfo, bits, cv);
            break;
        case NO:
          skipField(documentInput, bits, cv);
          break;
        case STOP:
          return;
      }
    }
  }

  @Override
  public StoredFieldsReader clone() {
    ensureOpen();
    return new CompressingStoredFieldsReader(this);
  }

  int getVersion() {
    return version;
  }

  CompressionMode getCompressionMode() {
    return compressionMode;
  }

  int getChunkSize() {
    return chunkSize;
  }

  ChunkIterator chunkIterator(int startDocID) throws IOException {
    ensureOpen();
    fieldsStream.seek(indexReader.getStartPointer(startDocID));
    return new ChunkIterator(startDocID);
  }

  final class ChunkIterator {

    final ChecksumIndexInput fieldsStream;
    final BytesRef spare;
    final BytesRef bytes;
    int docBase;
    int chunkDocs;
    int[] numStoredFields;
    int[] lengths;

    private ChunkIterator(int startDocId) throws IOException {
      this.docBase = -1;
      bytes = new BytesRef();
      spare = new BytesRef();
      numStoredFields = new int[1];
      lengths = new int[1];

      IndexInput in = CompressingStoredFieldsReader.this.fieldsStream;
      in.seek(0);
      fieldsStream = new BufferedChecksumIndexInput(in);
      fieldsStream.seek(indexReader.getStartPointer(startDocId));
    }

    /**
     * Return the decompressed size of the chunk
     */
    int chunkSize() {
      int sum = 0;
      for (int i = 0; i < chunkDocs; ++i) {
        sum += lengths[i];
      }
      return sum;
    }

    /**
     * Go to the chunk containing the provided doc ID.
     */
    void next(int doc) throws IOException {
      assert doc >= docBase + chunkDocs : doc + " " + docBase + " " + chunkDocs;
      fieldsStream.seek(indexReader.getStartPointer(doc));

      final int docBase = fieldsStream.readVInt();
      final int chunkDocs = fieldsStream.readVInt();
      if (docBase < this.docBase + this.chunkDocs
          || docBase + chunkDocs > numDocs) {
        throw new CorruptIndexException("Corrupted: current docBase=" + this.docBase
            + ", current numDocs=" + this.chunkDocs + ", new docBase=" + docBase
            + ", new numDocs=" + chunkDocs + " (resource=" + fieldsStream + ")");
      }
      this.docBase = docBase;
      this.chunkDocs = chunkDocs;

      if (chunkDocs > numStoredFields.length) {
        final int newLength = ArrayUtil.oversize(chunkDocs, 4);
        numStoredFields = new int[newLength];
        lengths = new int[newLength];
      }

      if (chunkDocs == 1) {
        numStoredFields[0] = fieldsStream.readVInt();
        lengths[0] = fieldsStream.readVInt();
      } else {
        final int bitsPerStoredFields = fieldsStream.readVInt();
        if (bitsPerStoredFields == 0) {
          Arrays.fill(numStoredFields, 0, chunkDocs, fieldsStream.readVInt());
        } else if (bitsPerStoredFields > 31) {
          throw new CorruptIndexException("bitsPerStoredFields=" + bitsPerStoredFields + " (resource=" + fieldsStream + ")");
        } else {
          final PackedInts.ReaderIterator it = PackedInts.getReaderIteratorNoHeader(fieldsStream, PackedInts.Format.PACKED, packedIntsVersion, chunkDocs, bitsPerStoredFields, 1);
          for (int i = 0; i < chunkDocs; ++i) {
            numStoredFields[i] = (int) it.next();
          }
        }

        final int bitsPerLength = fieldsStream.readVInt();
        if (bitsPerLength == 0) {
          Arrays.fill(lengths, 0, chunkDocs, fieldsStream.readVInt());
        } else if (bitsPerLength > 31) {
          throw new CorruptIndexException("bitsPerLength=" + bitsPerLength);
        } else {
          final PackedInts.ReaderIterator it = PackedInts.getReaderIteratorNoHeader(fieldsStream, PackedInts.Format.PACKED, packedIntsVersion, chunkDocs, bitsPerLength, 1);
          for (int i = 0; i < chunkDocs; ++i) {
            lengths[i] = (int) it.next();
          }
        }
      }
    }

    /**
     * Decompress the chunk.
     */
    void decompress() throws IOException {
      // decompress data
      final int chunkSize = chunkSize();
      if (version >= VERSION_BIG_CHUNKS && chunkSize >= 2 * CompressingStoredFieldsReader.this.chunkSize) {
        bytes.offset = bytes.length = 0;
        for (int decompressed = 0; decompressed < chunkSize; ) {
          final int toDecompress = Math.min(chunkSize - decompressed, CompressingStoredFieldsReader.this.chunkSize);
          decompressor.decompress(fieldsStream, toDecompress, 0, toDecompress, spare);
          bytes.bytes = ArrayUtil.grow(bytes.bytes, bytes.length + spare.length);
          System.arraycopy(spare.bytes, spare.offset, bytes.bytes, bytes.length, spare.length);
          bytes.length += spare.length;
          decompressed += toDecompress;
        }
      } else {
        decompressor.decompress(fieldsStream, chunkSize, 0, chunkSize, bytes);
      }
      if (bytes.length != chunkSize) {
        throw new CorruptIndexException("Corrupted: expected chunk size = " + chunkSize() + ", got " + bytes.length + " (resource=" + fieldsStream + ")");
      }
    }

    /**
     * Check integrity of the data. The iterator is not usable after this method has been called.
     */
    void checkIntegrity() throws IOException {
      if (version >= VERSION_CHECKSUM) {
        fieldsStream.seek(fieldsStream.length() - CodecUtil.footerLength());
        CodecUtil.checkFooter(fieldsStream);
      }
    }

  }

  @Override
  public long ramBytesUsed() {
    return indexReader.ramBytesUsed();
  }

  @Override
  public void checkIntegrity() throws IOException {
    if (version >= VERSION_CHECKSUM) {
      CodecUtil.checksumEntireFile(fieldsStream);
    }
  }

}
