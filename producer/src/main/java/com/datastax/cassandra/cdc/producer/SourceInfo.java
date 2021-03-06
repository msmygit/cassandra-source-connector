/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.datastax.cassandra.cdc.producer;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata about the source of the change event
 */
@Getter
@AllArgsConstructor
@ToString
@EqualsAndHashCode
public class SourceInfo  {
    public final String cluster;
    public final UUID nodeId;
    public final CommitLogPosition commitLogPosition;
    public final String keyspace;
    public final String table;
    public final Instant timestamp;
}
