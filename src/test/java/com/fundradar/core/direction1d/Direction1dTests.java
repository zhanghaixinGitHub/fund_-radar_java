package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.auth.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 合成数据验证截止/身份/模型；不能作为真实前向正确率证据。 */
class Direction1dTests {
    ObjectMapper json=new ObjectMapper();
    @AfterEach void clear(){CurrentUserContext.clear();}
    String payload() throws Exception {
        var p=json.createObjectNode();p.put("schema_version","DIRECTION_1D_EXPERIMENT_V1");p.put("protocol","DIRECTION_1D_V1");
        p.put("horizon_trading_days",1);p.put("target_definition","UNIT_NAV_DIRECTION_V1");p.put("kind","FORWARD_ORIGINAL");
        p.put("model_released",false);p.putNull("up_probability");p.put("fund_code","001632");
        p.put("base_nav_date","2026-09-11");p.put("target_nav_date","2026-09-14");p.put("latest_nav_date","2026-09-11");
        p.put("window_open_at","2026-09-11T18:00:00+08:00");p.put("deadline_at","2026-09-14T08:30:00+08:00");
        p.put("generated_at","2026-09-11T19:00:00+08:00");p.put("calendar_version",Direction1dCalendar.VERSION);
        var input=p.putObject("input");input.put("feature_as_of","2026-09-11T18:59:00+08:00");input.put("max_input_observed_at","2026-09-11T18:58:00+08:00");
        var values=input.putArray("values");for(var day:Direction1dCalendar.inputs(LocalDate.of(2026,9,11))) {
            var row=values.addObject();row.put("nav_date",day.toString());row.put("unit_nav","1.001");row.put("observed_at","2026-09-11T18:58:00+08:00");
        }
        var features=input.putArray("features");for(int i=0;i<7;i++)features.add(0.1);
        String inputRaw=json.writeValueAsString(input);p.put("input_json",inputRaw);p.put("input_hash",Direction1dPolicy.hash(inputRaw));
        var branches=p.putArray("branches");for(String branch:List.of("FIXED","WEEKLY")) {
            var b=branches.addObject();b.put("branch_id",branch);b.put("status","AVAILABLE");b.put("score",.6);b.put("predicted_direction","UP");
            b.put("model_id",UUID.randomUUID().toString());b.put("model_hash","a".repeat(64));b.put("trained_at","2026-09-10T12:00:00+08:00");
        }
        return json.writeValueAsString(p);
    }
    @Test void validatesIndependentOneDayContract() throws Exception {
        String raw=payload();assertNotNull(Direction1dPolicy.validate(json,raw,Direction1dPolicy.hash(raw),"001632",Instant.parse("2026-09-11T12:00:00Z")));
    }
    @Test void wrongTargetModelHashOrUserFailsClosed() throws Exception {
        String raw=payload();
        assertThrows(IllegalArgumentException.class,()->Direction1dPolicy.validate(json,raw,"bad","001632",Instant.now()));
        assertThrows(IllegalArgumentException.class,()->Direction1dPolicy.validate(json,raw,Direction1dPolicy.hash(raw),"006730",Instant.now()));
        for(String changed:List.of(raw.replace("\"horizon_trading_days\":1","\"horizon_trading_days\":20"),
                raw.replace("2026-09-14","2026-09-15"),raw.replace("\"up_probability\":null","\"up_probability\":0.9"),
                raw.replace("2026-09-10T12:00:00+08:00","2026-09-11T19:00:00+08:00")))
            assertThrows(IllegalArgumentException.class,()->Direction1dPolicy.validate(json,changed,Direction1dPolicy.hash(changed),"001632",Instant.now()));
    }
    @Test void deadlineEqualityAndLateCommitDoNotCount() {
        Instant deadline=Instant.parse("2026-09-14T00:30:00Z"),early=deadline.minusSeconds(1);
        assertEquals("VERIFIED",Direction1dPolicy.receipt(early,early,early,deadline));
        assertEquals("LATE_ARCHIVE",Direction1dPolicy.receipt(early,deadline,deadline,deadline));
        assertEquals("LATE_ARCHIVE",Direction1dPolicy.receipt(early,early,deadline,deadline));
    }
    @Test void completedModelCanEnterOpenWindowButFutureRegistrationCannot() throws Exception {
        var p=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(payload());
        p.put("activation_policy",Direction1dPolicy.ACTIVATION_POLICY);
        for(var value:p.path("branches")) {
            var branch=(com.fasterxml.jackson.databind.node.ObjectNode)value;
            branch.put("trained_at","2026-09-11T18:30:00+08:00");
            branch.put("registered_at","2026-09-11T18:31:00+08:00");
            branch.put("model_selected_at","2026-09-11T18:59:00+08:00");
        }
        String raw=p.toString();
        assertDoesNotThrow(()->Direction1dPolicy.validate(json,raw,Direction1dPolicy.hash(raw),"001632",Instant.parse("2026-09-11T12:00:00Z")));
        var fixed=(com.fasterxml.jackson.databind.node.ObjectNode)p.path("branches").get(0);
        for(String field:List.of("trained_at","registered_at","model_selected_at")) {
            String original=fixed.path(field).asText();fixed.put(field,"2026-09-11T20:00:00+08:00");
            String bad=p.toString();
            assertThrows(IllegalArgumentException.class,()->Direction1dPolicy.validate(json,bad,Direction1dPolicy.hash(bad),"001632",Instant.now()));
            fixed.put(field,original);
        }
        p.put("activation_policy","UNKNOWN");String unknown=p.toString();
        assertThrows(IllegalArgumentException.class,()->Direction1dPolicy.validate(json,unknown,Direction1dPolicy.hash(unknown),"001632",Instant.now()));
    }
    @Test void holidaysAndYearBoundaryUseFrozenCalendar() {
        assertEquals(LocalDate.of(2026,10,8),Direction1dCalendar.next(LocalDate.of(2026,9,30)));
        assertEquals(LocalDate.of(2026,1,5),Direction1dCalendar.next(LocalDate.of(2025,12,31)));
        assertThrows(IllegalArgumentException.class,()->Direction1dCalendar.next(LocalDate.of(2026,12,31)));
    }
    @Test void unauthenticatedAndForeignFollowNeverCallsPython() {
        var repo=mock(Direction1dRepository.class);var client=mock(Direction1dClient.class);
        var service=new Direction1dService(repo,client,true,true);
        assertThrows(RuntimeException.class,()->service.current("001632"));verifyNoInteractions(client,repo);
        UUID owner=UUID.randomUUID(),other=UUID.randomUUID();
        CurrentUserContext.set(new AuthenticatedUser(other,"test","测试",AccountRole.FUND_USER,Set.of(PermissionCode.FUND_READ,PermissionCode.WATCHLIST_SELF_READ)));
        when(repo.follows(owner,"001632")).thenReturn(true);
        assertThrows(RuntimeException.class,()->service.current("001632"));verifyNoInteractions(client);
    }
    @Test void cannotInjectUserOrOverrideThreshold() {
        var service=mock(Direction1dService.class);var controller=new Direction1dController(service,mock(Direction1dStatistics.class));
        assertThrows(IllegalArgumentException.class,()->controller.subscription(Map.of("enabled",true,"userId",UUID.randomUUID())));
        assertThrows(IllegalArgumentException.class,()->controller.generate("001632",Map.of("threshold",.1)));
        verifyNoInteractions(service);
    }
    @Test void labelHashDecimalDirectionAndRevisionAreIndependentlyChecked() throws Exception {
        var original=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(payload());
        original.put("task_key","synthetic-only");original.put("input_snapshot_id",UUID.randomUUID().toString());
        var frozen=(com.fasterxml.jackson.databind.node.ObjectNode)original.path("input").path("values").get(60);
        frozen.put("source_id",UUID.randomUUID().toString());frozen.put("version_id",UUID.randomUUID().toString());
        frozen.put("content_hash","a".repeat(64));frozen.put("expires_at","2027-01-01T00:00:00+08:00");
        var p=json.createObjectNode();p.put("task_key","synthetic-only");p.set("input_snapshot_id",original.path("input_snapshot_id"));
        p.put("target_nav_date","2026-09-14");p.put("target_definition","UNIT_NAV_DIRECTION_V1");p.put("kind","FORWARD_ORIGINAL");p.put("status","AVAILABLE");
        p.put("label_observed_at","2026-09-14T20:00:00+08:00");p.set("base_source",frozen.deepCopy());
        var target=frozen.deepCopy();target.put("nav_date","2026-09-14");target.put("unit_nav","1.001");target.put("content_hash","b".repeat(64));p.set("target_source",target);
        p.put("base_unit_nav","1.001");p.put("target_unit_nav","1.001");p.put("y",0);p.put("actual_direction","FLAT");p.put("nav_return","0.000000000000");
        p.put("base_revised",false);p.put("training_eligible",true);
        var envelope=json.createObjectNode();envelope.put("snapshot_id",UUID.randomUUID().toString());envelope.set("payload",p);
        java.util.function.Supplier<com.fasterxml.jackson.databind.JsonNode> signed=()->{String raw=p.toString();envelope.put("payload_json",raw);envelope.put("content_hash",Direction1dPolicy.hash(raw));return envelope;};
        Instant now=Instant.parse("2026-09-14T12:01:00Z");
        assertDoesNotThrow(()->Direction1dPolicy.validateLabel(json,signed.get(),original,now));
        p.put("actual_direction","UP");assertThrows(IllegalArgumentException.class,()->Direction1dPolicy.validateLabel(json,signed.get(),original,now));p.put("actual_direction","FLAT");
        p.put("nav_return","0.001");assertThrows(IllegalArgumentException.class,()->Direction1dPolicy.validateLabel(json,signed.get(),original,now));p.put("nav_return","0.000000000000");
        ((com.fasterxml.jackson.databind.node.ObjectNode)p.path("base_source")).put("content_hash","c".repeat(64));
        assertThrows(IllegalArgumentException.class,()->Direction1dPolicy.validateLabel(json,signed.get(),original,now));
        p.put("base_revised",true);p.put("training_eligible",false);assertDoesNotThrow(()->Direction1dPolicy.validateLabel(json,signed.get(),original,now));
        envelope.put("content_hash","0".repeat(64));assertThrows(IllegalArgumentException.class,()->Direction1dPolicy.validateLabel(json,envelope,original,now));
    }
}
