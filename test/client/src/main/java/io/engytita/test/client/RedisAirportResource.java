package io.engytita.test.client;

import javax.inject.Inject;
import javax.ws.rs.Path;

import io.quarkus.redis.client.reactive.ReactiveRedisClient;

@Path("/airredis")
public class RedisAirportResource extends RedisBaseAirportResource {
    @Inject
    ReactiveRedisClient redis;

    @Override
    protected ReactiveRedisClient redisClient() {
        return redis;
    }
}
