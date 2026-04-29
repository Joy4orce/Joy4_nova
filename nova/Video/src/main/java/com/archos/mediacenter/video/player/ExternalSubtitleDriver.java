// Copyright 2026
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0

package com.archos.mediacenter.video.player;

import android.os.Handler;
import android.os.Looper;

import com.archos.medialib.Subtitle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an external subtitle file (.srt / .vtt / .ass / .ssa / .smi) into a list
 * of timed cues and feeds them back to a {@link Listener} based on the current
 * playback position.
 *
 * Used for audio-only files played through Android's MediaPlayer, since the
 * AVOS native subtitle pipeline is not active in that path.
 */
public class ExternalSubtitleDriver {

    private static final Logger log = LoggerFactory.getLogger(ExternalSubtitleDriver.class);

    public interface Listener {
        void onSubtitle(Subtitle subtitle);
    }

    public interface PositionProvider {
        /** Current playback position in milliseconds. */
        int getCurrentPositionMs();
    }

    public static final String[] SUPPORTED_EXTENSIONS = {
            "srt", "vtt", "ass", "ssa", "smi", "txt"
    };

    private static final long POLL_INTERVAL_MS = 200;

    private final List<Cue> cues;
    private final Listener listener;
    private final PositionProvider positionProvider;
    private final Handler handler;
    private int lastCueIndex = -2; // -2 means "never set" so first tick always fires
    private boolean running = false;

    private ExternalSubtitleDriver(List<Cue> cues, Listener listener, PositionProvider positionProvider) {
        this.cues = cues;
        this.listener = listener;
        this.positionProvider = positionProvider;
        this.handler = new Handler(Looper.getMainLooper());
    }

    /** Build a driver from a subtitle file. Returns null if parsing yields no cues. */
    public static ExternalSubtitleDriver fromFile(File subtitleFile, Listener listener, PositionProvider positionProvider) {
        if (subtitleFile == null || !subtitleFile.isFile() || !subtitleFile.canRead()) return null;
        List<Cue> cues = parse(subtitleFile);
        if (cues == null || cues.isEmpty()) {
            log.info("ExternalSubtitleDriver.fromFile: parsed 0 cues from {}", subtitleFile.getAbsolutePath());
            return null;
        }
        log.info("ExternalSubtitleDriver.fromFile: parsed {} cues from {}", cues.size(), subtitleFile.getAbsolutePath());
        return new ExternalSubtitleDriver(cues, listener, positionProvider);
    }

    /**
     * Build a driver from an already-open {@link InputStream}. Used when the
     * subtitle is reachable via {@link android.content.ContentResolver} but not
     * as a real on-disk path — e.g. third-party FileProvider / SAF / NAS clients
     * that expose <code>content://</code> URIs without a {@code _data} column.
     *
     * <p>The stream is fully consumed and closed by this method. The caller
     * supplies {@code filenameForFormat} so we can pick the right parser
     * (the URI itself sometimes carries a synthetic name without a useful
     * extension).
     */
    public static ExternalSubtitleDriver fromInputStream(InputStream is, String filenameForFormat,
                                                         Listener listener, PositionProvider positionProvider) {
        if (is == null) return null;
        try {
            String content = readText(is);
            List<Cue> cues = parseByName(content, filenameForFormat);
            if (cues == null || cues.isEmpty()) {
                log.info("ExternalSubtitleDriver.fromInputStream: parsed 0 cues from {}", filenameForFormat);
                return null;
            }
            log.info("ExternalSubtitleDriver.fromInputStream: parsed {} cues from {}", cues.size(), filenameForFormat);
            return new ExternalSubtitleDriver(cues, listener, positionProvider);
        } catch (IOException e) {
            log.warn("ExternalSubtitleDriver.fromInputStream: read failed for {}", filenameForFormat, e);
            return null;
        } finally {
            try { is.close(); } catch (IOException ignored) {}
        }
    }

    /** Find the first sibling subtitle file matching the given media file name. */
    public static File findSubtitleFor(File mediaFile) {
        if (mediaFile == null) return null;
        File parent = mediaFile.getParentFile();
        if (parent == null || !parent.isDirectory()) return null;
        String mediaName = mediaFile.getName();
        int dot = mediaName.lastIndexOf('.');
        String stem = (dot > 0) ? mediaName.substring(0, dot) : mediaName;
        File[] siblings = parent.listFiles();
        if (siblings == null) return null;
        for (String ext : SUPPORTED_EXTENSIONS) {
            for (File f : siblings) {
                if (!f.isFile()) continue;
                String name = f.getName();
                if (!name.toLowerCase(Locale.US).endsWith("." + ext)) continue;
                // accept "stem.ext" or "stem.<lang>.ext"
                if (name.equalsIgnoreCase(stem + "." + ext)) return f;
                if (name.toLowerCase(Locale.US).startsWith(stem.toLowerCase(Locale.US) + ".")
                        && name.toLowerCase(Locale.US).endsWith("." + ext)) {
                    return f;
                }
            }
        }
        return null;
    }

