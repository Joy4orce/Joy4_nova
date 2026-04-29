# Nova Video Player - Scrape Preprocessing Specification

This document defines the strategy and logic for extracting metadata from filenames and querying TMDb. It combines **Leeroy's "Backwards Loop"** efficiency with **Nova's "Title Integrity"** rules.

## 1. Overview & Goals
The goal is to transform a raw filename into an optimal TMDb query while distinguishing between a **Release Year** and a **Year in the Title** (e.g., *1984*, *2001: A Space Odyssey*, *Class of 1999*, *1917*).

---

## 2. Year Extraction Strategy (The "Leeroy" Engine)

The scraper uses a right-to-left (backwards) scan to find the most likely release year candidate.

### 2.1 Core Rules
- **Valid Range**: **1906** to **CurrentYear + 1**. (Resolves *Paris Police 1900*).
- **Resolution Safety**: Uses Lookaround Regex `(?<![\d\p{L}])(\d{4})(?![\d\p{L}])` to ignore years inside resolutions (e.g., `1080` in `1920x1080`) or codecs.
- **Hard Break**: As soon as the backwards loop finds a valid year, it **stops** searching further left. This provides stability and protects title prefixes.

### 2.2 Extraction Heuristics (Sequential)
1. **Parentheses Year `(YYYY)`**: 
   - *Status:* **Confident**. 
   - *Action:* Strip from name.
2. **Backwards Scan ("Anywhere" Year)**:
   - Perform backwards regex scan for isolated 4-digit numbers.
   - On the first valid match found:
     - Calculate the **Cut Index** (characters remaining to the left of the year).
     - **If Cut Index == 0** (Year at start, e.g., `2001 A Space Odyssey`):
       - Identify year but **DO NOT STRIP** from the title.
       - Set `yearAtStart = true`.
     - **If Cut Index > 0 AND Remainder is < 2 characters** (e.g., `1984.mkv`):
       - **Ignore the year**. Keep it in the title.
     - **If Cut Index > 0 AND Remainder is >= 2 characters** (e.g., `Class of 1999`):
       - Identify year and **STRIP** from the title.
       - Set `yearConfident = false`.

---

## 3. Fallback Two-Stage Identification (The "Nova" Unified Scoring)

If a year is identified but not "Confident" (not in parentheses), the scraper performs two searches to ensure the best match wins.

### 3.1 Candidate Ordering
The order of search passes depends on the `yearAtStart` flag:

**Scenario A: Year at Start** (e.g., `1961 le cave se rebiffe`)
1. **Pass 1 (Title Priority):** Search **Original Name** (unstripped), Year: `null`.
2. **Pass 2 (Split Fallback):** Search **Cleaned Name** (remainder), Year: `1961`.

**Scenario B: Year at End/Middle** (e.g., `Class of 1999`)
1. **Pass 1 (Year Priority):** Search **Cleaned Name** (stripped), Year: `1999`.
2. **Pass 2 (Merge Fallback):** Search **Original Name** (unstripped), Year: `null`.

### 3.2 Unified Scoring (The Re-Rank)
1. Execute searches for **both** candidates.
2. Pool all results from both searches into a single list.
3. For every result, calculate the **Levenshtein Distance** against the `originalName`.
4. Sort the pool by distance (Ascending). 
   - *Example:* For `Class of 1999`, the year-pass might return *"In a Class of His Own"*, but the title-pass returns *"Class of 1999"*. Sorting by distance ensures the exact title match (distance 0) wins.

---

## 4. Special Episodes Handling (TV Shows)
- **Season 0 Mapping**: Any episode parsed with Season `0` or `00` is mapped to TMDb "Specials".
- **Folder Fallback**: Folder named `Specials` without explicit `S00` tags defaults to Season 0.

---

## 5. The Preprocessing Pipeline (Sequence)
1. **Extension Stripping**: Defensive removal of `.mkv`, `.mp4`.
2. **Original Name Capture**: Capture title here for re-ranking reference.
3. **Numbering Stripping**: Remove leading numbers (restricted to **max 3 digits**).
4. **Year Extraction**: Apply Section 2 heuristics.
5. **Garbage Cleanup**:
   - Strip brackets `[]`, `{}`, `<>`.
   - Strip case-sensitive tags (`FRENCH`, `MULTI`).
   - Strip resolutions (`1920x1080`, `720p`).
6. **Normalization**: Trim, unify apostrophes, and resolve acronyms.
