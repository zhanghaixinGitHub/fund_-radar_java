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
@SpringBootTest(properties={"direction1d.initial-delay=PT24H","simulation.enabled=false","portfolio.advice.enabled=false"})
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
        selectCurrent(forecast);
    }
    /** 合成记录也必须走当前引用的数据库截止保护；全部写入由测试事务回滚。 */
    void selectCurrent(UUID id) {
        db.sql("""
          INSERT INTO direction_1d_current(protocol,fund_code,target_nav_date,forecast_id,revision_sequence)
          SELECT protocol,fund_code,target_nav_date,forecast_id,revision_sequence FROM direction_1d_forecast WHERE forecast_id=:id
          ON CONFLICT(protocol,fund_code,target_nav_date) DO UPDATE
          SET forecast_id=EXCLUDED.forecast_id,revision_sequence=EXCLUDED.revision_sequence
          """).param("id",id).update();
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
    @Test void threeStateFlatMissesDownAndLegacyNonUpStillMatchesDown() {
        UUID scope=repo.scope(user,LocalDate.of(2026,9,14),List.of());
        UUID newer=null;
        for(String protocol:List.of("DIRECTION_1D_V1","DIRECTION_1D_V2")) {
            UUID id=UUID.randomUUID();if(protocol.endsWith("V2"))newer=id;
            db.sql("""
              INSERT INTO direction_1d_forecast(forecast_id,source_job_id,protocol,cohort_id,fund_code,base_nav_date,target_nav_date,
                calendar_version,window_open_at,deadline_at,payload_json,payload,input_hash,content_hash,generated_at)
              SELECT :id,:job,:protocol,:cohort,fund_code,base_nav_date,target_nav_date,calendar_version,window_open_at,
                deadline_at,payload_json,payload,input_hash,content_hash,generated_at FROM direction_1d_forecast WHERE forecast_id=:source
              """).param("id",id).param("job",UUID.randomUUID()).param("protocol",protocol)
                    .param("cohort",UUID.randomUUID().toString()).param("source",forecast).update();
            db.sql("""
              INSERT INTO direction_1d_forecast_score(forecast_id,branch_id,score,predicted_direction,status)
              VALUES(:id,'FIXED',.6,:direction,'AVAILABLE')
              """).param("id",id).param("direction",protocol.endsWith("V2")?"FLAT":"NON_UP").update();
            db.sql("""
              INSERT INTO direction_1d_forecast_receipt(forecast_id,receipt_verified_at,content_hash,status)
              SELECT :id,receipt_verified_at,content_hash,status FROM direction_1d_forecast_receipt WHERE forecast_id=:source
              """).param("id",id).param("source",forecast).update();
            repo.link(user,id,scope);
            selectCurrent(id);
            db.sql("""
              INSERT INTO direction_1d_outcome(forecast_id,revision_no,label_snapshot_id,label_hash,payload,base_unit_nav,
                target_unit_nav,y,actual_direction,nav_return,label_observed_at,revision_reason)
              VALUES(:id,1,:snapshot,:hash,'{}',1,.99,0,'DOWN',-.01,clock_timestamp(),'SYNTHETIC_ROLLBACK_ONLY')
              """).param("id",id).param("snapshot",UUID.randomUUID()).param("hash","b".repeat(64)).update();
        }
        assertEquals(newer,repo.currentPublic("123456",LocalDate.of(2026,9,14)));
        var current=repo.currentHistory(user,"123456");
        assertEquals(newer,((Map<?,?>)((List<?>)current.get("items")).get(0)).get("forecastId"));
        for(var row:repo.metrics(user)) {
            if("DIRECTION_1D_V2".equals(row.get("protocol")))assertEquals(0L,row.get("correct_count"));
            if("DIRECTION_1D_V1".equals(row.get("protocol")))assertEquals(1L,row.get("correct_count"));
        }
        var result=stats.read(user,LocalDate.of(2026,1,1),LocalDate.of(2026,12,31));
        for(Object item:(List<?>)result.get("branches")) {
            var row=(Map<?,?>)item;
            if("DIRECTION_1D_V2".equals(row.get("protocol"))) {
                assertEquals(0L,row.get("correct_count"));assertNotNull(row.get("down_recall"));assertNull(row.get("flat_recall"));
            }
            if("DIRECTION_1D_V1".equals(row.get("protocol")))assertEquals(1L,row.get("correct_count"));
        }
    }
    @Test void cancellationKeepsOldAuthorizedHistory() {
        db.sql("DELETE FROM watchlist_item WHERE user_id=:u").param("u",user).update();
        assertEquals(forecast,repo.detail(user,forecast).get("forecastId"));
    }
    @Test void noSubscriptionAndOldDisabledSubscriptionBothParticipateButInactiveUsersDoNot() {
        assertTrue(repo.users(null).contains(user));
        assertTrue(repo.predictionFundCodes("").contains("123456"));
        assertEquals(List.of(user),repo.predictionUsers("123456",null));
        db.sql("INSERT INTO direction_1d_subscription(user_id,enabled) VALUES(:u,false)").param("u",user).update();
        assertTrue(repo.users(null).contains(user));
        // 旧开关关闭也能关联新预测；仍只关联当前关注的基金。
        db.sql("DELETE FROM direction_1d_user_forecast WHERE user_id=:u").param("u",user).update();
        UUID scope=repo.scope(user,LocalDate.of(2026,9,14),List.of());
        repo.link(user,forecast,scope);
        assertEquals(forecast,repo.detail(user,forecast).get("forecastId"));
        db.sql("UPDATE user_account SET status='DISABLED' WHERE user_id=:u").param("u",user).update();
        assertFalse(repo.users(null).contains(user));
        assertEquals(List.of(),repo.predictionUsers("123456",null));
    }
    @Test void allNewColumnsHavePhysicalChineseComments() {
        long missing=db.sql("""
          SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
          JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
          WHERE n.nspname=current_schema() AND c.relkind='r' AND c.relname LIKE 'direction_1d_%'
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

    UUID additionalRevision(long sequence,boolean late) {
        UUID id=UUID.randomUUID();
        String raw="{\"kind\":\"SYNTHETIC_ROLLBACK_ONLY\",\"revision_sequence\":"+sequence+"}";
        db.sql("""
          INSERT INTO direction_1d_forecast(forecast_id,source_job_id,protocol,cohort_id,fund_code,base_nav_date,target_nav_date,
            calendar_version,window_open_at,deadline_at,payload_json,payload,input_hash,content_hash,generated_at,
            revision_sequence,input_identity)
          SELECT :id,:job,protocol,cohort_id,fund_code,base_nav_date,target_nav_date,calendar_version,window_open_at,
            CASE WHEN :late THEN clock_timestamp()-interval '1 second' ELSE deadline_at END,
            :raw,CAST(:raw AS jsonb),input_hash,:hash,generated_at,:sequence,:identity
          FROM direction_1d_forecast WHERE forecast_id=:original
          """).param("id",id).param("job",UUID.randomUUID()).param("late",late).param("raw",raw)
                .param("hash",Direction1dPolicy.hash(raw)).param("sequence",sequence).param("identity","c".repeat(64))
                .param("original",forecast).update();
        db.sql("INSERT INTO direction_1d_forecast_receipt VALUES(:id,clock_timestamp(),:hash,'VERIFIED')")
                .param("id",id).param("hash",Direction1dPolicy.hash(raw)).update();
        return id;
    }

    @Test void pointerRejectsLateAndBackwardResults() {
        UUID newer=additionalRevision(2,false);selectCurrent(newer);
        UUID older=additionalRevision(1,false);
        db.sql("SAVEPOINT stale_pointer").update();
        assertThrows(RuntimeException.class,()->selectCurrent(older));
        db.sql("ROLLBACK TO SAVEPOINT stale_pointer").update();
        assertEquals(newer,db.sql("SELECT forecast_id FROM direction_1d_current WHERE protocol='TEST_ONLY'")
                .query(UUID.class).single());
        UUID late=additionalRevision(3,true);
        db.sql("SAVEPOINT late_pointer").update();
        assertThrows(RuntimeException.class,()->selectCurrent(late));
        db.sql("ROLLBACK TO SAVEPOINT late_pointer").update();
        assertEquals(newer,db.sql("SELECT forecast_id FROM direction_1d_current WHERE protocol='TEST_ONLY'")
                .query(UUID.class).single());
    }

    @Test void firstAndLastPredictionsHaveSeparateOnePerDayStatistics() {
        UUID newer=additionalRevision(1,false);
        UUID scope=repo.scope(user,LocalDate.of(2026,9,14),List.of());repo.link(user,newer,scope);
        db.sql("INSERT INTO direction_1d_forecast_score(forecast_id,branch_id,score,predicted_direction,status) "
                +"VALUES(:id,'FIXED',.7,'DOWN','AVAILABLE')").param("id",newer).update();
        for(UUID id:List.of(forecast,newer)) db.sql("""
          INSERT INTO direction_1d_outcome(forecast_id,revision_no,label_snapshot_id,label_hash,payload,base_unit_nav,
            target_unit_nav,y,actual_direction,nav_return,label_observed_at,revision_reason)
          VALUES(:id,1,:snapshot,:hash,'{}',1,.99,0,'DOWN',-.01,clock_timestamp(),'SYNTHETIC_ROLLBACK_ONLY')
          """).param("id",id).param("snapshot",UUID.randomUUID()).param("hash","d".repeat(64)).update();
        for(String basis:List.of("FIRST_VALID","LAST_VALID")) {
            var result=stats.read(user,LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),"FIRST_OBSERVED",basis);
            @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)result.get("branches");
            var fixed=rows.stream().filter(r->"FIXED".equals(r.get("branch_id"))).findFirst().orElseThrow();
            assertEquals(1L,fixed.get("assessed_count"));
            assertEquals("FIRST_VALID".equals(basis)?0L:1L,fixed.get("correct_count"));
        }
    }
}
