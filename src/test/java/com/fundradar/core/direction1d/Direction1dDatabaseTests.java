package com.fundradar.core.direction1d;

import com.fundradar.core.auth.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static org.junit.jupiter.api.Assertions.*;

/** 真实本地PostgreSQL事务测试；合成账户/样本在测试结束全部回滚，不计实际预测。 */
@SpringBootTest(properties={"direction1d.enabled=false","simulation.enabled=false","portfolio.advice.enabled=false"})
@Transactional
class Direction1dDatabaseTests {
    @Autowired Direction1dRepository repo;
    @Autowired Direction1dStatistics stats;
    @Autowired JdbcClient db;
    UUID user,other,forecast;
    @BeforeEach void setup() {
        user=createUser();other=createUser();forecast=UUID.randomUUID();
        db.sql("INSERT INTO watchlist_item(watchlist_item_id,user_id,fund_code,fund_type) VALUES(:id,:u,'123456','STOCK')")
                .param("id",UUID.randomUUID()).param("u",user).update();
        repo.subscribe(user,true);
        UUID scope=repo.scope(user,LocalDate.of(2026,9,14),List.of(Map.of("fund_code","123456","fund_type","STOCK")));
        String raw="{\"fund_code\":\"123456\",\"group_id\":\"CN_EQUITY\",\"product_family_id\":\"synthetic-family\",\"input\":{\"event_status\":\"UNKNOWN\"},\"kind\":\"REPLAY_DIAGNOSTIC\"}";
        db.sql("""
          INSERT INTO direction_1d_forecast(forecast_id,source_job_id,protocol,cohort_id,fund_code,base_nav_date,target_nav_date,
            calendar_version,window_open_at,deadline_at,payload_json,payload,input_hash,content_hash,generated_at)
          VALUES(:id,:job,'TEST_ONLY',:cohort,'123456','2026-09-11','2026-09-14',:hash,clock_timestamp()-interval '1 hour',
            clock_timestamp()+interval '1 day',:raw,CAST(:raw AS jsonb),:hash,:content,clock_timestamp())
          """).param("id",forecast).param("job",UUID.randomUUID()).param("cohort",UUID.randomUUID().toString())
                .param("hash","a".repeat(64)).param("raw",raw).param("content",Direction1dPolicy.hash(raw)).update();
        for(String branch:List.of("FIXED","WEEKLY")) db.sql("INSERT INTO direction_1d_forecast_score(forecast_id,branch_id,score,predicted_direction,status) VALUES(:id,:branch,0.7,'UP','AVAILABLE')")
                .param("id",forecast).param("branch",branch).update();
        db.sql("INSERT INTO direction_1d_forecast_receipt(forecast_id,receipt_verified_at,content_hash,status) VALUES(:id,clock_timestamp(),:hash,'VERIFIED')")
                .param("id",forecast).param("hash",Direction1dPolicy.hash(raw)).update();
        repo.link(user,forecast,scope);repo.link(user,forecast,scope);
    }
    UUID createUser() {
        UUID id=UUID.randomUUID();db.sql("INSERT INTO user_account(user_id,mobile,display_name,password_hash,role,status) VALUES(:u,:m,'一日回滚测试','synthetic-not-login','FUND_USER','ACTIVE')")
                .param("u",id).param("m","139"+String.format("%08d",ThreadLocalRandom.current().nextInt(100000000))).update();return id;
    }
    @AfterEach void clear(){CurrentUserContext.clear();}
    @Test void ownerIsolationPaginationAndPendingNotWrong() {
        assertEquals(1L,repo.history(user,null,LocalDate.of(2021,1,1),LocalDate.of(2026,12,31),1,20).get("totalCount"));
        assertEquals(0L,repo.history(other,null,LocalDate.of(2021,1,1),LocalDate.of(2026,12,31),1,20).get("totalCount"));
        assertThrows(NoSuchElementException.class,()->repo.detail(other,forecast));
        var result=stats.read(user,LocalDate.of(2021,1,1),LocalDate.of(2026,12,31));
        @SuppressWarnings("unchecked") var branches=(List<Map<String,Object>>)result.get("branches");
        assertEquals(2,branches.size());
        for(var b:branches){assertEquals(0L,b.get("assessed_count"));assertEquals(1L,b.get("pending_count"));assertNull(b.get("accuracy"));}
    }
    @Test void cancellationAndPauseKeepOldAuthorizedHistory() {
        repo.subscribe(user,false);db.sql("DELETE FROM watchlist_item WHERE user_id=:u").param("u",user).update();
        assertEquals(forecast,repo.detail(user,forecast).get("forecastId"));assertFalse(repo.enabled(user));
    }
    @Test void allNewColumnsHavePhysicalChineseComments() {
        long missing=db.sql("""
          SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
          JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
          WHERE n.nspname='public' AND c.relkind='r' AND c.relname LIKE 'direction_1d_%'
            AND col_description(c.oid,a.attnum) IS NULL
          """).query(Long.class).single();assertEquals(0,missing);
    }
    @Test void originalForecastCannotBeUpdated() {
        assertThrows(RuntimeException.class,()->db.sql("UPDATE direction_1d_forecast SET payload_json='{}' WHERE forecast_id=:id").param("id",forecast).update());
    }
    @Test void sourceRevisionNeverChangesFirstStatisticsAndCursorDoesNotRepeat() {
        for(int revision=1;revision<=2;revision++) db.sql("""
          INSERT INTO direction_1d_outcome(forecast_id,revision_no,label_snapshot_id,label_hash,payload,base_unit_nav,target_unit_nav,y,
            actual_direction,nav_return,label_observed_at,revision_reason)
          VALUES(:id,:revision,:snapshot,:hash,'{}',1,:nav,:y,:direction,:ret,clock_timestamp(),'SYNTHETIC_ROLLBACK_ONLY')
          """).param("id",forecast).param("revision",revision).param("snapshot",UUID.randomUUID()).param("hash",(""+revision).repeat(64))
                .param("nav",revision==1?1.1:0.9).param("y",revision==1?1:0).param("direction",revision==1?"UP":"DOWN").param("ret",revision==1?0.1:-0.1).update();
        for(String basis:List.of("FIRST_OBSERVED","LATEST_REVISION")) {
            var result=stats.read(user,LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),basis);
            @SuppressWarnings("unchecked") var branches=(List<Map<String,Object>>)result.get("branches");
            assertEquals("FIRST_OBSERVED".equals(basis)?1L:0L,branches.get(0).get("correct_count"));
        }
        var next=repo.history(user,null,LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),2,20,LocalDate.of(2026,9,14),forecast,"FIXED","ASSESSED");
        assertEquals(List.of(),next.get("items"));assertEquals(1L,next.get("totalCount"));
    }
}
