package com.example.xkcdfetcher.config;

import com.example.xkcdfetcher.client.XkcdHttpClient;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class HttpClientConfig {

    private static final int CONNECTION_TIMEOUT_MS = 10000; // 10 seconds
    private static final int RESPONSE_TIMEOUT_MS = 30000;    // 30 seconds
    private static final int MAX_CONNECTIONS = 500;
    private static final int MAX_IDLE_TIME_MS = 30000;       // 30 seconds
    private static final int MAX_LIFE_TIME_MS = 60000;        // 1 minute

    @Bean
    public ConnectionProvider connectionProvider() {
        return ConnectionProvider.builder("xkcd-connection-pool")
                .maxConnections(MAX_CONNECTIONS)
                .pendingAcquireMaxCount(MAX_CONNECTIONS)
                .maxIdleTime(Duration.ofMillis(MAX_IDLE_TIME_MS))
                .maxLifeTime(Duration.ofMillis(MAX_LIFE_TIME_MS))
                .build();
    }

    @Bean
    public HttpClient httpClient(ConnectionProvider connectionProvider) {
        return HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECTION_TIMEOUT_MS)
                .responseTimeout(Duration.ofMillis(RESPONSE_TIMEOUT_MS))
                .doOnConnected(conn -> 
                    conn.addHandlerLast(new ReadTimeoutHandler(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                       .addHandlerLast(new WriteTimeoutHandler(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                );
    }

    @Bean
    public XkcdHttpClient xkcdClient(WebClient.Builder builder, HttpClient httpClient) {
        WebClient client = builder
                .baseUrl("https://xkcd.com")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
                
        WebClientAdapter adapter = WebClientAdapter.create(client);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(adapter).build();
        return factory.createClient(XkcdHttpClient.class);
    }
}
