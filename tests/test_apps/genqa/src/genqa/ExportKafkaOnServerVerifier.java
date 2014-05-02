/* This file is part of VoltDB.
 * Copyright (C) 2008-2014 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */
package genqa;

import genqa.ExportOnServerVerifier.ValidationErr;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.voltdb.VoltDB;

import java.util.Properties;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
import org.I0Itec.zkclient.ZkClient;
import org.voltdb.iv2.TxnEgo;

/**
 * This verifier connects to kafka zk and consumes messsages from 2 topics
 * 1. Table data related
 * 2. End of Data topic to which client write when all export data is pushed.
 *
 * Each row is verified and row count is matched at the end.
 *
 */
public class ExportKafkaOnServerVerifier {

    public static long VALIDATION_REPORT_INTERVAL = 1000;

    private VoltKafkaConsumerConfig m_kafkaConfig;
    private long expectedRows = 0;
    private final AtomicLong consumedRows = new AtomicLong(0);

    private static class VoltKafkaConsumerConfig {
        final String m_zkhost;
        final ConsumerConfig consumerConfig;
        final ConsumerConfig doneConsumerConfig;
        final ConsumerConnector consumer;
        final ConsumerConnector doneConsumer;
        private final String m_groupId;

        VoltKafkaConsumerConfig(String zkhost) {
            m_zkhost = zkhost;
            //Use random groupId and we clean it up from zk at the end.
            m_groupId = String.valueOf(System.currentTimeMillis());
            Properties props = new Properties();
            props.put("zookeeper.connect", m_zkhost);
            props.put("group.id", m_groupId);
            props.put("auto.commit.interval.ms", "1000");
            props.put("auto.commit.enable", "true");
            props.put("fetch.size", "10240"); // Use smaller size than default.
            props.put("auto.offset.reset", "smallest");
            props.put("queuedchunks.max", "1000");
            props.put("backoff.increment.ms", "1500");
            props.put("consumer.timeout.ms", "120000");

            consumerConfig = new ConsumerConfig(props);
            consumer = kafka.consumer.Consumer.createJavaConsumerConnector(consumerConfig);

            //Certain properties in done consumer are different.
            props.remove("consumer.timeout.ms");
            props.put("group.id", m_groupId + "-done");
            //Use higher autocommit interval as we read only 1 row and then real consumer follows for long time.
            props.put("auto.commit.interval.ms", "10000");
            doneConsumerConfig = new ConsumerConfig(props);
            doneConsumer = kafka.consumer.Consumer.createJavaConsumerConnector(doneConsumerConfig);
        }

        public void stop() {
            doneConsumer.commitOffsets();
            doneConsumer.shutdown();
            consumer.commitOffsets();
            consumer.shutdown();
            tryCleanupZookeeper();
        }

