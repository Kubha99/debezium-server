/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.server.kinesis;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.DebeziumException;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.DebeziumEngine.RecordCommitter;
import io.debezium.server.BaseChangeConsumer;
import io.debezium.server.CustomConsumerBuilder;
import io.debezium.util.Clock;
import io.debezium.util.Metronome;

import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.KinesisClientBuilder;
import software.amazon.awssdk.services.kinesis.model.KinesisException;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequest;
import software.amazon.awssdk.services.kinesis.model.PutRecordsRequestEntry;
import software.amazon.awssdk.services.kinesis.model.PutRecordsResponse;
import software.amazon.awssdk.services.kinesis.model.PutRecordsResultEntry;

/**
 * Implementation of the consumer that delivers the messages into Amazon Kinesis destination.
 *
 * @author Jiri Pechanec
 *
 */
@Named("kinesis")
@Dependent
public class KinesisChangeConsumer extends BaseChangeConsumer implements DebeziumEngine.ChangeConsumer<ChangeEvent<Object, Object>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(KinesisChangeConsumer.class);

    private static final String PROP_PREFIX = "debezium.sink.kinesis.";
    private static final String PROP_REGION_NAME = PROP_PREFIX + "region";
    private static final String PROP_ENDPOINT_NAME = PROP_PREFIX + "endpoint";
    private static final String PROP_CREDENTIALS_PROFILE = PROP_PREFIX + "credentials.profile";

    private String region;
    private Optional<String> endpointOverride;
    private Optional<String> credentialsProfile;
    private static final int DEFAULT_RETRIES = 5;
    private static final int batchSize = 500;
    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(1);

    @ConfigProperty(name = PROP_PREFIX + "null.key", defaultValue = "default")
    String nullKey;

    private KinesisClient client = null;

    @Inject
    @CustomConsumerBuilder
    Instance<KinesisClient> customClient;

    @PostConstruct
    void connect() {
        if (customClient.isResolvable()) {
            client = customClient.get();
            LOGGER.info("Obtained custom configured KinesisClient '{}'", client);
            return;
        }

        final Config config = ConfigProvider.getConfig();
        region = config.getValue(PROP_REGION_NAME, String.class);
        endpointOverride = config.getOptionalValue(PROP_ENDPOINT_NAME, String.class);
        credentialsProfile = config.getOptionalValue(PROP_CREDENTIALS_PROFILE, String.class);
        final KinesisClientBuilder builder = KinesisClient.builder()
                .region(Region.of(region));
        endpointOverride.ifPresent(endpoint -> builder.endpointOverride(URI.create(endpoint)));
        credentialsProfile.ifPresent(profile -> builder.credentialsProvider(ProfileCredentialsProvider.create(profile)));

        client = builder.build();
        LOGGER.info("Using default KinesisClient '{}'", client);
    }

    @PreDestroy
    void close() {
        try {
            client.close();
        }
        catch (Exception e) {
            LOGGER.warn("Exception while closing Kinesis client: {}", e);
        }
    }

    @Override
    public void handleBatch(List<ChangeEvent<Object, Object>> records, RecordCommitter<ChangeEvent<Object, Object>> committer)
            throws InterruptedException {

        // Split the records into batches of size 500
        String streamName = records.get(0).destination();
        List<List<ChangeEvent<Object, Object>>> batchRecords = batchList(records, batchSize);
        // Process each batch to PutRecordsRequestEntry
        for (List<ChangeEvent<Object, Object>> batch : batchRecords) {
            List<PutRecordsRequestEntry> putRecordsRequestEntryList = new ArrayList<>();
            for (ChangeEvent<Object, Object> record : batch) {

                Object rv = record.value();
                if (rv == null) {
                    rv = "";
                }
                PutRecordsRequestEntry putRecordsRequestEntry = PutRecordsRequestEntry.builder().partitionKey((record.key() != null) ? getString(record.key()) : nullKey)
                        .data(SdkBytes.fromByteArray(getBytes(rv))).build();
                putRecordsRequestEntryList.add(putRecordsRequestEntry);
            }

            // Handle Error
            boolean notSuccesful = true;
            int attempts = 0;
            List<PutRecordsRequestEntry> batchRequest = putRecordsRequestEntryList;

            while (notSuccesful) {

                if (attempts >= DEFAULT_RETRIES) {
                    throw new DebeziumException("Exceeded maximum number of attempts to publish event");
                }

                try {
                    PutRecordsResponse response = recordsSent(batchRequest, streamName);
                    attempts++;
                    if (response.failedRecordCount() > 0) {
                        Metronome.sleeper(RETRY_INTERVAL, Clock.SYSTEM).pause();

                        final List<PutRecordsResultEntry> putRecordsResults = response.records();
                        List<PutRecordsRequestEntry> failedRecordsList = new ArrayList<>();

                        for (int index = 0; index < putRecordsResults.size(); index++) {
                            PutRecordsResultEntry entryResult = putRecordsResults.get(index);
                            if (entryResult.errorCode() != null) {
                                failedRecordsList.add(putRecordsRequestEntryList.get(index));
                            }
                        }
                        batchRequest = failedRecordsList;

                    }
                    else {
                        notSuccesful = false;
                    }

                }
                catch (KinesisException exception) {
                    LOGGER.warn("Failed to send record to {}", streamName, exception);
                    attempts++;
                    Metronome.sleeper(RETRY_INTERVAL, Clock.SYSTEM).pause();
                }
            }

            for (ChangeEvent<Object, Object> record : batch) {
                committer.markProcessed(record);
            }
        }

        // Mark Batch Finished
        committer.markBatchFinished();
    }

    private List<List<ChangeEvent<Object, Object>>> batchList(List<ChangeEvent<Object, Object>> inputList, final int maxSize) {
        List<List<ChangeEvent<Object, Object>>> batches = new ArrayList<>();
        final int size = inputList.size();
        for (int i = 0; i < size; i += maxSize) {
            batches.add(new ArrayList<>(inputList.subList(i, Math.min(size, i + maxSize))));
        }
        return batches;
    }

    private PutRecordsResponse recordsSent(List<PutRecordsRequestEntry> putRecordsRequestEntryList, String streamName) {

        // Create a PutRecordsRequest
        PutRecordsRequest putRecordsRequest = PutRecordsRequest.builder().streamName(streamNameMapper.map(streamName)).records(putRecordsRequestEntryList).build();

        // Send Request
        PutRecordsResponse putRecordsResponse = client.putRecords(putRecordsRequest);
        LOGGER.trace("Response Receieved: " + putRecordsResponse);
        return putRecordsResponse;
    }
}
