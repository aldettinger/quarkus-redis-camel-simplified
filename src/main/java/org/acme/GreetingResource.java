package org.acme;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.component.redis.processor.aggregate.RedisAggregationRepository;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.DefaultExchangeHolder;
import org.apache.camel.impl.DefaultCamelContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RTransaction;
import org.redisson.api.RedissonClient;
import org.redisson.api.TransactionOptions;
import org.redisson.config.Config;

@Path("/hello")
public class GreetingResource {

    @ConfigProperty(name = "camel.redis.test.server.authority")
    String redisUrl;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {

        StringBuilder sb = new StringBuilder();

        String key = "1";
        String mapName = "aggregation";
        String persistenceMapName = "aggregation-completed";

        // doStart
        Config config = new Config();
        config.useSingleServer().setAddress(String.format("redis://%s", redisUrl));
        RedissonClient redisson = Redisson.create(config);
        Map<String, DefaultExchangeHolder> cache = redisson.getMap(mapName);
        Map<String, DefaultExchangeHolder> persistedCache = redisson.getMap(persistenceMapName);
        sb.append("START");

        // scan
        Set<String> scanned = Collections.unmodifiableSet(persistedCache.keySet());
        scanned.toString();
        sb.append("-SCAN");

        // add
        DefaultCamelContext context = new DefaultCamelContext();
        Exchange exchange = new DefaultExchange(context);
        exchange.getIn().setBody("dummy body");

        RLock lock = redisson.getLock("aggregationLock");
        try {
            lock.lock();
            DefaultExchangeHolder newHolder = DefaultExchangeHolder.marshal(exchange, true, false);
            DefaultExchangeHolder oldHolder = cache.put(key, newHolder);

            if (oldHolder == null) {
                // unmarshal return null
            }
            sb.append("-ADDTRY");
        } finally {
            lock.unlock();
            sb.append("-ADDFINALLY");
        }

        // remove
        TransactionOptions tOpts = TransactionOptions.defaults();
        RTransaction transaction = redisson.createTransaction(tOpts);

        try {
            RMap<String, DefaultExchangeHolder> tCache = transaction.getMap(mapName);
            RMap<String, DefaultExchangeHolder> tPersistentCache = transaction.getMap(persistenceMapName);

            DefaultExchangeHolder removedHolder = tCache.remove(key);
            tPersistentCache.put(exchange.getExchangeId(), removedHolder);

            transaction.commit();
            sb.append("-REMTRY");
        } catch (Exception throwable) {
            sb.append("-REMCATCH");
            transaction.rollback();

            final String msg = String.format("Transaction was rolled back for remove operation with a key %s and an Exchange ID %s.", key, exchange.getExchangeId());
            throw new RuntimeException(msg, throwable);
        }

        // confirm
        persistedCache.remove(exchange.getExchangeId());
        sb.append("-CONFIRM");

        // doStop
        redisson.shutdown();
        sb.append("-SHUTDOWN");

        return sb.toString();
    }

}
