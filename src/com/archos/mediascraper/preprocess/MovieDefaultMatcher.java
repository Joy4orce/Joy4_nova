// Copyright 2017 Archos SA
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


package com.archos.mediascraper.preprocess;

import android.net.Uri;
import android.util.Pair;

import com.archos.filecorelibrary.FileUtils;
import com.archos.mediascraper.StringUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Calendar;

import static com.archos.mediascraper.preprocess.ParseUtils.BRACKETS;
import static com.archos.mediascraper.preprocess.ParseUtils.isPlausibleYear;
import static com.archos.mediascraper.preprocess.ParseUtils.removeAfterEmptyParenthesis;
import static com.archos.mediascraper.preprocess.ParseUtils.extractYearAnywhere;
import static com.archos.mediascraper.preprocess.ParseUtils.yearExtractor;
import static com.archos.mediascraper.preprocess.ParseUtils.yearExtractorEndString;

/**
 * Matches everything. Tries to strip away all junk, not very reliable.
 * <p>
 * Process is as follows:
 * <ul>
 * <li> Start with filename without extension: "100. [DVD]Starship_Troopers_1995.-HDrip--IT"
 * <li> Remove potential starting numbering of collections "[DVD]Starship_Troopers_1995.-HDrip--IT"
 * <li> Extract last year if any: "[DVD]Starship_Troopers_.-HDrip--IT"
 * <li> Remove anything in brackets: "Starship_Troopers_.-HDrip--IT"
 * <li> Assume from here on that the title is first followed by junk
 * <li> Trim CasE sensitive junk: "Starship_Troopers_.-HDrip" ("it" could be part of the movie name, "IT" probably not)
 * <li> Remove separators: "Starship Troopers HDrip"
 * <li> Trim junk case insensitive: "Starship Troopers"
 * </ul>
 */
class MovieDefaultMatcher implements InputMatcher {
    private static final Logger log = LoggerFactory.getLogger(MovieDefaultMatcher.class);

    public static MovieDefaultMatcher instance() {
        return INSTANCE;
    }

    private static final MovieDefaultMatcher INSTANCE =
            new MovieDefaultMatcher();

    private MovieDefaultMatcher() {
        // singleton
    }

    @Override
    public boolean matchesFileInput(Uri fileInput, Uri simplifiedUri) {
        // this is the fallback matcher that matches everything
        return true;
    }

    @Override
    public boolean matchesUserInput(String userInput) {
        // this is the fallback matcher that matches everything
        return true;
    }

    @Override
    public SearchInfo getFileInputMatch(Uri file, Uri simplifiedUri) {
        if(simplifiedUri!=null)
            file = simplifiedUri;
        return getMatch(FileUtils.getFileNameWithoutExtension(file), file);
    }

    @Override
    public SearchInfo getUserInputMatch(String userInput, Uri file) {
        return getMatch(userInput, file);
    }

    private static SearchInfo getMatch(String input, Uri file) {
        String name = input;
        final int currentYear = Calendar.getInstance().get(Calendar.YEAR);

        // extract the last year from the string
        String year = null;
        // matches "[space or punctuation/brackets etc]year", year is group 1
        // "[\\s\\p{Punct}]((?:19|20)\\d{2})(?!\\d)"
        Pair<String, String> nameYear = yearExtractor(name);
        if (isPlausibleYear(nameYear.second, nameYear.first, currentYear)) {
            name = nameYear.first;
            year = nameYear.second;
        }

        // Fallback: if yearExtractor didn't find year, try yearExtractorEndString
        // Handles cases like "Movie.Title.2023" where year is at end
        if (year == null || year.isEmpty()) {
            nameYear = yearExtractorEndString(name);
            if (isPlausibleYear(nameYear.second, nameYear.first, currentYear)) {
                name = nameYear.first;
                year = nameYear.second;
            }
        }

        // Final fallback: scan anywhere for a plausible 4-digit year, even if attached
        if (year == null || year.isEmpty()) {
            nameYear = extractYearAnywhere(name, currentYear);
            if (isPlausibleYear(nameYear.second, nameYear.first, currentYear)) {
                name = nameYear.first;
                year = nameYear.second;
            }
        }

        // remove junk behind () that was containing year
        // applies to movieName (1928) junk -> movieName () junk -> movieName
        name = removeAfterEmptyParenthesis(name);

        // Strip out starting numbering for collections "1. ", "1) ", "1 - ", "1.-.", "1._"... but not "1.Foo" or "1-Foo"
        name = ParseUtils.removeNumbering(name);
        // Strip out starting numbering for collections "1-"
        name = ParseUtils.removeNumberingDash(name);

        // Strip out everything else in brackets <[{( .. )})>, most of the time teams names, etc
        name = StringUtils.replaceAll(name, "", BRACKETS);

        // strip away known case sensitive garbage
        name = ParseUtils.cutOffBeforeFirstMatch(name, ParseUtils.GARBAGE_CASESENSITIVE_PATTERNS);

        // replace all remaining whitespace & punctuation with a single space
        name = ParseUtils.removeInnerAndOutterSeparatorJunk(name);

        // append a " " to aid next step
        // > "Foo bar 1080p AC3 " to find e.g. " AC3 "
        name = name + " ";

        // try to remove more garbage, this time " garbage " syntax
        // method will compare with lowercase name automatically
        name = ParseUtils.cutOffBeforeFirstMatch(name, ParseUtils.GARBAGE_LOWERCASE);

        name = name.trim();
        return new MovieSearchInfo(file, name, year);
    }

    @Override
    public String getMatcherName() {
        return "MovieDefault";
    }

}
