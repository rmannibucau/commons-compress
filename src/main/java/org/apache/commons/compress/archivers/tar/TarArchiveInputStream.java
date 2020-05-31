/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

/*
 * This package is based on the work done by Timothy Gerard Endres
 * (time@ice.com) to whom the Ant project is very grateful for his great code.
 */

package org.apache.commons.compress.archivers.tar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipEncoding;
import org.apache.commons.compress.archivers.zip.ZipEncodingHelper;
import org.apache.commons.compress.utils.ArchiveUtils;
import org.apache.commons.compress.utils.BoundedInputStream;
import org.apache.commons.compress.utils.CharsetNames;
import org.apache.commons.compress.utils.IOUtils;

/**
 * The TarInputStream reads a UNIX tar archive as an InputStream.
 * methods are provided to position at each successive entry in
 * the archive, and the read each entry as a normal input stream
 * using read().
 * @NotThreadSafe
 */
public class TarArchiveInputStream extends ArchiveInputStream {

    private static final int SMALL_BUFFER_SIZE = 256;

    private final byte[] smallBuf = new byte[SMALL_BUFFER_SIZE];

    /** The size the TAR header */
    private final int recordSize;

    /** The size of a block */
    private final int blockSize;

    /** True if file has hit EOF */
    private boolean hasHitEOF;

    /** Size of the current entry */
    private long entrySize;

    /** How far into the entry the stream is at */
    private long entryOffset;

    /** An input stream to read from */
    private final InputStream inputStream;

    /** Input streams for reading sparse entries **/
    private List<InputStream> sparseInputStreams;

    /** the index of current input stream being read when reading sparse entries */
    private int currentSparseInputStreamIndex;

    /** The meta-data about the current entry */
    private TarArchiveEntry currEntry;

    /** The encoding of the file */
    private final ZipEncoding zipEncoding;

    // the provided encoding (for unit tests)
    final String encoding;

    // the global PAX header
    private Map<String, String> globalPaxHeaders = new HashMap<>();

    // the global sparse headers, this is only used in PAX Format 0.X
    private final List<TarArchiveStructSparse> globalSparseHeaders = new ArrayList<>();

    private final boolean lenient;

    /**
     * Constructor for TarInputStream.
     * @param is the input stream to use
     */
    public TarArchiveInputStream(final InputStream is) {
        this(is, TarConstants.DEFAULT_BLKSIZE, TarConstants.DEFAULT_RCDSIZE);
    }

    /**
     * Constructor for TarInputStream.
     * @param is the input stream to use
     * @param lenient when set to true illegal values for group/userid, mode, device numbers and timestamp will be
     * ignored and the fields set to {@link TarArchiveEntry#UNKNOWN}. When set to false such illegal fields cause an
     * exception instead.
     * @since 1.19
     */
    public TarArchiveInputStream(final InputStream is, boolean lenient) {
        this(is, TarConstants.DEFAULT_BLKSIZE, TarConstants.DEFAULT_RCDSIZE, null, lenient);
    }

    /**
     * Constructor for TarInputStream.
     * @param is the input stream to use
     * @param encoding name of the encoding to use for file names
     * @since 1.4
     */
    public TarArchiveInputStream(final InputStream is, final String encoding) {
        this(is, TarConstants.DEFAULT_BLKSIZE, TarConstants.DEFAULT_RCDSIZE,
             encoding);
    }

    /**
     * Constructor for TarInputStream.
     * @param is the input stream to use
     * @param blockSize the block size to use
     */
    public TarArchiveInputStream(final InputStream is, final int blockSize) {
        this(is, blockSize, TarConstants.DEFAULT_RCDSIZE);
    }

    /**
     * Constructor for TarInputStream.
     * @param is the input stream to use
     * @param blockSize the block size to use
     * @param encoding name of the encoding to use for file names
     * @since 1.4
     */
    public TarArchiveInputStream(final InputStream is, final int blockSize,
                                 final String encoding) {
        this(is, blockSize, TarConstants.DEFAULT_RCDSIZE, encoding);
    }

    /**
     * Constructor for TarInputStream.
     * @param is the input stream to use
     * @param blockSize the block size to use
     * @param recordSize the record size to use
     */
    public TarArchiveInputStream(final InputStream is, final int blockSize, final int recordSize) {
        this(is, blockSize, recordSize, null);
    }

    /**
     * Constructor for TarInputStream.
     * @param is the input stream to use
     * @param blockSize the block size to use
     * @param recordSize the record size to use
     * @param encoding name of the encoding to use for file names
     * @since 1.4
     */
    public TarArchiveInputStream(final InputStream is, final int blockSize, final int recordSize,
                                 final String encoding) {
        this(is, blockSize, recordSize, encoding, false);
    }