    public void start() {
        if (running) return;
        running = true;
        handler.post(tick);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
        // Clear any showing subtitle
        listener.onSubtitle(new Subtitle.TimedTextSubtitle(0, 1, ""));
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            int posMs = positionProvider.getCurrentPositionMs();
            int idx = findCueIndexAt(posMs);
            if (idx != lastCueIndex) {
                lastCueIndex = idx;
                if (idx >= 0) {
                    Cue c = cues.get(idx);
                    listener.onSubtitle(new Subtitle.TimedTextSubtitle(c.startMs, Math.max(1, c.endMs - c.startMs), c.text));
                } else {
                    listener.onSubtitle(new Subtitle.TimedTextSubtitle(posMs, 1, ""));
                }
            }
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private int findCueIndexAt(int posMs) {
        // cues are sorted by startMs; small linear scan is fine for typical sizes
        for (int i = 0; i < cues.size(); i++) {
            Cue c = cues.get(i);
            if (posMs < c.startMs) return -1;
            if (posMs <= c.endMs) return i;
        }
        return -1;
    }

    // --- parsing ---

    private static class Cue {
        final int startMs;
        final int endMs;
        final String text;
        Cue(int startMs, int endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    private static List<Cue> parse(File file) {
        try {
            return parseByName(readText(file), file.getName());
        } catch (IOException e) {
            log.warn("ExternalSubtitleDriver.parse: failed to read {}", file, e);
            return null;
        }
    }

    /** Pick the right parser by filename extension and run it on the raw text. */
    private static List<Cue> parseByName(String content, String filenameForFormat) {
        String name = (filenameForFormat == null) ? "" : filenameForFormat.toLowerCase(Locale.US);
        if (name.endsWith(".srt") || name.endsWith(".vtt") || name.endsWith(".txt")) return parseSrtVtt(content);
        if (name.endsWith(".ass") || name.endsWith(".ssa")) return parseAss(content);
        if (name.endsWith(".smi")) return parseSmi(content);
        // Fall back to SRT/VTT parser — works for most well-formed subtitle files.
        return parseSrtVtt(content);
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            return readText(fis);
        }
    }

    private static String readText(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = is.read(tmp)) > 0) buf.write(tmp, 0, n);
        byte[] data = buf.toByteArray();
        // Strip UTF-8 BOM
        int offset = 0;
        if (data.length >= 3 && (data[0] & 0xff) == 0xEF && (data[1] & 0xff) == 0xBB && (data[2] & 0xff) == 0xBF) {
            offset = 3;
        }
        return new String(data, offset, data.length - offset, Charset.forName("UTF-8"));
    }

    /** Pattern matches both SRT (00:00:00,000) and VTT (00:00:00.000) timecodes. */
    private static final Pattern TC_PATTERN = Pattern.compile(
            "(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{1,3})");

    private static List<Cue> parseSrtVtt(String content) {
        List<Cue> out = new ArrayList<>();
        String[] lines = content.replace("\r", "").split("\n");
        int i = 0;
        while (i < lines.length) {
            Matcher m = TC_PATTERN.matcher(lines[i]);
            if (!m.find()) { i++; continue; }
            int start = toMs(m.group(1), m.group(2), m.group(3), m.group(4));
            int end   = toMs(m.group(5), m.group(6), m.group(7), m.group(8));
            i++;
            StringBuilder text = new StringBuilder();
            while (i < lines.length && !lines[i].isEmpty()) {
                if (text.length() > 0) text.append('\n');
                text.append(stripTags(lines[i]));
                i++;
            }
            if (end > start && text.length() > 0) out.add(new Cue(start, end, text.toString()));
        }
        Collections.sort(out, (a, b) -> Integer.compare(a.startMs, b.startMs));
        return out;
    }