        void tryCleanupZookeeper() {
            try {
                ZkClient zk = new ZkClient(m_zkhost);
                String dir = "/consumers/" + m_groupId;
                zk.deleteRecursive(dir);
                dir = "/consumers/" + m_groupId + "-done";
                zk.deleteRecursive(dir);
                zk.close();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

    }

    ExportKafkaOnServerVerifier() {
    }

    boolean verifySetup(String[] args) throws Exception {

        //Zookeeper
        m_zookeeper = args[0];
        System.out.println("Zookeeper is: " + m_zookeeper);
        //Topic
        m_topic = args[1]; //"voltdbexportEXPORT_PARTITIONED_TABLE";

        boolean skinny = false;
        if (args.length > 3 && args[3] != null && !args[3].trim().isEmpty()) {
            skinny = Boolean.parseBoolean(args[3].trim().toLowerCase());
        }

        m_kafkaConfig = new VoltKafkaConsumerConfig(m_zookeeper);

        return skinny;
    }

    /**
     * Verifies the fat version of the exported table. By fat it means that it contains many
     * columns of multiple types
     *
     * @throws Exception
     */
    void verifyFat() throws Exception
    {
        createAndConsumeKafkaStreams(m_topic, m_doneTopic, false);
    }

    /**
     * Verifies the skinny version of the exported table. By skinny it means that it contains the
     * bare minimum of columns (just enough for the purpose of transaction verification)
     *
     * @throws Exception
     */
    void verifySkinny() throws Exception
    {
        createAndConsumeKafkaStreams(m_topic, m_doneTopic, true);
    }

    public class ExportConsumer implements Runnable {

        private final KafkaStream m_stream;
        private final boolean m_doneStream;
        private final CountDownLatch m_cdl;
        private final boolean m_skinny;

        public ExportConsumer(KafkaStream a_stream, boolean doneStream, boolean skinny, CountDownLatch cdl) {
            m_stream = a_stream;
            m_doneStream = doneStream;
            m_skinny = skinny;
            m_cdl = cdl;
        }

        @Override
        public void run() {
            try {
                ConsumerIterator<byte[], byte[]> it = m_stream.iterator();
                int ttlVerified = 0;
                while (it.hasNext()) {
                    byte msg[] = it.next().message();
                    String smsg = new String(msg);
                    String row[] = ExportOnServerVerifier.RoughCSVTokenizer.tokenize(smsg);
                    try {
                        if (m_doneStream) {
                            System.out.println("EOS Consumed: " + smsg + " Expected Rows: " + row[6]);
                            expectedRows = Long.parseLong(row[6]);
                            break;
                        }
                        consumedRows.incrementAndGet();
                        if (m_skinny) {
                            if (expectedRows != 0 && consumedRows.get() >= expectedRows) {
                                break;
                            }
                        }
                        ExportOnServerVerifier.ValidationErr err = ExportOnServerVerifier.verifyRow(row);
                        if (err != null) {
                            System.out.println("ERROR in validation: " + err.toString());
                        }
                        if (++ttlVerified % VALIDATION_REPORT_INTERVAL == 0) {
                            System.out.println("Verified " + ttlVerified + " rows.");
                        }

                        Integer partition = Integer.parseInt(row[3].trim());
                        Long rowTxnId = Long.parseLong(row[6].trim());

                        if (TxnEgo.getPartitionId(rowTxnId) != partition) {
                            System.err.println("ERROR: mismatched exported partition for txid " + rowTxnId +
                                    ", tx says it belongs to " + TxnEgo.getPartitionId(rowTxnId) +
                                    ", while export record says " + partition);
                        }
                        if (expectedRows != 0 && consumedRows.get() >= expectedRows) {
                            break;
                        }
                    } catch (ExportOnServerVerifier.ValidationErr ex) {
                        System.out.println("Validation ERROR: " + ex);
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                if (m_cdl != null) {
                    m_cdl.countDown();
                }
            }
        }
    }

    //Submit consumer tasks to executor and wait for EOS message then continue on.
    void createAndConsumeKafkaStreams(String topic, String doneTopic, boolean skinny) throws Exception {
        List<Future<Long>> doneFutures = new ArrayList<>();

        Map<String, Integer> topicCountMap = new HashMap<>();
        topicCountMap.put(topic, 1);
        Map<String, List<KafkaStream<byte[], byte[]>>> consumerMap = m_kafkaConfig.consumer.createMessageStreams(topicCountMap);
        List<KafkaStream<byte[], byte[]>> streams = consumerMap.get(topic);

        ExecutorService executor = Executors.newFixedThreadPool(streams.size());

        // now launch all the threads
        CountDownLatch consumersLatch = new CountDownLatch(streams.size());
        for (final KafkaStream stream : streams) {
            System.out.println("Creating consumer for " + topic);
            ExportConsumer consumer = new ExportConsumer(stream, false, skinny, consumersLatch);
            executor.submit(consumer);
        }

        Map<String, Integer> topicDoneCountMap = new HashMap<String, Integer>();
        topicDoneCountMap.put(doneTopic, 1);
        Map<String, List<KafkaStream<byte[], byte[]>>> doneConsumerMap = m_kafkaConfig.doneConsumer.createMessageStreams(topicDoneCountMap);

        List<KafkaStream<byte[], byte[]>> doneStreams = doneConsumerMap.get(doneTopic);
        ExecutorService executor2 = Executors.newFixedThreadPool(doneStreams.size());
        CompletionService<Long> ecs
                 = new ExecutorCompletionService<>(executor2);

        // now launch all the threads
        for (final KafkaStream stream : doneStreams) {
            System.out.println("Creating consumer for " + doneTopic);
            ExportConsumer consumer = new ExportConsumer(stream, true, true, null);
            Future<Long> f = ecs.submit(consumer, new Long(0));
            doneFutures.add(f);
        }

        System.out.println("All Consumer Creation Done...Waiting for EOS");
        // Now wait for any executorservice2 completion.
        ecs.take().get();
        System.out.println("Done Consumer Saw EOS...Cancelling rest of the done consumers.");
        for (Future<Long> f : doneFutures) {
            f.cancel(true);
        }
        //Wait for all consumers to consume and timeout.
        consumersLatch.await();
        m_kafkaConfig.stop();
        executor.shutdownNow();
        executor.awaitTermination(1, TimeUnit.DAYS);
        System.out.println("Seen Rows: " + consumedRows.get() + " Expected: " + expectedRows);
        if (consumedRows.get() != expectedRows) {
            System.out.println("ERROR: Exported row count does not match consumed rows.");
        }
        //For shutdown hook to not stop twice.
        m_kafkaConfig = null;
    }

    String m_topic = null;
    final String m_doneTopic = "voltdbexportEXPORT_DONE_TABLE";
    String m_zookeeper = null;

    static {
        VoltDB.setDefaultTimezone();
    }

    public void stopConsumer() {
        if (m_kafkaConfig != null) {
            m_kafkaConfig.stop();
        }
    }

    public static void main(String[] args) throws Exception {
        final ExportKafkaOnServerVerifier verifier = new ExportKafkaOnServerVerifier();
        try
        {
            Runtime.getRuntime().addShutdownHook(
                    new Thread() {
                        @Override
                        public void run() {
                            System.out.println("Shuttind Down...");
                            verifier.stopConsumer();
                        }
                    });

            boolean skinny = verifier.verifySetup(args);

            if (skinny) {
                verifier.verifySkinny();
            } else {
                verifier.verifyFat();
            }
        }
        catch(IOException e) {
            e.printStackTrace(System.err);
            System.exit(-1);
        }
        catch (ValidationErr e ) {
            System.err.println("Validation error: " + e.toString());
            System.exit(-1);
        }
        System.exit(0);
    }

}