    /**
     * Constructor for TarInputStream.
     * @param is the input stream to use
     * @param blockSize the block size to use
     * @param recordSize the record size to use
     * @param encoding name of the encoding to use for file names
     * @param lenient when set to true illegal values for group/userid, mode, device numbers and timestamp will be
     * ignored and the fields set to {@link TarArchiveEntry#UNKNOWN}. When set to false such illegal fields cause an
     * exception instead.
     * @since 1.19
     */
    public TarArchiveInputStream(final InputStream is, final int blockSize, final int recordSize,
                                 final String encoding, boolean lenient) {
        this.inputStream = is;
        this.hasHitEOF = false;
        this.encoding = encoding;
        this.zipEncoding = ZipEncodingHelper.getZipEncoding(encoding);
        this.recordSize = recordSize;
        this.blockSize = blockSize;
        this.lenient = lenient;
    }

    /**
     * Closes this stream. Calls the TarBuffer's close() method.
     * @throws IOException on error
     */
    @Override
    public void close() throws IOException {
        // Close all the input streams in sparseInputStreams
        if(sparseInputStreams != null) {
            for (InputStream inputStream : sparseInputStreams) {
                inputStream.close();
            }
        }

        inputStream.close();
    }

    /**
     * Get the record size being used by this stream's buffer.
     *
     * @return The TarBuffer record size.
     */
    public int getRecordSize() {
        return recordSize;
    }

    /**
     * Get the available data that can be read from the current
     * entry in the archive. This does not indicate how much data
     * is left in the entire archive, only in the current entry.
     * This value is determined from the entry's size header field
     * and the amount of data already read from the current entry.
     * Integer.MAX_VALUE is returned in case more than Integer.MAX_VALUE
     * bytes are left in the current entry in the archive.
     *
     * @return The number of available bytes for the current entry.
     * @throws IOException for signature
     */
    @Override
    public int available() throws IOException {
        if (isDirectory()) {
            return 0;
        }

        if (currEntry.getRealSize() - entryOffset > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) (currEntry.getRealSize() - entryOffset);
    }


    /**
     * Skips over and discards <code>n</code> bytes of data from this input
     * stream. The <code>skip</code> method may, for a variety of reasons, end
     * up skipping over some smaller number of bytes, possibly <code>0</code>.
     * This may result from any of a number of conditions; reaching end of file
     * or end of entry before <code>n</code> bytes have been skipped; are only
     * two possibilities. The actual number of bytes skipped is returned. If
     * <code>n</code> is negative, no bytes are skipped.
     *
     *
     * @param n
     *            the number of bytes to be skipped.
     * @return the actual number of bytes skipped.
     * @throws IOException
     *                if some other I/O error occurs.
     */
    @Override
    public long skip(final long n) throws IOException {
        if (n <= 0 || isDirectory()) {
            return 0;
        }

        final long available = currEntry.getRealSize() - entryOffset;
        final long skipped;
        if (!currEntry.isSparse()) {
            skipped = IOUtils.skip(inputStream, Math.min(n, available));
        } else {
            skipped = skipSparse(Math.min(n, available));
        }
        count(skipped);
        entryOffset += skipped;
        return skipped;
    }

    /**
     * Skip n bytes from current input stream, if the current input stream doesn't have enough data to skip,
     * jump to the next input stream and skip the rest bytes, keep doing this until total n bytes are skipped
     * or the input streams are all skipped
     *
     * @param n bytes of data to skip
     * @return actual bytes of data skipped
     * @throws IOException
     */
    private long skipSparse(final long n) throws IOException {
        if (sparseInputStreams == null || sparseInputStreams.size() == 0) {
            return inputStream.skip(n);
        }

        long bytesSkipped = 0;

        while (bytesSkipped < n && currentSparseInputStreamIndex < sparseInputStreams.size()) {
            final InputStream  currentInputStream = sparseInputStreams.get(currentSparseInputStreamIndex);
            bytesSkipped += currentInputStream.skip(n - bytesSkipped);

            if (bytesSkipped < n) {
                currentSparseInputStreamIndex++;
            }
        }

        return bytesSkipped;
    }

    /**
     * Since we do not support marking just yet, we return false.
     *
     * @return False.
     */
    @Override
    public boolean markSupported() {
        return false;
    }

    /**
     * Since we do not support marking just yet, we do nothing.
     *
     * @param markLimit The limit to mark.
     */
    @Override
    public synchronized void mark(final int markLimit) {
    }

