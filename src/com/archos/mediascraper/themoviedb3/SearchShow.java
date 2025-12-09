// Copyright 2020 Courville Software
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.archos.mediascraper.themoviedb3;

import android.os.Bundle;
import android.util.LruCache;
import android.util.Pair;

import com.archos.mediascraper.ScrapeStatus;
import com.archos.mediascraper.SearchResult;
import com.archos.mediascraper.ShowUtils;
import com.archos.mediascraper.preprocess.TvShowSearchInfo;
import com.archos.mediascraper.xml.ShowScraper4;
import com.uwetrottmann.tmdb2.entities.TvShowResultsPage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import retrofit2.Response;

import java.util.Locale;

import static com.archos.mediascraper.preprocess.ParseUtils.yearExtractor;

// Search Show for name query for year in language (ISO 639-1 code)
public class SearchShow {
    private static final Logger log = LoggerFactory.getLogger(SearchShow.class);

    // Tier 1 cache: Normalized show name -> showId mapping to avoid redundant TMDb searches
    // This cache deduplicates different name variations (case, spacing) that resolve to the same show
    private final static LruCache<String, Integer> sShowNameToIdCache = new LruCache<>(100);

    // Tier 2 cache: Exact search string -> full TMDb API response
    // Benchmarks tells that with tv shows sorted in folders, size of 200 or 20 or even 10 provides the same cacheHits on fake collection of 30k episodes, 250 shows
    private final static LruCache<String, Response<TvShowResultsPage>> showCache = new LruCache<>(50);

