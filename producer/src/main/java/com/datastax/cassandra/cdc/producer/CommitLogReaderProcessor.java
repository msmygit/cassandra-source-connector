package com.datastax.cassandra.cdc.producer;

import org.apache.cassandra.db.commitlog.BlockingCommitLogReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.*;

/**
 * Manage BlockingCommitLogReaders to read synced data as soon as possible.
 *
 */
@Singleton
public class CommitLogReaderProcessor extends AbstractProcessor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(CommitLogReaderProcessor.class);
    private static final String NAME = "CommitLogReader Processor";

    // synced position
    private volatile long syncedSegmentId = -1;
    private volatile int  syncedPosition = -1;
    private volatile BlockingCommitLogReader blockingCommitLogReader = null;
    private CountDownLatch syncedPositionLatch = new CountDownLatch(1);

    private final PriorityBlockingQueue<File> commitLogQueue = new PriorityBlockingQueue<>(128, CommitLogUtil::compareCommitLogs);

    private final ChangeEventQueue changeEventQueue;
    private final CommitLogReadHandlerImpl commitLogReadHandler;
    private final FileOffsetWriter fileOffsetWriter;
    private final CommitLogTransfer commitLogTransfer;

    public CommitLogReaderProcessor(CassandraConnectorConfiguration config,
                                    CommitLogReadHandlerImpl commitLogReadHandler,
                                    ChangeEventQueue changeEventQueue,
                                    FileOffsetWriter fileOffsetWriter,
                                    CommitLogTransfer commitLogTransfer) {
        super(NAME, 0);
        this.changeEventQueue = changeEventQueue;
        this.commitLogReadHandler = commitLogReadHandler;
        this.fileOffsetWriter = fileOffsetWriter;
        this.commitLogTransfer = commitLogTransfer;
    }

    public void submitCommitLog(File file)  {
        File cdcIdxFile = new File(file.getParent(), file.getName().replace(".log","_cdc.idx"));
        if (cdcIdxFile.exists()) {
            // you can have old _cdc.idx file, ignore it
            long seg = CommitLogUtil.extractTimestamp(file.getName());
            int pos = 0;
            if (seg >= this.syncedSegmentId) {
                try {
                    List<String> lines = Files.readAllLines(cdcIdxFile.toPath(), Charset.forName("UTF-8"));
                    pos = Integer.parseInt(lines.get(0));
                    boolean completed = false;
                    try {
                        if("COMPLETED".equals(lines.get(1))) {
                            completed = true;
                        }
                    } catch(Exception ex) {
                    }
                    logger.debug("file={} segment={} position={} completed={}", file.getName(), seg, pos, completed);
                    assert seg > this.syncedSegmentId || pos > this.syncedPosition : "Unexpected synced position " + seg + ":" +pos;

                    if (blockingCommitLogReader != null) {
                        if (blockingCommitLogReader.segmentId < seg) {
                            blockingCommitLogReader.release(); // make the reader non-blocking
                        } else if (blockingCommitLogReader.segmentId == seg) {
                            blockingCommitLogReader.updateSyncedPosition(pos);  // move the reader ahead
                        }
                    }
                    this.syncedSegmentId = seg;
                    this.syncedPosition = pos;
                    // unlock the processing of commitlogs
                    if (syncedPositionLatch.getCount() > 0)
                        syncedPositionLatch.countDown();
                } catch(IOException ex) {
                    logger.warn("error while reading file=" + file.getName(), ex);
                }
            } else {
                logger.debug("Ignoring old synced position from file={} pos={}", file.getName(), pos);
            }
        } else {
            this.commitLogQueue.add(file);
        }
    }

    public void awaitSyncedPosition() throws InterruptedException {
        syncedPositionLatch.await();
    }

    @Override
    public void process() throws InterruptedException {
        assert this.syncedSegmentId >= this.fileOffsetWriter.position().segmentId : "offset segment is beyond the synced segment";
        assert this.syncedSegmentId > this.fileOffsetWriter.position().segmentId || this.syncedPosition > this.fileOffsetWriter.position().position : "offset is beyond the synced position";
        File file = null;
        while(true) {
            file = this.commitLogQueue.take();
            long seg = CommitLogUtil.extractTimestamp(file.getName());
            // ignore file before the last write offset
            if (seg < this.fileOffsetWriter.position().segmentId) {
                logger.debug("Ignore file={} processed position={}", file, this.fileOffsetWriter.position());
                continue;
            }

            logger.debug("processing file={} blocking={} synced position={}:{}",
                    file.getName(), seg == this.syncedSegmentId, this.syncedSegmentId, this.syncedPosition);
            assert seg <= this.syncedSegmentId : "reading a commitlog ahead the last synced commitlog";

            this.blockingCommitLogReader = new BlockingCommitLogReader(file,
                    seg == this.syncedSegmentId ? new Semaphore(this.syncedPosition) : null);
            try {
                blockingCommitLogReader.readCommitLogSegment(commitLogReadHandler, blockingCommitLogReader.file, fileOffsetWriter.position(), false);
                changeEventQueue.enqueue(new EOFEvent(blockingCommitLogReader.file, true));
                logger.debug("Successfully processed commitlog {}", blockingCommitLogReader.file.getName());
                //commitLogTransfer.onSuccessTransfer(blockingCommitLogReader.file);
            } catch(Exception e) {
                logger.warn("Failed to read commitlog file="+file.getName(), e);
                changeEventQueue.enqueue(new EOFEvent(blockingCommitLogReader.file, false));
                //commitLogTransfer.onErrorTransfer(blockingCommitLogReader.file);
            }
        }
    }

    /**
     * Override destroy to clean up resources after stopping the processor
     */
    @Override
    public void close() {
    }
}