package com.example.xkcdfetcher.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record XkcdComic(
    int num,
    String title,
    String img,
    String alt,
    @JsonProperty("transcript") String transcript
) {}