    /** Strips HTML/SSA inline tags so plain text is shown. */
    private static String stripTags(String s) {
        // remove simple HTML tags <b>, <i>, <font>, <v Speaker>, etc.
        s = s.replaceAll("<[^>]+>", "");
        // remove SSA inline overrides {\an1} {\b1}...
        s = s.replaceAll("\\{[^}]*\\}", "");
        return s;
    }

    private static List<Cue> parseAss(String content) {
        List<Cue> out = new ArrayList<>();
        String[] lines = content.replace("\r", "").split("\n");
        int textIdx = -1; // column index for text within Dialogue:
        int startIdx = -1, endIdx = -1;
        boolean inEvents = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.equalsIgnoreCase("[Events]")) { inEvents = true; continue; }
            if (line.startsWith("[") && line.endsWith("]")) { inEvents = false; continue; }
            if (!inEvents) continue;
            if (line.toLowerCase(Locale.US).startsWith("format:")) {
                String[] cols = line.substring(7).split(",");
                for (int i = 0; i < cols.length; i++) {
                    String key = cols[i].trim().toLowerCase(Locale.US);
                    if (key.equals("start")) startIdx = i;
                    else if (key.equals("end")) endIdx = i;
                    else if (key.equals("text")) textIdx = i;
                }
                continue;
            }
            if (line.toLowerCase(Locale.US).startsWith("dialogue:") && textIdx > 0 && startIdx >= 0 && endIdx >= 0) {
                String body = line.substring("dialogue:".length()).trim();
                // text is everything after the (textIdx)-th comma — earlier columns may not contain commas in time fields
                String[] parts = body.split(",", textIdx + 1);
                if (parts.length <= textIdx) continue;
                int s = parseAssTime(parts[startIdx].trim());
                int e = parseAssTime(parts[endIdx].trim());
                if (e <= s) continue;
                String text = parts[textIdx];
                // ASS line breaks are "\N" or "\n"
                text = text.replace("\\N", "\n").replace("\\n", "\n");
                text = stripTags(text);
                if (!text.isEmpty()) out.add(new Cue(s, e, text));
            }
        }
        Collections.sort(out, (a, b) -> Integer.compare(a.startMs, b.startMs));
        return out;
    }

    private static int parseAssTime(String s) {
        // H:MM:SS.cs (centiseconds)
        try {
            String[] hms = s.split(":");
            if (hms.length != 3) return 0;
            int h = Integer.parseInt(hms[0]);
            int m = Integer.parseInt(hms[1]);
            String[] secCs = hms[2].split("\\.");
            int sec = Integer.parseInt(secCs[0]);
            int cs = secCs.length > 1 ? Integer.parseInt(secCs[1]) : 0;
            return ((h * 3600) + (m * 60) + sec) * 1000 + cs * 10;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<Cue> parseSmi(String content) {
        List<Cue> out = new ArrayList<>();
        // SMI: <SYNC Start=12345>...<P>text</P>
        Pattern syncP = Pattern.compile("<SYNC\\s+Start=(\\d+)\\s*>(.*?)(?=(?:<SYNC\\s)|\\z)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = syncP.matcher(content);
        List<int[]> syncs = new ArrayList<>(); // {startMs, blockIndex}
        List<String> blocks = new ArrayList<>();
        while (m.find()) {
            int t = Integer.parseInt(m.group(1));
            String body = m.group(2);
            // strip HTML and special &nbsp;
            body = body.replaceAll("(?i)<br\\s*/?>", "\n");
            body = body.replaceAll("<[^>]+>", "");
            body = body.replaceAll("&nbsp;", " ").replace("&amp;", "&").trim();
            syncs.add(new int[]{t, blocks.size()});
            blocks.add(body);
        }
        for (int i = 0; i < syncs.size(); i++) {
            int start = syncs.get(i)[0];
            int end = (i + 1 < syncs.size()) ? syncs.get(i + 1)[0] : start + 5000;
            String text = blocks.get(i);
            if (end > start && !text.isEmpty()) out.add(new Cue(start, end, text));
        }
        Collections.sort(out, (a, b) -> Integer.compare(a.startMs, b.startMs));
        return out;
    }

    private static int toMs(String h, String m, String s, String ms) {
        try {
            int hh = Integer.parseInt(h);
            int mm = Integer.parseInt(m);
            int ss = Integer.parseInt(s);
            int mss = Integer.parseInt(ms);
            // pad ms — "1" should be 100ms, "12" 120ms, "123" 123ms
            if (ms.length() == 1) mss *= 100;
            else if (ms.length() == 2) mss *= 10;
            return ((hh * 3600) + (mm * 60) + ss) * 1000 + mss;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
