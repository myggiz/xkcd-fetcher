# 📡 Reactive XKCD Fetcher (Java 24 + Spring Boot 3.2 + WebFlux + HTTP Interfaces)

This project is a **reactive XKCD comic fetcher** using:
- ✅ Java 24 (with optional Structured Concurrency)
- ✅ Spring Boot 3.2+
- ✅ Spring Framework 6.1+
- ✅ Spring WebFlux (Reactive Stack)
- ✅ HTTP Interfaces (new in Spring 6.1)

---

## 🔧 Prerequisites

- Java 221 (preview enabled if using Structured Concurrency)
- spring boot 3.5.0
- Maven or Gradle
- Internet access (XKCD public API)

---

## 🚀 Features

- Fetch the **latest XKCD comic**
- Fetch a **comic by ID**
- Fully **non-blocking** and **reactive**
- Uses **HTTP interfaces** instead of manual `WebClient` calls

---

## 🔗 XKCD API

- Latest comic: `https://xkcd.com/info.0.json`
- Specific comic: `https://xkcd.com/{comic_id}/info.0.json`

---

## 🧱 Project Structure

### 1. `XkcdComic.java`

```java
public record XkcdComic(
    int num,
    String title,
    String img,
    String alt,
    String transcript
) {}


@HttpExchange(url = "https://xkcd.com", accept = "application/json")
public interface XkcdHttpClient {

    @GetExchange("/info.0.json")
    Mono<XkcdComic> getLatestComic();

    @GetExchange("/{id}/info.0.json")
    Mono<XkcdComic> getComicById(@PathVariable int id);
}



@Configuration
public class HttpClientConfig {

    @Bean
    XkcdHttpClient xkcdClient(WebClient.Builder builder) {
        HttpServiceProxyFactory factory = HttpServiceProxyFactory
            .builder(WebClientAdapter.forClient(builder.build()))
            .build();

        return factory.createClient(XkcdHttpClient.class);
    }
}


@Service
public class XkcdService {

    private final XkcdHttpClient client;

    public XkcdService(XkcdHttpClient client) {
        this.client = client;
    }

    public Mono<XkcdComic> getLatestComic() {
        return client.getLatestComic();
    }

    public Mono<XkcdComic> getComicById(int id) {
        return client.getComicById(id);
    }
}


@RestController
@RequestMapping("/xkcd")
public class XkcdController {

    private final XkcdService service;

    public XkcdController(XkcdService service) {
        this.service = service;
    }

    @GetMapping("/latest")
    public Mono<XkcdComic> getLatestComic() {
        return service.getLatestComic();
    }

    @GetMapping("/{id}")
    public Mono<XkcdComic> getComicById(@PathVariable int id) {
        return service.getComicById(id);
    }
}

### Example Test with WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class XkcdControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void testLatestComic() {
        webTestClient.get().uri("/xkcd/latest")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.title").exists();
    }
}

