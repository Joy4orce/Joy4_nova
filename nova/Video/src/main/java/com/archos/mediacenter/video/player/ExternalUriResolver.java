// Copyright 2026 Joy4orce
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

package com.archos.mediacenter.video.player;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

import com.archos.filecorelibrary.MimeUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers for media URIs handed to us by other apps ("Open with" from a file
 * manager): mapping content:// URIs back to on-disk paths, and finding the
 * other files in the folder of an http(s):// localhost-proxy URI (from the
 * proxy's folder listing, or by guessing the next episode number).
 * Shared by the sibling-subtitle scan (PlayerActivity) and folder auto-play
 * (UpdateNextTask).
 */
public final class ExternalUriResolver {

    private static final Logger log = LoggerFactory.getLogger(ExternalUriResolver.class);

    private static final int HTTP_CONNECT_TIMEOUT_MS = 2500;
    private static final int HTTP_READ_TIMEOUT_MS = 4000;
    private static final int MAX_NUMBER_GROUPS = 3;
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final int MAX_LISTING_BYTES = 1 << 20;
    private static final Pattern HREF = Pattern.compile("href\\s*=\\s*[\"']([^\"'#?]+)", Pattern.CASE_INSENSITIVE);
    private static final String DIAG_TAG = "Nova-AutoPlay";

    private ExternalUriResolver() {}

