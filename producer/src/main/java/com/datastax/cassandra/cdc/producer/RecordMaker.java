/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.datastax.cassandra.cdc.producer;

import com.datastax.cassandra.cdc.Operation;
import com.datastax.cassandra.cdc.producer.exceptions.CassandraConnectorTaskException;
import io.debezium.function.BlockingConsumer;
import org.apache.cassandra.db.commitlog.CommitLogPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.time.Instant;
import java.util.UUID;

/**
 * Responsible for generating ChangeRecord and/or TombstoneRecord for create/update/delete events, as well as EOF events.
 */
@Singleton
public class RecordMaker {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecordMaker.class);
    private final boolean emitTombstoneOnDelete;

    public RecordMaker(CassandraConnectorConfiguration config) {
        this.emitTombstoneOnDelete = config.tombstonesOnDelete;
    }

    public void insert(String cluster, UUID node, CommitLogPosition offsetPosition, KeyspaceTable keyspaceTable, boolean snapshot,
                       Instant tsMicro, RowData data,
                       boolean markOffset, BlockingConsumer<Record> consumer) {
        createRecord(cluster, node, offsetPosition, keyspaceTable, snapshot, tsMicro,
                data, markOffset, consumer, Operation.INSERT);
    }

    public void update(String cluster, UUID node, CommitLogPosition offsetPosition, KeyspaceTable keyspaceTable, boolean snapshot,
                       Instant tsMicro, RowData data,
                       boolean markOffset, BlockingConsumer<Record> consumer) {
        createRecord(cluster, node, offsetPosition, keyspaceTable, snapshot, tsMicro,
                data, markOffset, consumer, Operation.UPDATE);
    }

    public void delete(String cluster, UUID node, CommitLogPosition offsetPosition, KeyspaceTable keyspaceTable, boolean snapshot,
                       Instant tsMicro, RowData data,
                       boolean markOffset, BlockingConsumer<Record> consumer) {
        createRecord(cluster, node, offsetPosition, keyspaceTable, snapshot, tsMicro,
                data, markOffset, consumer, Operation.DELETE);
    }

    private void createRecord(String cluster, UUID node, CommitLogPosition offsetPosition, KeyspaceTable keyspaceTable, boolean snapshot,
                              Instant tsMicro, RowData data,
                              boolean markOffset, BlockingConsumer<Record> consumer, Operation operation) {
        // TODO: filter columns
        RowData filteredData;
        switch (operation) {
            case INSERT:
            case UPDATE:
                filteredData = data;
                break;
            case DELETE:
            default:
                filteredData = data;
                break;
        }

        SourceInfo source = new SourceInfo(cluster, node, offsetPosition, keyspaceTable, tsMicro);
        ChangeRecord record = new ChangeRecord(source, filteredData, operation, markOffset);
        try {
            consumer.accept(record);
        }
        catch (InterruptedException e) {
            LOGGER.error("Interruption while enqueuing Change Event {}", record.toString());
            throw new CassandraConnectorTaskException("Enqueuing has been interrupted: ", e);
        }

        if (operation == Operation.DELETE && emitTombstoneOnDelete) {
            // generate kafka tombstone event
            TombstoneRecord tombstoneRecord = new TombstoneRecord(source, filteredData);
            try {
                consumer.accept(tombstoneRecord);
            }
            catch (Exception e) {
                LOGGER.error("Interruption while enqueuing Tombstone Event {}", record.toString());
                throw new CassandraConnectorTaskException("Enqueuing has been interrupted: ", e);
            }
        }
    }

}