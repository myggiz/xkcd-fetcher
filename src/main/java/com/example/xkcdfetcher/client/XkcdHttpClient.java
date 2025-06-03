package com.example.xkcdfetcher.client;

import com.example.xkcdfetcher.model.XkcdComic;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import reactor.core.publisher.Mono;

@HttpExchange(url = "https://xkcd.com", accept = "application/json")
public interface XkcdHttpClient {

    @GetExchange("/info.0.json")
    Mono<XkcdComic> getLatestComic();

    @GetExchange("/{id}/info.0.json")
    Mono<XkcdComic> getComicById(@PathVariable int id);
}
