package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.nio.file.*;
import java.util.*;

/** 从Python已核验2021—2026日历导出的独立Java校验副本；内容损坏即停止。 */
public final class Direction1dCalendar {
    public static final String VERSION="4cb07f1a22092f38dad6f2100edbefb3bdc3dc1f925152afba5bc1fc3fbbffb2";
    private static final String FILE_HASH="c1543765f35ecb0e445ab57ff4153241c326b840b1f5407a06d187ab98b7f2cf";
    private static final List<LocalDate> DAYS=load();
    private Direction1dCalendar() {}
    private static List<LocalDate> load() {
        try(var input=Direction1dCalendar.class.getResourceAsStream("/direction1d-calendar.json")) {
            if(input==null)throw new IllegalStateException("CALENDAR_UNAVAILABLE");
            String raw=new String(input.readNBytes(65537),StandardCharsets.UTF_8);
            if(!FILE_HASH.equals(Direction1dPolicy.hash(raw)))throw new IllegalStateException("CALENDAR_HASH_MISMATCH");
            var node=new ObjectMapper().readTree(raw);List<LocalDate> days=new ArrayList<>();
            node.path("sessions").forEach(d->days.add(LocalDate.parse(d.asText())));return List.copyOf(days);
        } catch(Exception error){throw new IllegalStateException("CALENDAR_UNAVAILABLE",error);}
    }
    public static List<LocalDate> inputs(LocalDate base) {
        int index=DAYS.indexOf(base);if(index<60)throw new IllegalArgumentException("CALENDAR_UNAVAILABLE");
        return DAYS.subList(index-60,index+1);
    }
    public static LocalDate next(LocalDate base) {
        int index=DAYS.indexOf(base);if(index<0||index+1>=DAYS.size())throw new IllegalArgumentException("CALENDAR_UNAVAILABLE");
        return DAYS.get(index+1);
    }

    /** 新年历按文件摘要独立加载；历史预测仍使用原来绑定的日历版本。 */
    private static List<LocalDate> versioned(String version) {
        if(VERSION.equals(version)) return DAYS;
        String configured=System.getProperty("fund.calendar.manifest");
        if(configured==null) throw new IllegalArgumentException("CALENDAR_VERSION_UNAVAILABLE");
        try {
            Path manifest=Path.of(configured).toAbsolutePath().normalize();
            if(Files.size(manifest)>65536) throw new IllegalArgumentException("CALENDAR_MANIFEST_SIZE");
            var json=new ObjectMapper();
            for(var entry:json.readTree(Files.readString(manifest)).path("extensions")) {
                if(!version.equals(entry.path("calendar_version").asText())) continue;
                Path file=manifest.getParent().resolve(entry.path("java_file").asText()).normalize();
                if(!file.getParent().equals(manifest.getParent()) || Files.size(file)>131072)
                    throw new IllegalArgumentException("CALENDAR_EXTENSION_PATH_OR_SIZE");
                String raw=Files.readString(file);
                if(!Direction1dPolicy.hash(raw).equals(entry.path("java_sha256").asText()))
                    throw new IllegalArgumentException("CALENDAR_EXTENSION_HASH_MISMATCH");
                var node=json.readTree(raw);List<LocalDate> days=new ArrayList<>();
                node.path("sessions").forEach(d->days.add(LocalDate.parse(d.asText())));
                if(!version.equals(node.path("version").asText()) || days.size()<=DAYS.size()
                        || !days.subList(0,DAYS.size()).equals(DAYS)
                        || !new ArrayList<>(new TreeSet<>(days)).equals(days))
                    throw new IllegalArgumentException("CALENDAR_EXTENSION_INVALID");
                return List.copyOf(days);
            }
        } catch(java.io.IOException error) { throw new IllegalArgumentException("CALENDAR_EXTENSION_UNAVAILABLE",error); }
        throw new IllegalArgumentException("CALENDAR_VERSION_UNAVAILABLE");
    }
    public static List<LocalDate> inputs(LocalDate base,String version) {
        var days=versioned(version);int index=days.indexOf(base);
        if(index<60) throw new IllegalArgumentException("CALENDAR_UNAVAILABLE");
        return days.subList(index-60,index+1);
    }
    public static LocalDate next(LocalDate base,String version) {
        var days=versioned(version);int index=days.indexOf(base);
        if(index<0 || index+1>=days.size()) throw new IllegalArgumentException("CALENDAR_UNAVAILABLE");
        return days.get(index+1);
    }
}
