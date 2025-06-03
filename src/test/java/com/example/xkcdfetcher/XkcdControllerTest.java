package com.example.xkcdfetcher;

import com.example.xkcdfetcher.controller.XkcdController;
import com.example.xkcdfetcher.model.XkcdComic;
import com.example.xkcdfetcher.service.XkcdService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class XkcdControllerTest {

    private WebTestClient webTestClient;
    
    @Mock
    private XkcdService xkcdService;
    
    @InjectMocks
    private XkcdController xkcdController;
    
    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(xkcdController).build();
    }

    @Test
    void testGetLatestComic() {
        // Given
        XkcdComic mockComic = new XkcdComic(
            1, 
            "Test Comic", 
            "https://example.com/comic.png", 
            "Alt text", 
            "Transcript"
        );

        // When
        when(xkcdService.getLatestComic()).thenReturn(Mono.just(mockComic));

        // Then
        webTestClient.get().uri("/xkcd/latest")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.title").isEqualTo("Test Comic")
            .jsonPath("$.num").isEqualTo(1);
    }
}