    /**
     * Since we do not support marking just yet, we do nothing.
     */
    @Override
    public synchronized void reset() {
    }

    /**
     * Get the next entry in this tar archive. This will skip
     * over any remaining data in the current entry, if there
     * is one, and place the input stream at the header of the
     * next entry, and read the header and instantiate a new
     * TarEntry from the header bytes and return that entry.
     * If there are no more entries in the archive, null will
     * be returned to indicate that the end of the archive has
     * been reached.
     *
     * @return The next TarEntry in the archive, or null.
     * @throws IOException on error
     */
    public TarArchiveEntry getNextTarEntry() throws IOException {
        if (isAtEOF()) {
            return null;
        }

        if (currEntry != null) {
            /* Skip will only go to the end of the current entry */
            IOUtils.skip(this, Long.MAX_VALUE);

            /* skip to the end of the last record */
            skipRecordPadding();
        }

        final byte[] headerBuf = getRecord();

        if (headerBuf == null) {
            /* hit EOF */
            currEntry = null;
            return null;
        }

        try {
            currEntry = new TarArchiveEntry(headerBuf, zipEncoding, lenient);
        } catch (final IllegalArgumentException e) {
            throw new IOException("Error detected parsing the header", e);
        }

        entryOffset = 0;
        entrySize = currEntry.getSize();

        if (currEntry.isGNULongLinkEntry()) {
            final byte[] longLinkData = getLongNameData();
            if (longLinkData == null) {
                // Bugzilla: 40334
                // Malformed tar file - long link entry name not followed by
                // entry
                return null;
            }
            currEntry.setLinkName(zipEncoding.decode(longLinkData));
        }

        if (currEntry.isGNULongNameEntry()) {
            final byte[] longNameData = getLongNameData();
            if (longNameData == null) {
                // Bugzilla: 40334
                // Malformed tar file - long entry name not followed by
                // entry
                return null;
            }

            // COMPRESS-509 : the name of directories should end with '/'
            String name = zipEncoding.decode(longNameData);
            if (currEntry.isDirectory() && !name.endsWith("/")) {
                name += "/";
            }
            currEntry.setName(name);
        }

        if (currEntry.isGlobalPaxHeader()){ // Process Global Pax headers
            readGlobalPaxHeaders();
        }

        if (currEntry.isPaxHeader()){ // Process Pax headers
            paxHeaders();
        } else if (!globalPaxHeaders.isEmpty()) {
            applyPaxHeadersToCurrentEntry(globalPaxHeaders, globalSparseHeaders);
        }

        if (currEntry.isOldGNUSparse()){ // Process sparse files
            readOldGNUSparse();
        }

        // If the size of the next element in the archive has changed
        // due to a new size being reported in the posix header
        // information, we update entrySize here so that it contains
        // the correct value.
        entrySize = currEntry.getSize();

        return currEntry;
    }

    /**
     * The last record block should be written at the full size, so skip any
     * additional space used to fill a record after an entry
     */
    private void skipRecordPadding() throws IOException {
        if (!isDirectory() && this.entrySize > 0 && this.entrySize % this.recordSize != 0) {
            final long numRecords = (this.entrySize / this.recordSize) + 1;
            final long padding = (numRecords * this.recordSize) - this.entrySize;
            final long skipped = IOUtils.skip(inputStream, padding);
            count(skipped);
        }
    }

    /**
     * Get the next entry in this tar archive as longname data.
     *
     * @return The next entry in the archive as longname data, or null.
     * @throws IOException on error
     */
    protected byte[] getLongNameData() throws IOException {
        // read in the name
        final ByteArrayOutputStream longName = new ByteArrayOutputStream();
        int length = 0;
        while ((length = read(smallBuf)) >= 0) {
            longName.write(smallBuf, 0, length);
        }
        getNextEntry();
        if (currEntry == null) {
            // Bugzilla: 40334
            // Malformed tar file - long entry name not followed by entry
            return null;
        }
        byte[] longNameData = longName.toByteArray();
        // remove trailing null terminator(s)
        length = longNameData.length;
        while (length > 0 && longNameData[length - 1] == 0) {
            --length;
        }
        if (length != longNameData.length) {
            final byte[] l = new byte[length];
            System.arraycopy(longNameData, 0, l, 0, length);
            longNameData = l;
        }
        return longNameData;
    }

