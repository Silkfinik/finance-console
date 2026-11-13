package com.example.finance.cache;

import com.example.finance.storage.BalanceReader;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Кэш балансов по схеме cache-aside: чтение сначала идёт в Redis, при промахе в источник, и результат кладётся в Redis
 * на {@link #TTL}. Недоступный Redis чтению не мешает: баланс берётся из источника, ошибка уходит в лог уровня WARN.
 */
public class CachedBalanceService implements BalanceReader, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CachedBalanceService.class);

    static final String KEY_PREFIX = "balance:";
    static final Duration TTL = Duration.ofSeconds(60);

    private final BalanceReader source;
    private final RedisClient client;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    private StatefulRedisConnection<String, String> connection;

    /** Клиент остаётся за вызывающим кодом: {@link #close()} закрывает только соединение. */
    public CachedBalanceService(BalanceReader source, RedisClient client) {
        this.source = Objects.requireNonNull(source, "source не может быть null");
        this.client = Objects.requireNonNull(client, "client не может быть null");
    }

    @Override
    public Optional<BigDecimal> findBalance(UUID accountId) {
        Objects.requireNonNull(accountId, "accountId не может быть null");
        String key = key(accountId);
        try {
            String cached = redis().get(key);
            if (cached != null) {
                hits.increment();
                return Optional.of(new BigDecimal(cached));
            }
            misses.increment();
        } catch (RedisException e) {
            log.warn("Redis недоступен, баланс счёта {} читается из базы", accountId, e);
            return source.findBalance(accountId);
        }
        Optional<BigDecimal> balance = source.findBalance(accountId);
        balance.ifPresent(value -> {
            try {
                redis().set(key, value.toPlainString(), SetArgs.Builder.ex(TTL));
            } catch (RedisException e) {
                log.warn("Не удалось записать баланс счёта {} в Redis", accountId, e);
            }
        });
        return balance;
    }

    /** Удаляет балансы счетов из кэша. Вызывать после commit транзакции, изменившей эти балансы. */
    public void evict(Collection<UUID> accountIds) {
        Objects.requireNonNull(accountIds, "accountIds не может быть null");
        if (accountIds.isEmpty()) {
            return;
        }
        String[] keys = accountIds.stream().map(CachedBalanceService::key).toArray(String[]::new);
        try {
            redis().del(keys);
        } catch (RedisException e) {
            log.warn(
                    "Не удалось удалить из Redis балансы счетов {}, до {} с они могут быть устаревшими",
                    accountIds,
                    TTL.toSeconds(),
                    e);
        }
    }

    /** Сколько чтений нашли баланс в кэше. */
    public long hits() {
        return hits.sum();
    }

    /** Сколько чтений не нашли баланс в кэше. Чтения при недоступном Redis сюда не входят. */
    public long misses() {
        return misses.sum();
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    static String key(UUID accountId) {
        return KEY_PREFIX + accountId;
    }

    private synchronized RedisCommands<String, String> redis() {
        if (connection == null) {
            connection = client.connect();
        }
        return connection.sync();
    }
}
