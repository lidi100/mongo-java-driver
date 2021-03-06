/*
 * Copyright (c) 2008-2014 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.async.client;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoDriverInformation;
import com.mongodb.client.gridfs.codecs.GridFSFileCodecProvider;
import com.mongodb.client.model.geojson.codecs.GeoJsonCodecProvider;
import com.mongodb.connection.AsynchronousSocketChannelStreamFactory;
import com.mongodb.connection.ClusterSettings;
import com.mongodb.connection.ConnectionPoolSettings;
import com.mongodb.connection.DefaultClusterFactory;
import com.mongodb.connection.ServerSettings;
import com.mongodb.connection.SocketSettings;
import com.mongodb.connection.SslSettings;
import com.mongodb.connection.StreamFactory;
import com.mongodb.connection.StreamFactoryFactory;
import com.mongodb.connection.netty.NettyStreamFactory;
import com.mongodb.event.CommandEventMulticaster;
import com.mongodb.event.CommandListener;
import com.mongodb.management.JMXConnectionPoolListener;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.bson.codecs.BsonValueCodecProvider;
import org.bson.codecs.DocumentCodecProvider;
import org.bson.codecs.IterableCodecProvider;
import org.bson.codecs.ValueCodecProvider;
import org.bson.codecs.configuration.CodecRegistry;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

import static java.util.Arrays.asList;
import static org.bson.codecs.configuration.CodecRegistries.fromProviders;

/**
 * A factory for MongoClient instances.
 *
 * @since 3.0
 */
public final class MongoClients {

    /**
     * Creates a new client with the default connection string "mongodb://localhost".
     *
     * @return the client
     */
    public static MongoClient create() {
        return create(new ConnectionString("mongodb://localhost"));
    }

    /**
     * Create a new client with the given client settings.
     *
     * @param settings the settings
     * @return the client
     */
    public static MongoClient create(final MongoClientSettings settings) {
        return create(settings, null);
    }

    /**
     * Create a new client with the given connection string as if by a call to {@link #create(ConnectionString)}.
     *
     * @param connectionString the connection
     * @return the client
     * @see #create(ConnectionString)
     */
    public static MongoClient create(final String connectionString) {
        return create(new ConnectionString(connectionString));
    }

    /**
     * Create a new client with the given connection string.
     * <p>
     * For each of the settings classed configurable via {@link MongoClientSettings}, the connection string is applied by calling the
     * {@code applyConnectionString} method on an instance of setting's builder class, building the setting, and adding it to an instance of
     * {@link com.mongodb.async.client.MongoClientSettings.Builder}.
     * </p>
     * <p>
     * The connection string's stream type is then applied by setting the
     * {@link com.mongodb.connection.StreamFactory} to an instance of {@link NettyStreamFactory},
     * </p>
     *
     * @param connectionString the settings
     * @return the client
     * @throws IllegalArgumentException if the connection string's stream type is not one of "netty" or "nio2"
     *
     * @see ConnectionString#getStreamType()
     * @see com.mongodb.async.client.MongoClientSettings.Builder
     * @see com.mongodb.connection.ClusterSettings.Builder#applyConnectionString(ConnectionString)
     * @see com.mongodb.connection.ConnectionPoolSettings.Builder#applyConnectionString(ConnectionString)
     * @see com.mongodb.connection.ServerSettings.Builder#applyConnectionString(ConnectionString)
     * @see com.mongodb.connection.SslSettings.Builder#applyConnectionString(ConnectionString)
     * @see com.mongodb.connection.SocketSettings.Builder#applyConnectionString(ConnectionString)
     */
    public static MongoClient create(final ConnectionString connectionString) {
        return create(connectionString, null);
    }

    /**
     * Creates a new client with the given client settings.
     *
     * <p>Note: Intended for driver and library authors to associate extra driver metadata with the connections.</p>
     *
     * @param settings               the settings
     * @param mongoDriverInformation any driver information to associate with the MongoClient
     * @return the client
     * @since 3.4
     */
    public static MongoClient create(final MongoClientSettings settings, final MongoDriverInformation mongoDriverInformation) {
        return create(settings, mongoDriverInformation, null);
    }

    /**
     * Create a new client with the given connection string.
     *
     * <p>Note: Intended for driver and library authors to associate extra driver metadata with the connections.</p>
     *
     * @param connectionString       the settings
     * @param mongoDriverInformation any driver information to associate with the MongoClient
     * @return the client
     * @throws IllegalArgumentException if the connection string's stream type is not one of "netty" or "nio2"
     * @see MongoClients#create(ConnectionString)
     */
    public static MongoClient create(final ConnectionString connectionString, final MongoDriverInformation mongoDriverInformation) {
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .clusterSettings(ClusterSettings.builder()
                        .applyConnectionString(connectionString)
                        .build())
                .connectionPoolSettings(ConnectionPoolSettings.builder()
                        .applyConnectionString(connectionString)
                        .build())
                .serverSettings(ServerSettings.builder()
                        .applyConnectionString(connectionString)
                        .build())
                .credentialList(connectionString.getCredentialList())
                .sslSettings(SslSettings.builder()
                        .applyConnectionString(connectionString)
                        .build())
                .socketSettings(SocketSettings.builder()
                        .applyConnectionString(connectionString)
                        .build());

        if (connectionString.getReadPreference() != null) {
            builder.readPreference(connectionString.getReadPreference());
        }
        if (connectionString.getReadConcern() != null) {
            builder.readConcern(connectionString.getReadConcern());
        }
        if (connectionString.getWriteConcern() != null) {
            builder.writeConcern(connectionString.getWriteConcern());
        }
        if (connectionString.getApplicationName() != null) {
            builder.applicationName(connectionString.getApplicationName());
        }
        return create(builder.build(), mongoDriverInformation, connectionString.getStreamType());
    }