    /**
     * Get the next record in this tar archive. This will skip
     * over any remaining data in the current entry, if there
     * is one, and place the input stream at the header of the
     * next entry.
     *
     * <p>If there are no more entries in the archive, null will be
     * returned to indicate that the end of the archive has been
     * reached.  At the same time the {@code hasHitEOF} marker will be
     * set to true.</p>
     *
     * @return The next header in the archive, or null.
     * @throws IOException on error
     */
    private byte[] getRecord() throws IOException {
        byte[] headerBuf = readRecord();
        setAtEOF(isEOFRecord(headerBuf));
        if (isAtEOF() && headerBuf != null) {
            tryToConsumeSecondEOFRecord();
            consumeRemainderOfLastBlock();
            headerBuf = null;
        }
        return headerBuf;
    }

    /**
     * Determine if an archive record indicate End of Archive. End of
     * archive is indicated by a record that consists entirely of null bytes.
     *
     * @param record The record data to check.
     * @return true if the record data is an End of Archive
     */
    protected boolean isEOFRecord(final byte[] record) {
        return record == null || ArchiveUtils.isArrayZero(record, recordSize);
    }

    /**
     * Read a record from the input stream and return the data.
     *
     * @return The record data or null if EOF has been hit.
     * @throws IOException on error
     */
    protected byte[] readRecord() throws IOException {

        final byte[] record = new byte[recordSize];

        final int readNow = IOUtils.readFully(inputStream, record);
        count(readNow);
        if (readNow != recordSize) {
            return null;
        }

        return record;
    }

    private void readGlobalPaxHeaders() throws IOException {
        globalPaxHeaders = parsePaxHeaders(this, globalSparseHeaders);
        getNextEntry(); // Get the actual file entry
    }

    /**
     * For PAX Format 0.0, the sparse headers(GNU.sparse.offset and GNU.sparse.numbytes)
     * may appear multi times, and they look like:
     *
     * GNU.sparse.size=size
     * GNU.sparse.numblocks=numblocks
     * repeat numblocks times
     *   GNU.sparse.offset=offset
     *   GNU.sparse.numbytes=numbytes
     * end repeat
     *
     *
     * For PAX Format 0.1, the sparse headers are stored in a single variable : GNU.sparse.map
     *
     * GNU.sparse.map
     *    Map of non-null data chunks. It is a string consisting of comma-separated values "offset,size[,offset-1,size-1...]"
     *
     *
     * For PAX Format 1.X:
     * The sparse map itself is stored in the file data block, preceding the actual file data.
     * It consists of a series of decimal numbers delimited by newlines. The map is padded with nulls to the nearest block boundary.
     * The first number gives the number of entries in the map. Following are map entries, each one consisting of two numbers
     * giving the offset and size of the data block it describes.
     * @throws IOException
     */
    private void paxHeaders() throws IOException{
        List<TarArchiveStructSparse> sparseHeaders = new ArrayList<>();
        final Map<String, String> headers = parsePaxHeaders(this, sparseHeaders);

        // for 0.1 PAX Headers
        if (headers.containsKey("GNU.sparse.map")) {
            sparseHeaders = parsePAX01SparseHeaders(headers.get("GNU.sparse.map"));
        }
        getNextEntry(); // Get the actual file entry
        if (currEntry == null) {
            throw new IOException("premature end of tar archive. Didn't find any entry after PAX header.");
        }
        applyPaxHeadersToCurrentEntry(headers, sparseHeaders);

        // for 1.0 PAX Format, the sparse map is stored in the file data block
        if (currEntry.isPaxGNU1XSparse()) {
            sparseHeaders = parsePAX1XSparseHeaders();
            currEntry.setSparseHeaders(sparseHeaders);
        }

        // sparse headers are all done reading, we need to build
        // sparse input streams using these sparse headers
        buildSparseInputStreams();
    }

    /**
     * For PAX Format 0.1, the sparse headers are stored in a single variable : GNU.sparse.map
     * GNU.sparse.map
     *    Map of non-null data chunks. It is a string consisting of comma-separated values "offset,size[,offset-1,size-1...]"
     *
     * @param sparseMap the sparse map string consisting of comma-separated values "offset,size[,offset-1,size-1...]"
     * @return sparse headers parsed from sparse map
     * @throws IOException
     */
    private List<TarArchiveStructSparse> parsePAX01SparseHeaders(String sparseMap) throws IOException {
        List<TarArchiveStructSparse> sparseHeaders = new ArrayList<>();
        String[] sparseHeaderStrings = sparseMap.split(",");

        for (int i = 0; i < sparseHeaderStrings.length;i += 2) {
            long sparseOffset = Long.parseLong(sparseHeaderStrings[i]);
            long sparseNumbytes = Long.parseLong(sparseHeaderStrings[i + 1]);
            sparseHeaders.add(new TarArchiveStructSparse(sparseOffset, sparseNumbytes));
        }

        return sparseHeaders;
    }

