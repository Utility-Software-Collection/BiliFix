package com.xjw.bilifix.in.feature.subtitle;

import android.net.Uri;

import com.xjw.bilifix.in.core.HookApi;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Marks only AI-service tracks exposed by the compatibility request. */
final class RecoveredSubtitleLabeler {
    private static final String REPAIR_MARK = "由BiliFix修复";

    private final HookApi module;
    private final Class<?> videoSubtitleClass;
    private final Method getSubtitlesList;
    private final Method getTypeValue;
    private final Method getSubtitleUrl;
    private final Method getLanDoc;
    private final Method itemToBuilder;
    private final Method setLanDoc;
    private final Method itemBuild;
    private final Method videoToBuilder;
    private final Method setSubtitles;
    private final Method videoBuild;
    private static final Object UNCHANGED = new Object();
    private final Map<Object, Object> cache =
            Collections.synchronizedMap(new WeakHashMap<>());

    RecoveredSubtitleLabeler(
            HookApi module, Class<?> videoSubtitleClass, Class<?> subtitleItemClass)
            throws Throwable {
        this.module = module;
        this.videoSubtitleClass = videoSubtitleClass;
        Class<?> itemBuilderClass = module.load(
                subtitleItemClass.getClassLoader(), subtitleItemClass.getName() + "$b");
        Class<?> videoBuilderClass = module.load(
                videoSubtitleClass.getClassLoader(), videoSubtitleClass.getName() + "$b");
        getSubtitlesList = module.publicMethod(videoSubtitleClass, "getSubtitlesList");
        getTypeValue = module.publicMethod(subtitleItemClass, "getTypeValue");
        getSubtitleUrl = module.publicMethod(subtitleItemClass, "getSubtitleUrl");
        getLanDoc = module.publicMethod(subtitleItemClass, "getLanDoc");
        itemToBuilder = module.publicMethod(subtitleItemClass, "toBuilder");
        setLanDoc = module.publicMethod(itemBuilderClass, "setLanDoc", String.class);
        itemBuild = module.publicMethod(itemBuilderClass, "build");
        videoToBuilder = module.publicMethod(videoSubtitleClass, "toBuilder");
        setSubtitles = module.publicMethod(
                videoBuilderClass, "setSubtitles", int.class, subtitleItemClass);
        videoBuild = module.publicMethod(videoBuilderClass, "build");
    }

    Object labelRecoveredTracks(Object source) throws Throwable {
        if (source == null || !videoSubtitleClass.isInstance(source)) {
            return source;
        }
        synchronized (cache) {
            Object cached = cache.get(source);
            if (cached == UNCHANGED) {
                return source;
            }
            if (cached != null) {
                return cached;
            }
        }

        Object listValue = module.invoke(getSubtitlesList, source);
        if (!(listValue instanceof List)) {
            return source;
        }
        List<?> tracks = (List<?>) listValue;
        Object videoBuilder = null;
        int labeled = 0;
        for (int index = 0; index < tracks.size(); index++) {
            Object track = tracks.get(index);
            if (!isRecoveredAiTrack(track)) {
                continue;
            }
            String oldLabel = String.valueOf(module.invoke(getLanDoc, track));
            String newLabel = repairLabel(oldLabel);
            if (newLabel.equals(oldLabel)) {
                continue;
            }
            Object itemBuilder = module.invoke(itemToBuilder, track);
            module.invoke(setLanDoc, itemBuilder, newLabel);
            Object labeledTrack = module.invoke(itemBuild, itemBuilder);
            if (videoBuilder == null) {
                videoBuilder = module.invoke(videoToBuilder, source);
            }
            module.invoke(setSubtitles, videoBuilder, index, labeledTrack);
            labeled++;
        }

        Object result = videoBuilder == null
                ? source : module.invoke(videoBuild, videoBuilder);
        synchronized (cache) {
            cache.put(source, videoBuilder == null ? UNCHANGED : result);
        }
        if (labeled > 0) {
            module.info("AI subtitle recovered-track labels added: tracks=" + labeled);
        }
        return result;
    }

    private boolean isRecoveredAiTrack(Object track) throws Throwable {
        int type = ((Number) module.invoke(getTypeValue, track)).intValue();
        if (type != 1) {
            return false;
        }
        String rawUrl = String.valueOf(module.invoke(getSubtitleUrl, track));
        try {
            String normalized = rawUrl.startsWith("//") ? "https:" + rawUrl : rawUrl;
            String host = Uri.parse(normalized).getHost();
            return host != null && host.toLowerCase().contains("aisubtitle");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String repairLabel(String value) {
        if (value == null || value.isEmpty() || value.contains(REPAIR_MARK)) {
            return value == null ? "" : value;
        }
        if (value.endsWith("）")) {
            return value.substring(0, value.length() - 1) + " " + REPAIR_MARK + "）";
        }
        if (value.endsWith(")")) {
            return value.substring(0, value.length() - 1) + " " + REPAIR_MARK + ")";
        }
        return value + "（" + REPAIR_MARK + "）";
    }
}