    public static SearchShowResult search(TvShowSearchInfo searchInfo, String language, int resultLimit, final boolean adultScrape, ShowScraper4 showScraper, MyTmdb tmdb) {
        SearchShowResult myResult = new SearchShowResult();
        Response<TvShowResultsPage> response = null;
        boolean authIssue = false;
        boolean notFoundIssue = true;
        boolean isResponseOk = false;
        boolean isResponseEmpty = false;
        boolean serviceError = false;
        String showKey = null;
        String name;
        if (log.isDebugEnabled()) log.debug("search: quering tmdb for {} year {} in {}, resultLimit={}", searchInfo.getShowName(), searchInfo.getFirstAiredYear(), language, resultLimit);
        try {
            Integer year = null;
            if (searchInfo.getFirstAiredYear() != null) {
                try {
                    year = Integer.parseInt(searchInfo.getFirstAiredYear());
                } catch (NumberFormatException nfe) {
                    log.warn("search: not valid year int {}", searchInfo.getFirstAiredYear());
                }
            }

            String searchQueryString = searchInfo.getShowName();

            // Tier 1 cache check: normalized name -> showId
            String nameCacheKey = buildNameCacheKey(searchQueryString, year, language);
            Integer cachedShowId = sShowNameToIdCache.get(nameCacheKey);
            if (cachedShowId != null) {
                if (log.isDebugEnabled()) log.debug("search: Tier 1 cache hit for normalized name '{}' -> showId {}", searchQueryString, cachedShowId);
                // Build result directly from cached showId, skip TMDb API call
                SearchResult result = new SearchResult(SearchResult.tvshow, searchQueryString, cachedShowId);
                result.setLanguage(language);
                result.setScraper(showScraper);
                result.setFile(searchInfo.getFile());
                result.setYear(year != null ? String.valueOf(year) : null);
                result.setOriginSearchSeason(searchInfo.getSeason());
                result.setOriginSearchEpisode(searchInfo.getEpisode());

                Bundle extra = new Bundle();
                extra.putString(ShowUtils.EPNUM, String.valueOf(searchInfo.getEpisode()));
                extra.putString(ShowUtils.SEASON, String.valueOf(searchInfo.getSeason()));
                result.setExtra(extra);

                myResult.result = new java.util.ArrayList<>();
                myResult.result.add(result);
                myResult.status = ScrapeStatus.OKAY;
                return myResult;
            }
            if (log.isDebugEnabled()) log.debug("search: Tier 1 cache miss for normalized name '{}'", searchQueryString);

            // Tier 2 cache check: exact search string -> full API response
            showKey = searchQueryString + ((year != null) ? "|" + year : "") + "|" + language;
            if (log.isDebugEnabled()) log.debug("SearchShowResult: cache showKey {}", showKey);
            response = showCache.get(showKey);
            //if (log.isTraceEnabled()) debugLruCache(showCache);
            if (response == null) {
                if (log.isDebugEnabled()) log.debug("SearchShowResult: no boost for {} year {}", searchInfo.getShowName(), year);
                // adult search false by default
                response = tmdb.searchService().tv(searchQueryString, 1, language, year, false).execute();
                if (response.code() != 404) notFoundIssue = false; // this is an AND
                // Check https://developer.themoviedb.org/docs/errors
                switch (response.code()) {
                    case 401 -> authIssue = true; // this is an OR
                    case 404 -> notFoundIssue = true; // this is an AND
                    case 500, 503, 504 -> serviceError = true;
                }
                if (response.isSuccessful()) isResponseOk = true;
                if (response.body() == null)
                    isResponseEmpty = true;
                else {
                    if (response.body().total_results == 0) notFoundIssue = true;
                    if (log.isDebugEnabled()) log.debug("search: response body has {} results", response.body().total_results);
                    if (notFoundIssue && searchInfo.getFirstAiredYear() == null) {
                        // reprocess name with year_extractor without parenthesis since we need to match The.Flash.2014.sXXeYY but not first to cope with Paris.Police.1900
                        name = searchInfo.getShowName();
                        Pair<String, String> nameYear = yearExtractor(name);
                        if (log.isDebugEnabled()) log.debug("search: not found trying to extract year name={}, year={}", nameYear.first, nameYear.second);
                        if (nameYear.second != null) { // avoid infinite loop
                            // remember that it is a reboot show with date year to add to name to discriminate
                            myResult.year = nameYear.second;
                            return search(new TvShowSearchInfo(searchInfo.getFile(), nameYear.first, searchInfo.getSeason(), searchInfo.getEpisode(), nameYear.second, searchInfo.getCountryOfOrigin()),
                                    language, resultLimit, adultScrape, showScraper, tmdb);
                        }
                    }
                }
                if (isResponseOk || isResponseEmpty) {
                    if (log.isDebugEnabled()) log.debug("search: inserting in Tier 2 cache showKey {} and response ", showKey);
                    showCache.put(showKey, response);
                    // Also populate Tier 1 cache if we have results
                    if (isResponseOk && response.body() != null && response.body().total_results > 0) {
                        int firstResultId = response.body().results.get(0).id;
                        sShowNameToIdCache.put(nameCacheKey, firstResultId);
                        if (log.isDebugEnabled()) log.debug("search: inserting in Tier 1 cache normalized name '{}' -> showId {}", searchQueryString, firstResultId);
                    }
                }
                if (log.isTraceEnabled()) {
                    debugLruCache(showCache);
                    debugNameCache(sShowNameToIdCache);
                }
            } else {
                if (log.isDebugEnabled()) log.debug("search: boost using cached searched show for {}", searchInfo.getShowName());
                isResponseOk = true;
                notFoundIssue = false;
                if (response.body() == null) isResponseEmpty = true;
            }
            if (authIssue) {
                if (log.isDebugEnabled()) log.debug("search: auth error");
                myResult.status = ScrapeStatus.AUTH_ERROR;
                myResult.result = SearchShowResult.EMPTY_LIST;
                ShowScraper4.reauth();
                return myResult;
            }
            if (notFoundIssue || serviceError) {
                if (log.isDebugEnabled()) log.debug("search: not found");
                myResult.result = SearchShowResult.EMPTY_LIST;
                if (serviceError) myResult.status = ScrapeStatus.ERROR;
                else myResult.status = ScrapeStatus.NOT_FOUND;
            } else {
                if (isResponseEmpty) {
                    if (log.isDebugEnabled()) log.debug("search: error");
                    myResult.result = SearchShowResult.EMPTY_LIST;
                    myResult.status = ScrapeStatus.ERROR_PARSER;
                } else {
                    myResult.result = SearchShowParser.getResult(
                            (isResponseOk) ? response : null,
                            searchInfo, year, language, resultLimit, showScraper);
                    myResult.status = ScrapeStatus.OKAY;
                }
            }
        } catch (IOException e) {
            if (log.isDebugEnabled())
                log.error("search: caught IOException {}", e.getMessage(), e);
            else
                log.error("search: caught IOException");
            myResult.result = SearchShowResult.EMPTY_LIST;
            myResult.status = ScrapeStatus.ERROR_PARSER;
            myResult.reason = e;
        }
        return myResult;
    }

    /**
     * Normalizes show name for Tier 1 cache key.
     * Show name is already cleaned by ShowUtils.cleanUpName() (dots/underscores replaced, brackets removed).
     * We only need to lowercase it for case-insensitive matching.
     */
    private static String normalizeShowNameForCache(String showName) {
        if (showName == null) return "";
        return showName.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Builds cache key for Tier 1 name-to-id cache.
     * Format: "normalizedname|year|language"
     */
    private static String buildNameCacheKey(String showName, Integer year, String language) {
        String normalized = normalizeShowNameForCache(showName);
        return normalized + (year != null ? "|" + year : "") + "|" + language;
    }

    public static void debugLruCache(LruCache<String, Response<TvShowResultsPage>> lruCache) {
        if (log.isDebugEnabled()) log.debug("debugLruCache(Tier2): size={}, put={}, hit={}, miss={}, evict={}", lruCache.size(), lruCache.putCount(), lruCache.hitCount(), lruCache.missCount(), lruCache.evictionCount());
    }

    public static void debugNameCache(LruCache<String, Integer> lruCache) {
        if (log.isDebugEnabled()) log.debug("debugNameCache(Tier1): size={}, put={}, hit={}, miss={}, evict={}", lruCache.size(), lruCache.putCount(), lruCache.hitCount(), lruCache.missCount(), lruCache.evictionCount());
    }

}