    /**
     * For PAX Format 1.X:
     * The sparse map itself is stored in the file data block, preceding the actual file data.
     * It consists of a series of decimal numbers delimited by newlines. The map is padded with nulls to the nearest block boundary.
     * The first number gives the number of entries in the map. Following are map entries, each one consisting of two numbers
     * giving the offset and size of the data block it describes.
     * @return sparse headers
     * @throws IOException
     */
    private List<TarArchiveStructSparse> parsePAX1XSparseHeaders() throws IOException {
        // for 1.X PAX Headers
        List<TarArchiveStructSparse> sparseHeaders = new ArrayList<>();
        long bytesRead = 0;

        long[] readResult = readLineOfNumberForPax1X(inputStream);
        long sparseHeadersCount = readResult[0];
        bytesRead += readResult[1];
        while (sparseHeadersCount-- > 0) {
            readResult = readLineOfNumberForPax1X(inputStream);
            long sparseOffset = readResult[0];
            bytesRead += readResult[1];

            readResult = readLineOfNumberForPax1X(inputStream);
            long sparseNumbytes = readResult[0];
            bytesRead += readResult[1];
            sparseHeaders.add(new TarArchiveStructSparse(sparseOffset, sparseNumbytes));
        }

        // skip the rest of this record data
        long bytesToSkip = recordSize - bytesRead % recordSize;
        IOUtils.skip(inputStream, bytesToSkip);
        return sparseHeaders;
    }

    /**
     * For 1.X PAX Format, the sparse headers are stored in the file data block, preceding the actual file data.
     * It consists of a series of decimal numbers delimited by newlines.
     *
     * @param inputStream the input stream of the tar file
     * @return the decimal number delimited by '\n', and the bytes read from input stream
     * @throws IOException
     */
    private long[] readLineOfNumberForPax1X(InputStream inputStream) throws IOException {
        int number;
        long result = 0;
        long bytesRead = 0;

        while((number = inputStream.read()) != '\n') {
            bytesRead += 1;
            if(number == -1) {
                throw new IOException("Unexpected EOF when reading parse information of 1.X PAX format");
            }
            result = result * 10 + (number - '0');
        }
        bytesRead += 1;

        return new long[] {result, bytesRead};
    }

    /**
     * For PAX Format 0.0, the sparse headers(GNU.sparse.offset and GNU.sparse.numbytes)
     * may appear multi times, and they look like:
     *
     * GNU.sparse.size=size
     * GNU.sparse.numblocks=numblocks
     * repeat numblocks times
     *   GNU.sparse.offset=offset
     *   GNU.sparse.numbytes=numbytes
     * end repeat
     *
     * For PAX Format 0.1, the sparse headers are stored in a single variable : GNU.sparse.map
     *
     * GNU.sparse.map
     *    Map of non-null data chunks. It is a string consisting of comma-separated values "offset,size[,offset-1,size-1...]"
     *
     * @param inputstream inputstream to read keys and values
     * @param sparseHeaders used in PAX Format 0.0 &amp; 0.1, as it may appear multi times,
     *                      the sparse headers need to be stored in an array, not a map
     * @return map of PAX headers values found inside of the current (local or global) PAX headers tar entry.
     * @throws IOException
     */
    Map<String, String> parsePaxHeaders(final InputStream inputStream, List<TarArchiveStructSparse> sparseHeaders)
        throws IOException {
        final Map<String, String> headers = new HashMap<>(globalPaxHeaders);
        Long offset = null;
        // Format is "length keyword=value\n";
        while(true) { // get length
            int ch;
            int len = 0;
            int read = 0;
            while((ch = inputStream.read()) != -1) {
                read++;
                if (ch == '\n') { // blank line in header
                    break;
                } else if (ch == ' '){ // End of length string
                    // Get keyword
                    final ByteArrayOutputStream coll = new ByteArrayOutputStream();
                    while((ch = inputStream.read()) != -1) {
                        read++;
                        if (ch == '='){ // end of keyword
                            final String keyword = coll.toString(CharsetNames.UTF_8);
                            // Get rest of entry
                            final int restLen = len - read;
                            if (restLen == 1) { // only NL
                                headers.remove(keyword);
                            } else {
                                final byte[] rest = new byte[restLen];
                                final int got = IOUtils.readFully(inputStream, rest);
                                if (got != restLen) {
                                    throw new IOException("Failed to read "
                                                          + "Paxheader. Expected "
                                                          + restLen
                                                          + " bytes, read "
                                                          + got);
                                }
                                // Drop trailing NL
                                final String value = new String(rest, 0,
                                                          restLen - 1, CharsetNames.UTF_8);
                                headers.put(keyword, value);

                                // for 0.0 PAX Headers
                                if (keyword.equals("GNU.sparse.offset")) {
                                    if (offset != null) {
                                        // previous GNU.sparse.offset header but but no numBytes
                                        sparseHeaders.add(new TarArchiveStructSparse(offset, 0));
                                    }
                                    offset = Long.valueOf(value);
                                }

                                // for 0.0 PAX Headers
                                if (keyword.equals("GNU.sparse.numbytes")) {
                                    if (offset == null) {
                                        throw new IOException("Failed to read Paxheader." +
                                                "GNU.sparse.offset is expected before GNU.sparse.numbytes shows up.");
                                    }
                                    sparseHeaders.add(new TarArchiveStructSparse(offset, Long.parseLong(value)));
                                    offset = null;
                                }
                            }
                            break;
                        }
                        coll.write((byte) ch);
                    }
                    break; // Processed single header
                }

                // COMPRESS-530 : throw if we encounter a non-number while reading length
                if (ch < '0' || ch > '9') {
                    throw new IOException("Failed to read Paxheader. Encountered a non-number while reading length");
                }

                len *= 10;
                len += ch - '0';
            }
            if (ch == -1){ // EOF
                break;
            }
        }
        if (offset != null) {
            // offset but no numBytes
            sparseHeaders.add(new TarArchiveStructSparse(offset, 0));
        }
        return headers;
    }