    /**
     * Resolve a media URI to a real on-disk path.
     * Handles:
     *   - file://                                   -> uri.getPath()
     *   - content:// MediaStore (audio/video/image) -> _data column lookup
     *   - content:// DocumentsContract (SAF):
     *       * com.android.externalstorage.documents -> /storage/emulated/0/<rel> (or /storage/<vol>/<rel>)
     *       * com.android.providers.media.documents -> rewrite to MediaStore URI, then _data
     *       * com.android.providers.downloads.documents -> public_downloads URI, then _data
     *   - everything else (remote http/smb, opaque providers) -> null
     */
    public static String resolveLocalPath(Context context, Uri uri) {
        if (uri == null) return null;
        String scheme = uri.getScheme();
        if ("file".equalsIgnoreCase(scheme)) {
            if (log.isDebugEnabled()) log.debug("resolveLocalPath: file scheme -> {}", uri.getPath());
            return uri.getPath();
        }
        if (!"content".equalsIgnoreCase(scheme)) return null;
        String authority = uri.getAuthority();
        // DocumentsContract paths (Storage Access Framework) — most common for modern file managers.
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                if (log.isDebugEnabled()) log.debug("resolveLocalPath: DocumentsContract uri authority={} docId={}", authority, docId);
                if ("com.android.externalstorage.documents".equals(authority) && docId != null) {
                    String[] split = docId.split(":", 2);
                    String volume = split[0];
                    String relPath = split.length > 1 ? split[1] : "";
                    if ("primary".equalsIgnoreCase(volume)) {
                        String p = Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + relPath;
                        if (log.isDebugEnabled()) log.debug("resolveLocalPath: externalstorage primary -> {}", p);
                        return p;
                    }
                    String p = "/storage/" + volume + "/" + relPath;
                    if (log.isDebugEnabled()) log.debug("resolveLocalPath: externalstorage volume {} -> {}", volume, p);
                    return p;
                }
                if ("com.android.providers.media.documents".equals(authority) && docId != null) {
                    String[] split = docId.split(":", 2);
                    if (split.length == 2) {
                        String type = split[0];
                        Uri mediaTable = null;
                        if ("audio".equals(type)) mediaTable = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                        else if ("video".equals(type)) mediaTable = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                        else if ("image".equals(type)) mediaTable = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                        if (mediaTable != null) {
                            try {
                                Uri mediaQuery = ContentUris.withAppendedId(mediaTable, Long.parseLong(split[1]));
                                String p = queryDataColumn(context, mediaQuery);
                                if (p != null) {
                                    if (log.isDebugEnabled()) log.debug("resolveLocalPath: media documents {} -> {}", type, p);
                                    return p;
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
                if ("com.android.providers.downloads.documents".equals(authority) && docId != null) {
                    try {
                        Uri downloadUri = ContentUris.withAppendedId(
                                Uri.parse("content://downloads/public_downloads"), Long.parseLong(docId));
                        String p = queryDataColumn(context, downloadUri);
                        if (p != null) {
                            if (log.isDebugEnabled()) log.debug("resolveLocalPath: downloads documents -> {}", p);
                            return p;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) log.debug("resolveLocalPath: DocumentsContract path failed: {}", e.getMessage());
        }
        // Fallback: try _data directly (works for MediaStore content URIs and well-behaved FileProviders).
        String p = queryDataColumn(context, uri);
        if (log.isDebugEnabled()) log.debug("resolveLocalPath: _data fallback for authority={} -> {}", authority, p);
        return p;
    }

    private static String queryDataColumn(Context context, Uri uri) {
        String[] projection = { MediaStore.MediaColumns.DATA };
        try (Cursor c = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(MediaStore.MediaColumns.DATA);
                if (idx >= 0) {
                    String path = c.getString(idx);
                    if (path != null && !path.isEmpty()) return path;
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) log.debug("queryDataColumn: failed for {}: {}", uri, e.getMessage());
        }
        return null;
    }

    /**
     * file:// URI for an on-disk path, in the unencoded "file://" + path form
     * JavaFile2.getUri() uses, except that '%', '#' and '?' are escaped so
     * getPath() (which both players use) returns the full path instead of
     * cutting the name at a fragment or query.
     */
    public static Uri fileUri(String path) {
        return Uri.parse("file://" + path.replace("%", "%25").replace("#", "%23").replace("?", "%3F"));
    }

    /**
     * Build a sibling URI by replacing the basename of {@code uri} with
     * {@code newFilename}. Handles two URI shapes:
     *   * SAF DocumentsContract URIs — swap the trailing path inside the doc id
     *   * Plain content:// / file:// / http(s):// URIs — swap the last URL path segment
     * Either way the rest of the URI (authority, prefix path, query) is preserved.
     */
    public static Uri siblingUri(Context context, Uri uri, String newFilename) {
        if (uri == null || newFilename == null) return null;

        // Try DocumentsContract first — covers SAF document URIs.
        try {
            if (context != null && DocumentsContract.isDocumentUri(context, uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                if (docId != null) {
                    int slash = docId.lastIndexOf('/');
                    int colon = docId.lastIndexOf(':');
                    int sep = Math.max(slash, colon);
                    String newDocId = (sep >= 0)
                            ? docId.substring(0, sep + 1) + newFilename
                            : newFilename;
                    return DocumentsContract.buildDocumentUri(uri.getAuthority(), newDocId);
                }
            }
        } catch (Exception ignored) {}

        // Generic last-path-segment swap — works for typical FileProvider URIs
        // where the URL path mirrors the on-disk file path.
        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.isEmpty()) return null;
        Uri.Builder b = uri.buildUpon().path(null);
        for (int i = 0; i < segments.size() - 1; i++) b.appendPath(segments.get(i));
        b.appendPath(newFilename);
        return b.build();
    }

    /**
     * GET an http(s):// URI with short timeouts and return the connected
     * connection, or throw FileNotFoundException if the server doesn't answer
     * 200/206. {@code firstByteOnly} sends "Range: bytes=0-0" — an existence
     * probe that also works on localhost proxies without HEAD support.
     * Caller owns the connection (read its stream or disconnect()).
     */
    public static HttpURLConnection openHttp(Uri uri, boolean firstByteOnly) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(uri.toString()).openConnection();
        conn.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        if (firstByteOnly) conn.setRequestProperty("Range", "bytes=0-0");
        int code;
        try {
            code = conn.getResponseCode();
        } catch (IOException e) {
            conn.disconnect();
            throw e;
        }
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            conn.disconnect();
            throw new FileNotFoundException("HTTP " + code);
        }
        return conn;
    }

    private static boolean httpExists(Uri uri) {
        try {
            openHttp(uri, true).disconnect();
            if (log.isDebugEnabled()) log.debug("httpExists: {} -> yes", uri);
            return true;
        } catch (Exception e) {
            if (log.isDebugEnabled()) log.debug("httpExists: {} -> {}", uri, e.getMessage());
            return false;
        }
    }

    /**
     * Ask a localhost http(s) proxy for the folder holding {@code uri} and return
     * every audio/video file in it, sorted by name like the folder play mode does
     * for local and SMB folders. Many proxies answer a GET on the folder URL with
     * an HTML index; its href links are collected. Returns null when the proxy
     * won't list the folder (error, or no media links in the answer), so the
     * caller can fall back to {@link #findNextHttpByNumber}.
     * Blocking network I/O: call off the UI thread.
     */
    public static List<Uri> listHttpFolderMedia(Uri uri) {
        if (!isLocalhostHttp(uri)) return null;
        List<String> segments = uri.getPathSegments();
        if (segments.isEmpty()) return null;
        Uri.Builder b = uri.buildUpon().path(null).query(null).fragment(null);
        for (int i = 0; i < segments.size() - 1; i++) b.appendPath(segments.get(i));
        String folderUrl = b.build().toString() + "/";
        List<String> folderSegments = segments.subList(0, segments.size() - 1);

        String body;
        HttpURLConnection conn = null;
        try {
            conn = openHttp(Uri.parse(folderUrl), false);
            String contentType = conn.getContentType();
            body = readLimited(conn.getInputStream(), MAX_LISTING_BYTES);
            // INFO via android.util.Log so it lands in nova-launch.log: what the proxy
            // returns for a folder is what decides whether this works
            Log.i(DIAG_TAG, "folder listing " + folderUrl + " -> " + contentType + ", " + body.length() + " chars: "
                    + body.substring(0, Math.min(300, body.length())).replace('\n', ' '));
        } catch (Exception e) {
            Log.i(DIAG_TAG, "folder listing " + folderUrl + " failed: " + e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }

        Map<String, Uri> byName = new HashMap<>();
        Matcher m = HREF.matcher(body);
        while (m.find()) {
            String href = m.group(1).replace("&amp;", "&");
            Uri child;
            try {
                child = Uri.parse(new URL(new URL(folderUrl), href).toString());
            } catch (Exception e) {
                continue;
            }
            // direct children of the folder only (no parent/sub-folder links)
            List<String> childSegments = child.getPathSegments();
            if (childSegments.size() != folderSegments.size() + 1
                    || !childSegments.subList(0, folderSegments.size()).equals(folderSegments)) continue;
            String name = child.getLastPathSegment();
            String mimeType = MimeUtils.guessMimeTypeFromExtension(MimeUtils.getExtension(name));
            if (mimeType == null || !(mimeType.startsWith("video/") || mimeType.startsWith("audio/"))) continue;
            byName.put(name, child);
        }
        if (byName.isEmpty()) {
            Log.i(DIAG_TAG, "folder listing has no media links, falling back to episode-number guess");
            return null;
        }
        List<String> names = new ArrayList<>(byName.keySet());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER); // same order as FileComparator.SortByNameAsc
        List<Uri> media = new ArrayList<>(names.size());
        for (String name : names) media.add(byName.get(name));
        Log.i(DIAG_TAG, "folder listing: " + media.size() + " media files");
        return media;
    }

    private static boolean isLocalhostHttp(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
        String host = uri.getHost();
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    private static String readLimited(InputStream in, int maxBytes) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (out.size() < maxBytes && (n = is.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Guess the next file after an http(s):// URI whose folder we can't list
     * (SMB-to-HTTP localhost proxies such as File Manager +, whose NAS URIs look
     * like http://127.0.0.1:21082/SMB/5/HDD1/...). Increments the
     * episode number in the basename ("Show S01E09 1080p.mp4" -> "Show S01E10
     * 1080p.mp4", keeping zero padding) and returns the sibling URI only if the
     * server says it exists. When {@code wrap} is set and there is no next one,
     * goes back to episode 1 (or 0), else repeats the current file.
     * Only localhost URIs qualify: UPnP servers also serve http(s) but name
     * items by database id, where id+1 is an unrelated file.
     * Blocking network I/O: call off the UI thread.
     */
    public static Uri findNextHttpByNumber(Uri uri, boolean wrap) {
        if (!isLocalhostHttp(uri)) return null;
        String name = uri.getLastPathSegment();
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";

        List<int[]> groups = episodeNumberCandidates(base);
        if (groups.isEmpty()) {
            if (log.isDebugEnabled()) log.debug("findNextHttpByNumber: no episode number in {}", name);
            return wrap ? uri : null;
        }
        for (int[] g : groups) {
            Uri next = probeNumber(uri, base, ext, g, Long.parseLong(base.substring(g[0], g[1])) + 1);
            if (next != null) return next;
        }
        if (wrap) {
            int[] g = groups.get(0);
            long current = Long.parseLong(base.substring(g[0], g[1]));
            for (long first = 1; first >= 0 && first < current; first--) {
                Uri firstUri = probeNumber(uri, base, ext, g, first);
                if (firstUri != null) return firstUri;
            }
            if (log.isDebugEnabled()) log.debug("findNextHttpByNumber: wrap found nothing, repeating {}", name);
            return uri;
        }
        if (log.isDebugEnabled()) log.debug("findNextHttpByNumber: no next found after {}", name);
        return null;
    }

    /** Try {@code value} in place of digit group {@code g}, zero-padded to the original width, then unpadded. */
    private static Uri probeNumber(Uri uri, String base, String ext, int[] g, long value) {
        int width = g[1] - g[0];
        String padded = String.format(Locale.US, "%0" + width + "d", value);
        String unpadded = String.valueOf(value);
        String prefix = base.substring(0, g[0]);
        String suffix = base.substring(g[1]);
        Uri candidate = siblingUri(null, uri, prefix + padded + suffix + ext);
        if (httpExists(candidate)) return candidate;
        if (!unpadded.equals(padded)) {
            candidate = siblingUri(null, uri, prefix + unpadded + suffix + ext);
            if (httpExists(candidate)) return candidate;
        }
        return null;
    }

    /**
     * Digit groups of {@code base} that could be the episode number, best first:
     * explicit markers (E09, EP09, 9화, 9회) first, then the rest from last to first.
     * Skips release tags such as 1080p, 2160p, 4K, x264, h265, and keeps at most
     * MAX_NUMBER_GROUPS so a long name can't trigger a long chain of probes.
     */
    private static List<int[]> episodeNumberCandidates(String base) {
        List<int[]> marked = new ArrayList<>();
        List<int[]> others = new ArrayList<>();
        Matcher m = DIGITS.matcher(base);
        while (m.find()) {
            int start = m.start(), end = m.end();
            if (end - start > 9) continue; // not an episode number, and keeps Long.parseLong safe
            char before = start > 0 ? base.charAt(start - 1) : ' ';
            char after = end < base.length() ? base.charAt(end) : ' ';
            if ("pPiIkK".indexOf(after) >= 0 || "xXhH".indexOf(before) >= 0) continue;
            boolean isMarked = "eEpP".indexOf(before) >= 0 || after == '화' || after == '회'; // 화, 회
            (isMarked ? marked : others).add(0, new int[]{start, end});
        }
        marked.addAll(others);
        return marked.size() > MAX_NUMBER_GROUPS ? marked.subList(0, MAX_NUMBER_GROUPS) : marked;
    }
}
