package com.github.reactivity512.rag.api;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.github.reactivity512.rag.api.dto.ApiDtos;
import com.github.reactivity512.rag.search.SearchService;

/**
 * {@code POST /search} — RAG-поиск: retrieval + генерация ответа LLM.
 *
 * <p>Только профиль {@code api}: воркер не поднимает web-сервер.
 */
@RestController
@Profile("api")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping("/search")
    public ApiDtos.SearchResponse search(@RequestBody ApiDtos.SearchRequest request) {
        List<String> skills = request.requiredSkills() == null ? List.of() : request.requiredSkills();
        SearchService.SearchResult result = searchService.search(
                request.query(), request.topKOrDefault(), skills);
        return new ApiDtos.SearchResponse(result.answer(), result.foundResumesCount());
    }
}