    private void applyPaxHeadersToCurrentEntry(final Map<String, String> headers, final List<TarArchiveStructSparse> sparseHeaders) {
        currEntry.updateEntryFromPaxHeaders(headers);
        currEntry.setSparseHeaders(sparseHeaders);
    }

    /**
     * Adds the sparse chunks from the current entry to the sparse chunks,
     * including any additional sparse entries following the current entry.
     *
     * @throws IOException on error
     */
    private void readOldGNUSparse() throws IOException {
        if (currEntry.isExtended()) {
            TarArchiveSparseEntry entry;
            do {
                final byte[] headerBuf = getRecord();
                if (headerBuf == null) {
                    currEntry = null;
                    break;
                }
                entry = new TarArchiveSparseEntry(headerBuf);
                currEntry.getSparseHeaders().addAll(entry.getSparseHeaders());
            } while (entry.isExtended());
        }

        // sparse headers are all done reading, we need to build
        // sparse input streams using these sparse headers
        buildSparseInputStreams();
    }

    private boolean isDirectory() {
        return currEntry != null && currEntry.isDirectory();
    }

    /**
     * Returns the next Archive Entry in this Stream.
     *
     * @return the next entry,
     *         or {@code null} if there are no more entries
     * @throws IOException if the next entry could not be read
     */
    @Override
    public ArchiveEntry getNextEntry() throws IOException {
        return getNextTarEntry();
    }

    /**
     * Tries to read the next record rewinding the stream if it is not a EOF record.
     *
     * <p>This is meant to protect against cases where a tar
     * implementation has written only one EOF record when two are
     * expected.  Actually this won't help since a non-conforming
     * implementation likely won't fill full blocks consisting of - by
     * default - ten records either so we probably have already read
     * beyond the archive anyway.</p>
     */
    private void tryToConsumeSecondEOFRecord() throws IOException {
        boolean shouldReset = true;
        final boolean marked = inputStream.markSupported();
        if (marked) {
            inputStream.mark(recordSize);
        }
        try {
            shouldReset = !isEOFRecord(readRecord());
        } finally {
            if (shouldReset && marked) {
                pushedBackBytes(recordSize);
            	inputStream.reset();
            }
        }
    }

