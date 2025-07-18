package com.archos.mediacenter.video.browser.loader;

import android.content.Context;
import android.util.Log;

import com.archos.mediaprovider.video.VideoStore;

public class WatchingUpNextLoader extends VideoLoader {

    public WatchingUpNextLoader(Context context) {
        super(context);
        init();
        // cf. https://github.com/nova-video-player/aos-AVP/issues/134 reduce strain
        // only updates the CursorLoader on data change every 10s since used only in MainFragment as nonScraped box presence
        if (VideoLoader.ALLVIDEO_THROTTLE) setUpdateThrottle(VideoLoader.ALLVIDEO_THROTTLE_DELAY);
    }

    @Override
    public String getSelection() {
        StringBuilder builder = new StringBuilder();
        builder.append(super.getSelection());

        if (builder.length() > 0)
            builder.append(" AND ");

        // Index-optimized query: Restructured to leverage new performance indexes
        builder.append(
                "(" +
                // Part 1: Next episodes in TV series (optimized for idx_video_series_episode)
                "(s_id IS NOT NULL AND " + VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN + " = 0 AND archos_hiddenbyuser = 0 AND " +
                "EXISTS (" +
                    // Find the highest completed episode in the series (by season/episode number)
                    "SELECT 1 FROM video lw " +
                    "WHERE lw.s_id = video.s_id " +
                        "AND lw.archos_hiddenbyuser = 0 " +
                        "AND lw." + VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN + " = 1 " +
                        // Ensure this is the highest completed episode by ordering and taking the first
                        "AND NOT EXISTS (" +
                            "SELECT 1 FROM video lwhigher " +
                            "WHERE lwhigher.s_id = video.s_id " +
                                "AND lwhigher.archos_hiddenbyuser = 0 " +
                                "AND lwhigher." + VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN + " = 1 " +
                                "AND (" +
                                    "(lwhigher.e_season > lw.e_season) " +
                                    "OR (lwhigher.e_season = lw.e_season AND lwhigher.e_episode > lw.e_episode)" +
                                ")" +
                        ") " +
                        "AND (" +
                            // Same season, next episode (uses series_episode index)
                            "(lw.e_season = video.e_season AND lw.e_episode = video.e_episode - 1) " +
                            "OR " +
                            // Previous season, last episode (uses series_episode index)
                            "(lw.e_season = video.e_season - 1 AND video.e_episode = 1 " +
                                "AND NOT EXISTS (SELECT 1 FROM video v3 WHERE v3.s_id = lw.s_id " +
                                    "AND v3.e_season = video.e_season AND v3.e_episode < video.e_episode))" +
                        ") " +
                        "LIMIT 1" +
                "))" +
                " OR " +
                // Part 2: Next movies in collections (optimized for idx_video_collection_year)
                "(m_coll_id IS NOT NULL AND " + VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN + " = 0 AND archos_hiddenbyuser = 0 AND " +
                "EXISTS (" +
                    // Find the highest completed movie in the collection (by year)
                    "SELECT 1 FROM video lw " +
                    "WHERE lw.m_coll_id = video.m_coll_id " +
                        "AND lw.archos_hiddenbyuser = 0 " +
                        "AND lw." + VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN + " = 1 " +
                        // Ensure this is the highest completed movie by year
                        "AND lw.m_year = (" +
                            "SELECT MAX(m_year) FROM video lwmax " +
                            "WHERE lwmax.m_coll_id = video.m_coll_id " +
                                "AND lwmax.archos_hiddenbyuser = 0 " +
                                "AND lwmax." + VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN + " = 1" +
                        ") " +
                        "AND lw.m_year < video.m_year " +
                        // Ensure this is the next movie chronologically
                        "AND NOT EXISTS (SELECT 1 FROM video v4 WHERE v4.m_coll_id = lw.m_coll_id " +
                            "AND v4.m_year > lw.m_year AND v4.m_year < video.m_year " +
                            "AND v4." + VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN + " = 0 AND v4.archos_hiddenbyuser = 0) " +
                        "LIMIT 1" +
                "))" +
                " OR " +
                // Part 3: Currently watching videos (partially watched, not completed)
                "(bookmark > 0 AND " + VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN + " = 0 AND archos_hiddenbyuser = 0)" +
                ")"
        );

        return builder.toString();
    }

    /*
    @Override
    public String getSortOrder() {
        return "CASE WHEN " + VideoStore.Video.VideoColumns.BOOKMARK + " > 0 THEN 0 ELSE 1 END, " + VideoStore.Video.VideoColumns.ARCHOS_LAST_TIME_PLAYED + " DESC, " + VideoLoader.DEFAULT_SORT + " LIMIT 100";
    }
     */
    @Override
    public String getSortOrder() {
        // Optimized sort that prioritizes currently watching videos, then up next videos
        return "CASE " +
            // Priority 1: Currently watching videos (have bookmarks but not completed) - sort by most recent play time
            "WHEN bookmark > 0 AND " + VideoStore.Video.VideoColumns.ARCHOS_TRAKT_SEEN + " = 0 THEN 1000000 + Archos_lastTimePlayed " +
            // Priority 2: Next episodes/movies - sort by most recent related play time
            "ELSE COALESCE(" +
                // For series: get most recent episode play time (uses idx_video_last_played_desc)
                "(SELECT MAX(lw.Archos_lastTimePlayed) FROM video lw " +
                    "WHERE lw.s_id = video.s_id AND lw.Archos_lastTimePlayed > 1), " +
                // For collections: get most recent movie play time (uses idx_video_last_played_desc)
                "(SELECT MAX(lw.Archos_lastTimePlayed) FROM video lw " +
                    "WHERE lw.m_coll_id = video.m_coll_id AND lw.Archos_lastTimePlayed > 1), " +
                "0" +
            ") " +
        "END DESC LIMIT 100";
    }
}