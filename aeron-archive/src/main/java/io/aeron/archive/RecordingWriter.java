/*
 * Copyright 2014-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.archive;

import io.aeron.archive.codecs.RecordingDescriptorDecoder;
import io.aeron.logbuffer.FrameDescriptor;
import io.aeron.logbuffer.Header;
import io.aeron.logbuffer.RawBlockHandler;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.BitUtil;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.LangUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;

import static io.aeron.archive.Archive.segmentFileName;
import static io.aeron.archive.Catalog.wrapDescriptorDecoder;

/**
 * Responsible for writing out a recording into the file system. A recording has descriptor file and a set of data files
 * written into the archive folder.
 * <p>
 * Design note: While this class is notionally closely related to the {@link RecordingSession} it is separated from it
 * for the following reasons:
 * <ul>
 * <li> Easier testing and in particular simplified re-use in testing. </li>
 * <li> Isolation of an external relationship, namely the FS</li>
 * <li> While a {@link RecordingWriter} is part of a {@link RecordingSession}, a session may transition without actually
 * creating a {@link RecordingWriter}.</li>
 * </ul>
 */
class RecordingWriter implements AutoCloseable, RawBlockHandler
{
    private static final int NULL_SEGMENT_POSITION = -1;

    private final boolean forceWrites;
    private final boolean forceMetadata;
    private final long recordingId;

    private final FileChannel archiveDirChannel;
    private final File archiveDir;
    private final AtomicCounter recordedPosition;
    private final int segmentFileLength;
    private final long startPosition;

    /**
     * Index is in the range 0:segmentFileLength, except before the first block for this image is received indicated
     * by NULL_SEGMENT_POSITION
     */
    private int segmentPosition = NULL_SEGMENT_POSITION;
    private int segmentIndex = 0;
    private FileChannel recordingFileChannel;

    private boolean isClosed = false;

    RecordingWriter(
        final Archive.Context context,
        final FileChannel archiveDirChannel,
        final UnsafeBuffer descriptorBuffer,
        final AtomicCounter recordedPosition)
    {
        this.recordedPosition = recordedPosition;
        final RecordingDescriptorDecoder descriptorDecoder = new RecordingDescriptorDecoder();
        wrapDescriptorDecoder(descriptorDecoder, descriptorBuffer);

        final int termBufferLength = descriptorDecoder.termBufferLength();

        this.archiveDirChannel = archiveDirChannel;
        archiveDir = context.archiveDir();
        segmentFileLength = Math.max(context.segmentFileLength(), termBufferLength);
        forceWrites = context.fileSyncLevel() > 0;
        forceMetadata = context.fileSyncLevel() > 1;

        recordingId = descriptorDecoder.recordingId();
        startPosition = descriptorDecoder.startPosition();
        recordedPosition.setOrdered(startPosition);

        final int termsMask = (segmentFileLength / termBufferLength) - 1;
        if (((termsMask + 1) & termsMask) != 0)
        {
            throw new IllegalArgumentException(
                "It is assumed the termBufferLength is a power of 2, and that the number of terms" +
                    "in a file is also a power of 2");
        }
    }

    public void onBlock(
        final FileChannel fileChannel,
        final long fileOffset,
        final UnsafeBuffer termBuffer,
        final int termOffset,
        final int blockLength,
        final int sessionId,
        final int termId)
    {
        try
        {
            if (Catalog.NULL_POSITION == segmentPosition)
            {
                onFirstWrite(termOffset);
            }

            if (segmentFileLength == segmentPosition)
            {
                onFileRollOver();
            }

            long bytesWritten = 0;
            do
            {
                bytesWritten += transferTo(fileChannel, fileOffset + bytesWritten, blockLength - bytesWritten);
            }
            while (bytesWritten < blockLength);

            if (forceWrites)
            {
                forceData(recordingFileChannel, forceMetadata);
            }

            afterWrite(blockLength);
        }
        catch (final ClosedByInterruptException ex)
        {
            Thread.interrupted();
            close();
            throw new IllegalStateException("Image file channel has been closed by interrupt, recording aborted.", ex);
        }
        catch (final Exception ex)
        {
            close();
            LangUtil.rethrowUnchecked(ex);
        }
    }

    public void close()
    {
        if (isClosed)
        {
            return;
        }

        isClosed = true;
        CloseHelper.close(recordingFileChannel);
    }

    /**
     * Convenience method for testing purposes only.
     */
    void writeFragment(final DirectBuffer buffer, final Header header)
    {
        final int termOffset = header.termOffset();
        final int frameLength = header.frameLength();
        final int alignedLength = BitUtil.align(frameLength, FrameDescriptor.FRAME_ALIGNMENT);

        try
        {
            if (Catalog.NULL_POSITION == segmentPosition)
            {
                onFirstWrite(termOffset);
            }

            if (segmentFileLength == segmentPosition)
            {
                onFileRollOver();
            }

            final ByteBuffer src = buffer.byteBuffer().duplicate();
            src.position(termOffset).limit(termOffset + frameLength);
            final int written = writeData(src, segmentPosition, recordingFileChannel);
            recordingFileChannel.position(segmentPosition + alignedLength);

            if (written != frameLength)
            {
                throw new IllegalStateException();
            }

            if (forceWrites)
            {
                forceData(recordingFileChannel, forceMetadata);
            }

            afterWrite(alignedLength);
        }
        catch (final Exception ex)
        {
            close();
            LangUtil.rethrowUnchecked(ex);
        }
    }

    long recordingId()
    {
        return recordingId;
    }

    int segmentFileLength()
    {
        return segmentFileLength;
    }

    long startPosition()
    {
        return startPosition;
    }

    long recordedPosition()
    {
        return recordedPosition.getWeak();
    }

    private int writeData(final ByteBuffer buffer, final int position, final FileChannel fileChannel)
        throws IOException
    {
        return fileChannel.write(buffer, position);
    }

    // extend for testing
    long transferTo(final FileChannel fromFileChannel, final long position, final long count)
        throws IOException
    {
        return fromFileChannel.transferTo(position, count, recordingFileChannel);
    }

    // extend for testing
    void newRecordingSegmentFile()
    {
        final File file = new File(archiveDir, segmentFileName(recordingId, segmentIndex));

        RandomAccessFile recordingFile = null;
        try
        {
            recordingFile = new RandomAccessFile(file, "rw");
            recordingFile.setLength(segmentFileLength + DataHeaderFlyweight.HEADER_LENGTH);
            recordingFileChannel = recordingFile.getChannel();
            if (forceWrites && null != archiveDirChannel)
            {
                forceData(archiveDirChannel, forceMetadata);
            }
        }
        catch (final IOException ex)
        {
            CloseHelper.quietClose(recordingFile);
            close();
            LangUtil.rethrowUnchecked(ex);
        }
    }

    // extend for testing
    void forceData(final FileChannel fileChannel, final boolean forceMetadata) throws IOException
    {
        fileChannel.force(forceMetadata);
    }

    boolean isClosed()
    {
        return isClosed;
    }

    private void onFileRollOver()
    {
        CloseHelper.close(recordingFileChannel);
        segmentPosition = 0;
        segmentIndex++;

        newRecordingSegmentFile();
    }

    private void onFirstWrite(final int termOffset) throws IOException
    {
        segmentPosition = termOffset;
        newRecordingSegmentFile();

        if (segmentPosition != 0)
        {
            recordingFileChannel.position(segmentPosition);
        }
    }

    private void afterWrite(final int blockLength)
    {
        segmentPosition += blockLength;
        recordedPosition.addOrdered(blockLength);
    }
}