    /**
     * Reads bytes from the current tar archive entry.
     *
     * This method is aware of the boundaries of the current
     * entry in the archive and will deal with them as if they
     * were this stream's start and EOF.
     *
     * @param buf The buffer into which to place bytes read.
     * @param offset The offset at which to place bytes read.
     * @param numToRead The number of bytes to read.
     * @return The number of bytes read, or -1 at EOF.
     * @throws IOException on error
     */
    @Override
    public int read(final byte[] buf, final int offset, int numToRead) throws IOException {
        if (numToRead == 0) {
            return 0;
        }
    	int totalRead = 0;

        if (isAtEOF() || isDirectory()) {
            return -1;
        }

        if (currEntry == null) {
            throw new IllegalStateException("No current tar entry");
        }

        if (!currEntry.isSparse()) {
            if (entryOffset >= entrySize) {
                return -1;
            }
        } else {
            // for sparse entries, there are actually currEntry.getRealSize() bytes to read
            if (entryOffset >= currEntry.getRealSize()) {
                return -1;
            }
        }

        numToRead = Math.min(numToRead, available());

        if (currEntry.isSparse()) {
            // for sparse entries, we need to read them in another way
            totalRead = readSparse(buf, offset, numToRead);
        } else {
            totalRead = inputStream.read(buf, offset, numToRead);
        }

        if (totalRead == -1) {
            if (numToRead > 0) {
                throw new IOException("Truncated TAR archive");
            }
            setAtEOF(true);
        } else {
            count(totalRead);
            entryOffset += totalRead;
        }

        return totalRead;
    }

    /**
     * For sparse tar entries, there are many "holes"(consisting of all 0) in the file. Only the non-zero data is
     * stored in tar files, and they are stored separately. The structure of non-zero data is introduced by the
     * sparse headers using the offset, where a block of non-zero data starts, and numbytes, the length of the
     * non-zero data block.
     * When reading sparse entries, the actual data is read out with "holes" and non-zero data combined together
     * according to the sparse headers.
     *
     * @param buf The buffer into which to place bytes read.
     * @param offset The offset at which to place bytes read.
     * @param numToRead The number of bytes to read.
     * @return The number of bytes read, or -1 at EOF.
     * @throws IOException on error
     */
    private int readSparse(final byte[] buf, final int offset, int numToRead) throws IOException {
        // if there are no actual input streams, just read from the original input stream
        if (sparseInputStreams == null || sparseInputStreams.size() == 0) {
            return inputStream.read(buf, offset, numToRead);
        }

        if(currentSparseInputStreamIndex >= sparseInputStreams.size()) {
            return -1;
        }

        InputStream currentInputStream = sparseInputStreams.get(currentSparseInputStreamIndex);
        int readLen = currentInputStream.read(buf, offset, numToRead);

        // if the current input stream is the last input stream,
        // just return the number of bytes read from current input stream
        if (currentSparseInputStreamIndex == sparseInputStreams.size() - 1) {
            return readLen;
        }

        // if EOF of current input stream is meet, open a new input stream and recursively call read
        if (readLen == -1) {
            currentSparseInputStreamIndex++;
            return readSparse(buf, offset, numToRead);
        }

        // if the rest data of current input stream is not long enough, open a new input stream
        // and recursively call read
        if (readLen < numToRead) {
            currentSparseInputStreamIndex++;
            int readLenOfNext = readSparse(buf, offset + readLen, numToRead - readLen);
            if (readLenOfNext == -1) {
                return readLen;
            }

            return readLen + readLenOfNext;
        }

        // if the rest data of current input stream is enough(which means readLen == len), just return readLen
        return readLen;
    }

    /**
     * Whether this class is able to read the given entry.
     *
     * <p>May return false if the current entry is a sparse file.</p>
     */
    @Override
    public boolean canReadEntryData(final ArchiveEntry ae) {
        if (ae instanceof TarArchiveEntry) {
            final TarArchiveEntry te = (TarArchiveEntry) ae;
            return !te.isSparse();
        }
        return false;
    }

    /**
     * Get the current TAR Archive Entry that this input stream is processing
     *
     * @return The current Archive Entry
     */
    public TarArchiveEntry getCurrentEntry() {
        return currEntry;
    }

    protected final void setCurrentEntry(final TarArchiveEntry e) {
        currEntry = e;
    }

    protected final boolean isAtEOF() {
        return hasHitEOF;
    }

    protected final void setAtEOF(final boolean b) {
        hasHitEOF = b;
    }

    /**
     * This method is invoked once the end of the archive is hit, it
     * tries to consume the remaining bytes under the assumption that
     * the tool creating this archive has padded the last block.
     */
    private void consumeRemainderOfLastBlock() throws IOException {
        final long bytesReadOfLastBlock = getBytesRead() % blockSize;
        if (bytesReadOfLastBlock > 0) {
            final long skipped = IOUtils.skip(inputStream, blockSize - bytesReadOfLastBlock);
            count(skipped);
        }
    }

