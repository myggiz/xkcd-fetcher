package com.example.xkcdfetcher.controller;

import com.example.xkcdfetcher.model.XkcdComic;
import com.example.xkcdfetcher.service.XkcdService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/xkcd")
@RequiredArgsConstructor
public class XkcdController {

    private final XkcdService service;

    @GetMapping("/latest")
    public Mono<XkcdComic> getLatestComic() {
        return service.getLatestComic();
    }

    @GetMapping("/{id}")
    public Mono<XkcdComic> getComicById(@PathVariable int id) {
        return service.getComicById(id);
    }

    @GetMapping("/all")
    public Flux<XkcdComic> getAllComics() {
        return service.getAllComics();
    }
}
