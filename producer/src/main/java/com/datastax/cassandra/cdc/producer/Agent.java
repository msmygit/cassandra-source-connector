package com.datastax.cassandra.cdc.producer;

import lombok.extern.slf4j.Slf4j;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.commitlog.CommitLogReader;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class Agent {
    public static void premain(String agentArgs, Instrumentation inst) {
        log.info("[Agent] In premain method");
        try {
            main(agentArgs, inst);
        } catch(Exception e) {
            log.error("error:", e);
            System.exit(-1);
        }
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        log.info("[Agent] In agentmain method");
        try {
            main(agentArgs, inst);
        } catch(Exception e) {
            log.error("error:", e);
            System.exit(-1);
        }
    }

    static void main(String agentArgs, Instrumentation inst) throws Exception {
        log.info("Starting CDC producer agent");
        DatabaseDescriptor.daemonInitialization();

        OffsetFileWriter offsetFileWriter = new OffsetFileWriter();
        PulsarMutationSender pulsarMutationSender = new PulsarMutationSender(offsetFileWriter);
        CommitLogReadHandlerImpl commitLogReadHandler = new CommitLogReadHandlerImpl(offsetFileWriter, pulsarMutationSender);
        CommitLogTransfer commitLogTransfer = new BlackHoleCommitLogTransfer();
        CommitLogReaderProcessor commitLogReaderProcessor = new CommitLogReaderProcessor(commitLogReadHandler, offsetFileWriter, commitLogTransfer);
        CommitLogProcessor commitLogProcessor = new CommitLogProcessor(commitLogTransfer, offsetFileWriter, commitLogReaderProcessor);

        // detect commitlogs file and submit new/modified files to the commitLogReader
        ExecutorService commitLogExecutor = Executors.newSingleThreadExecutor();
        commitLogExecutor.submit(() -> {
            try {
                commitLogProcessor.initialize();
                commitLogProcessor.start();
            } catch(Exception e) {
                log.error("commitLogProcessor error:", e);
            }
        });

        ExecutorService commitLogReaderExecutor = Executors.newSingleThreadExecutor();
        commitLogReaderExecutor.submit(() -> {
            try {
                // wait for the synced position
                commitLogReaderProcessor.awaitSyncedPosition();

                // continuously read commitlogs
                commitLogReaderProcessor.initialize();
                commitLogReaderProcessor.start();
            } catch(Exception e) {
                log.error("commitLogReaderProcessor error:", e);
            }
        });

        log.info("CDC producer agent started");
    }
}