    /**
     * Checks if the signature matches what is expected for a tar file.
     *
     * @param signature
     *            the bytes to check
     * @param length
     *            the number of bytes to check
     * @return true, if this stream is a tar archive stream, false otherwise
     */
    public static boolean matches(final byte[] signature, final int length) {
        if (length < TarConstants.VERSION_OFFSET+TarConstants.VERSIONLEN) {
            return false;
        }

        if (ArchiveUtils.matchAsciiBuffer(TarConstants.MAGIC_POSIX,
                signature, TarConstants.MAGIC_OFFSET, TarConstants.MAGICLEN)
            &&
            ArchiveUtils.matchAsciiBuffer(TarConstants.VERSION_POSIX,
                signature, TarConstants.VERSION_OFFSET, TarConstants.VERSIONLEN)
                ){
            return true;
        }
        if (ArchiveUtils.matchAsciiBuffer(TarConstants.MAGIC_GNU,
                signature, TarConstants.MAGIC_OFFSET, TarConstants.MAGICLEN)
            &&
            (
             ArchiveUtils.matchAsciiBuffer(TarConstants.VERSION_GNU_SPACE,
                signature, TarConstants.VERSION_OFFSET, TarConstants.VERSIONLEN)
            ||
            ArchiveUtils.matchAsciiBuffer(TarConstants.VERSION_GNU_ZERO,
                signature, TarConstants.VERSION_OFFSET, TarConstants.VERSIONLEN)
            )
                ){
            return true;
        }
        // COMPRESS-107 - recognise Ant tar files
        return ArchiveUtils.matchAsciiBuffer(TarConstants.MAGIC_ANT,
                signature, TarConstants.MAGIC_OFFSET, TarConstants.MAGICLEN)
                &&
                ArchiveUtils.matchAsciiBuffer(TarConstants.VERSION_ANT,
                        signature, TarConstants.VERSION_OFFSET, TarConstants.VERSIONLEN);
    }

    /**
     * Build the input streams consisting of all-zero input streams and non-zero input streams.
     * When reading from the non-zero input streams, the data is actually read from the original input stream.
     * The size of each input stream is introduced by the sparse headers.
     *
     * NOTE : Some all-zero input streams and non-zero input streams have the size of 0. We DO NOT store the
     *        0 size input streams because they are meaningless.
     */
    private void buildSparseInputStreams() throws IOException {
        currentSparseInputStreamIndex = -1;
        sparseInputStreams = new ArrayList<>();

        final List<TarArchiveStructSparse> sparseHeaders = currEntry.getSparseHeaders();
        // sort the sparse headers in case they are written in wrong order
        if (sparseHeaders != null && sparseHeaders.size() > 1) {
            final Comparator<TarArchiveStructSparse> sparseHeaderComparator = new Comparator<TarArchiveStructSparse>() {
                @Override
                public int compare(final TarArchiveStructSparse p, final TarArchiveStructSparse q) {
                    Long pOffset = p.getOffset();
                    Long qOffset = q.getOffset();
                    return pOffset.compareTo(qOffset);
                }
            };
            Collections.sort(sparseHeaders, sparseHeaderComparator);
        }

        if (sparseHeaders != null) {
            // Stream doesn't need to be closed at all as it doesn't use any resources
            final InputStream zeroInputStream = new TarArchiveSparseZeroInputStream(); //NOSONAR
            long offset = 0;
            for (TarArchiveStructSparse sparseHeader : sparseHeaders) {
                if (sparseHeader.getOffset() == 0 && sparseHeader.getNumbytes() == 0) {
                    break;
                }

                if ((sparseHeader.getOffset() - offset) < 0) {
                    throw new IOException("Corrupted struct sparse detected");
                }

                // only store the input streams with non-zero size
                if ((sparseHeader.getOffset() - offset) > 0) {
                    sparseInputStreams.add(new BoundedInputStream(zeroInputStream, sparseHeader.getOffset() - offset));
                }

                // only store the input streams with non-zero size
                if (sparseHeader.getNumbytes() > 0) {
                    sparseInputStreams.add(new BoundedInputStream(inputStream, sparseHeader.getNumbytes()));
                }

                offset = sparseHeader.getOffset() + sparseHeader.getNumbytes();
            }
        }

        if (sparseInputStreams.size() > 0) {
            currentSparseInputStreamIndex = 0;
        }
    }

    /**
     * This is an inputstream that always return 0,
     * this is used when reading the "holes" of a sparse file
     */
    private static class TarArchiveSparseZeroInputStream extends InputStream {
        /**
         * Just return 0
         * @return
         * @throws IOException
         */
        @Override
        public int read() throws IOException {
            return 0;
        }

        /**
         * these's nothing need to do when skipping
         *
         * @param n bytes to skip
         * @return bytes actually skipped
         */
        @Override
        public long skip(final long n) {
            return n;
        }
    }
}
