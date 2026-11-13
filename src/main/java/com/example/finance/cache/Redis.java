package com.example.finance.cache;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import java.time.Duration;

public final class Redis {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(1);
    static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(2);

    private Redis() {}

    public static RedisClient createClient(String url) {
        RedisURI uri = RedisURI.create(url);
        uri.setTimeout(COMMAND_TIMEOUT);
        RedisClient client = RedisClient.create(uri);
        client.setOptions(ClientOptions.builder()
                .socketOptions(
                        SocketOptions.builder().connectTimeout(CONNECT_TIMEOUT).build())
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
        return client;
    }

    public static void shutdown(RedisClient client) {
        client.shutdown(Duration.ZERO, SHUTDOWN_TIMEOUT);
    }
}
