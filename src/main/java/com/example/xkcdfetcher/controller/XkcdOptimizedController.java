package com.example.xkcdfetcher.controller;

import com.example.xkcdfetcher.model.XkcdComic;
import com.example.xkcdfetcher.service.XkcdServiceOptimized;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v2/xkcd")
@RequiredArgsConstructor
public class XkcdOptimizedController {
    
    private final XkcdServiceOptimized xkcdService;
    
    @GetMapping("/latest")
    public Mono<XkcdComic> getLatestComic() {
        return xkcdService.getLatestComic();
    }
    
    @GetMapping("/{id}")
    public Mono<XkcdComic> getComicById(@PathVariable int id) {
        return xkcdService.getComicById(id);
    }
    
    @GetMapping("/all")
    public Flux<XkcdComic> getAllComics() {
        return xkcdService.getAllComics();
    }
}
