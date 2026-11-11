package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 人工跨年夹具只写JUnit临时目录，不是2027正式日历。 */
class Direction1dCalendarExtensionTests {
    @TempDir Path directory;
    @AfterEach void clear(){System.clearProperty("fund.calendar.manifest");}
    @Test void acceptsVerifiedExtensionAndRetainsHistoricalVersion() throws Exception {
        var json=new ObjectMapper();
        List<String> sessions=new ArrayList<>();
        try(var stream=Direction1dCalendar.class.getResourceAsStream("/direction1d-calendar.json")) {
            json.readTree(stream).path("sessions").forEach(d->sessions.add(d.asText()));
        }
        sessions.add("2027-01-04");sessions.add("2027-01-05");
        String version="a".repeat(64);
        String raw=json.writeValueAsString(Map.of("version",version,"sessions",sessions));
        Path file=directory.resolve("test-java.json");Files.writeString(file,raw);
        Path manifest=directory.resolve("manifest.json");
        Files.writeString(manifest,json.writeValueAsString(Map.of("extensions",List.of(Map.of(
                "calendar_version",version,"java_file","test-java.json","java_sha256",Direction1dPolicy.hash(raw))))));
        System.setProperty("fund.calendar.manifest",manifest.toString());
        assertEquals(LocalDate.of(2027,1,4),Direction1dCalendar.next(LocalDate.of(2026,12,31),version));
        assertEquals(61,Direction1dCalendar.inputs(LocalDate.of(2027,1,4),version).size());
        assertThrows(IllegalArgumentException.class,()->Direction1dCalendar.next(LocalDate.of(2026,12,31),Direction1dCalendar.VERSION));
        Files.writeString(file,raw+" ");
        assertThrows(IllegalArgumentException.class,()->Direction1dCalendar.next(LocalDate.of(2026,12,31),version));
    }
    @Test void unregisteredFutureCalendarIsRejected() {
        assertThrows(IllegalArgumentException.class,()->Direction1dCalendar.next(LocalDate.of(2026,12,31),"b".repeat(64)));
    }
}