    private static MongoClient create(final MongoClientSettings settings, final MongoDriverInformation mongoDriverInformation,
                                      final String requestedStreamType) {
        String streamType = getStreamType(requestedStreamType);
        EventLoopGroup eventLoopGroup = getEventLoopGroupIfNecessary(settings.getStreamFactoryFactory(), streamType);
        StreamFactory streamFactory = getStreamFactory(settings.getStreamFactoryFactory(), settings.getSocketSettings(),
                settings.getSslSettings(), streamType, eventLoopGroup);
        StreamFactory heartbeatStreamFactory = getStreamFactory(settings.getStreamFactoryFactory(), settings.getHeartbeatSocketSettings(),
                settings.getSslSettings(), streamType, eventLoopGroup);
        return new MongoClientImpl(settings, new DefaultClusterFactory().create(settings.getClusterSettings(), settings.getServerSettings(),
                settings.getConnectionPoolSettings(), streamFactory,
                heartbeatStreamFactory,
                settings.getCredentialList(), null, new JMXConnectionPoolListener(), null,
                createCommandListener(settings.getCommandListeners()),
                settings.getApplicationName(), mongoDriverInformation),
                                          getEventLoopGroupCloser(eventLoopGroup));
    }


    /**
     * Gets the default codec registry.  It includes the following providers:
     *
     * <ul>
     *     <li>{@link org.bson.codecs.ValueCodecProvider}</li>
     *     <li>{@link org.bson.codecs.DocumentCodecProvider}</li>
     *     <li>{@link org.bson.codecs.BsonValueCodecProvider}</li>
     *     <li>{@link com.mongodb.client.model.geojson.codecs.GeoJsonCodecProvider}</li>
     * </ul>
     *
     * @return the default codec registry
     * @see MongoClientSettings#getCodecRegistry()
     * @since 3.1
     */
    public static CodecRegistry getDefaultCodecRegistry() {
        return MongoClients.DEFAULT_CODEC_REGISTRY;
    }

    private static final CodecRegistry DEFAULT_CODEC_REGISTRY =
            fromProviders(asList(new ValueCodecProvider(),
                    new DocumentCodecProvider(),
                    new BsonValueCodecProvider(),
                    new IterableCodecProvider(),
                    new GeoJsonCodecProvider(),
                    new GridFSFileCodecProvider()));

    private static StreamFactory getStreamFactory(final StreamFactoryFactory streamFactoryFactory,
                                                  final SocketSettings socketSettings, final SslSettings sslSettings,
                                                  final String streamType, final EventLoopGroup eventLoopGroup) {
        if (streamFactoryFactory != null) {
            return streamFactoryFactory.create(socketSettings, sslSettings);
        } else if (isNetty(streamType)) {
            return new NettyStreamFactory(socketSettings, sslSettings, eventLoopGroup);
        } else if (isNio2(streamType)) {
            return new AsynchronousSocketChannelStreamFactory(socketSettings, sslSettings);
        } else {
            throw new IllegalArgumentException("Unsupported stream type: " + streamType);
        }
    }

    private static boolean isNetty(final String streamType) {
        return streamType.toLowerCase().equals("netty");
    }

    private static boolean isNio2(final String streamType) {
        return streamType.toLowerCase().equals("nio2");
    }

    private static String getStreamType(final String requestedStreamType) {
        if (requestedStreamType != null) {
            return requestedStreamType;
        } else {
            return System.getProperty("org.mongodb.async.type", "nio2");
        }
    }

    private static Closeable getEventLoopGroupCloser(final EventLoopGroup eventLoopGroup) {
        if (eventLoopGroup == null) {
            return null;
        } else {
            return new Closeable() {
                @Override
                public void close() throws IOException {
                    eventLoopGroup.shutdownGracefully().awaitUninterruptibly();
                }
            };
        }
    }
    private static EventLoopGroup getEventLoopGroupIfNecessary(final StreamFactoryFactory streamFactoryFactory,
                                                                  final String streamType) {
        if (isNetty(streamType) && streamFactoryFactory == null) {
            return new NioEventLoopGroup();
        } else {
            return null;
        }
    }

    private static CommandListener createCommandListener(final List<CommandListener> commandListeners) {
        switch (commandListeners.size()) {
            case 0:
                return null;
            case 1:
                return commandListeners.get(0);
            default:
                return new CommandEventMulticaster(commandListeners);
        }
    }

    private MongoClients() {
    }
}
