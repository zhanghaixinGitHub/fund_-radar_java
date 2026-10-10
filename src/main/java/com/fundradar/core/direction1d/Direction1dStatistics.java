package com.fundradar.core.direction1d;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.*;

/** 所有统计仅取本人真实关联、首次答案；日期与家族重复权重在数据库内计算。 */
@Service
public class Direction1dStatistics {
    private final JdbcClient db;
    public Direction1dStatistics(JdbcClient db){this.db=db;}
    public Map<String,Object> read(UUID user,LocalDate start,LocalDate end) {
        return read(user,start,end,"FIRST_OBSERVED");
    }
    public Map<String,Object> read(UUID user,LocalDate start,LocalDate end,String labelBasis) {
        return read(user,start,end,labelBasis,"LAST_VALID");
    }
    /** 先固定每基金每日的首次或最后有效预测，再核对答案，不能事后挑最准的版本。 */
    public Map<String,Object> read(UUID user,LocalDate start,LocalDate end,String labelBasis,String predictionBasis) {
        if(!Set.of("FIRST_VALID","LAST_VALID").contains(predictionBasis))throw new IllegalArgumentException("INVALID_PREDICTION_BASIS");
        if(!Set.of("FIRST_OBSERVED","LATEST_REVISION").contains(labelBasis))throw new IllegalArgumentException("INVALID_LABEL_BASIS");
        String answer="FIRST_OBSERVED".equals(labelBasis)?"1":"(SELECT max(revision_no) FROM direction_1d_outcome WHERE forecast_id=f.forecast_id)";
        if(start.isAfter(end))throw new IllegalArgumentException("INVALID_RANGE");
        String order="FIRST_VALID".equals(predictionBasis)?"ASC":"DESC";
        // 排序方向仅由上方白名单产生；用户输入不拼接进 SQL。
        String selection="""
          WITH selected AS (
            SELECT forecast_id FROM (
              SELECT f.forecast_id,row_number() OVER(PARTITION BY f.protocol,f.fund_code,f.target_nav_date
                ORDER BY f.revision_sequence %s,f.stored_at %s,f.forecast_id %s) AS choice
              FROM direction_1d_forecast f JOIN direction_1d_user_forecast u USING(forecast_id)
              JOIN direction_1d_forecast_receipt r USING(forecast_id)
              WHERE u.user_id=:u AND f.target_nav_date BETWEEN :start AND :end AND r.status='VERIFIED'
                AND r.content_hash=f.content_hash AND encode(sha256(convert_to(f.payload_json,'UTF8')),'hex')=f.content_hash
                AND f.generated_at<f.deadline_at AND f.stored_at<f.deadline_at AND r.receipt_verified_at<f.deadline_at
            ) ranked WHERE choice=1
          )
          """.formatted(order,order,order);
        String rows=selection+"""
          , raw AS (
            SELECT f.protocol,f.forecast_id,f.fund_code,f.target_nav_date,s.branch_id,s.model_id,s.predicted_direction,
              CASE WHEN f.protocol IN ('DIRECTION_1D_V2','DIRECTION_1D_ANALYSIS_V1') THEN s.predicted_direction=o.actual_direction
                ELSE (s.predicted_direction='UP')=(o.y=1) END AS correct,
              f.payload->>'group_id' AS group_id,f.payload->>'product_family_id' AS family,
              COALESCE(o.payload->>'event_status',f.payload->'input'->>'event_status') AS event_status,o.y,o.actual_direction,
              r.status='VERIFIED' AND s.status='AVAILABLE' AND o.y IS NOT NULL
                AND encode(sha256(convert_to(f.payload_json,'UTF8')),'hex')=f.content_hash
                AND COALESCE((f.payload->>'expires_at')::timestamptz,'infinity'::timestamptz)>clock_timestamp() AS valid,
              s.status='AVAILABLE' AS available,
              count(*) FILTER(WHERE r.status='VERIFIED' AND s.status='AVAILABLE' AND o.y IS NOT NULL)
                OVER(PARTITION BY f.protocol,s.branch_id,f.target_nav_date,f.payload->>'product_family_id') AS family_shares
            FROM direction_1d_user_forecast u JOIN direction_1d_forecast f USING(forecast_id)
            JOIN selected USING(forecast_id)
            JOIN direction_1d_forecast_score s USING(forecast_id)
            LEFT JOIN direction_1d_forecast_receipt r USING(forecast_id)
            LEFT JOIN direction_1d_outcome o ON o.forecast_id=f.forecast_id AND o.revision_no=1
            WHERE u.user_id=:u AND f.target_nav_date BETWEEN :start AND :end
          ), expanded AS (
            SELECT raw.*,dimension.kind,dimension.key FROM raw CROSS JOIN LATERAL (VALUES
              ('TOTAL','ALL'),('FUND',fund_code),('ASSET_GROUP',group_id),
              ('MONTH',to_char(target_nav_date,'YYYY-MM')),('MODEL',COALESCE(model_id::text,'BASELINE')),
              ('EVENT',COALESCE(event_status,'UNKNOWN'))) dimension(kind,key)
          )
          SELECT protocol,kind,key,branch_id,count(*) FILTER(WHERE valid) AS assessed_count,
            count(*) FILTER(WHERE valid AND correct) AS correct_count,
            count(DISTINCT target_nav_date) FILTER(WHERE valid) AS distinct_target_dates,
            count(*) FILTER(WHERE available AND y IS NULL) AS pending_count,
            count(*) FILTER(WHERE NOT available) AS unavailable_count,
            count(*) FILTER(WHERE valid AND actual_direction='FLAT') AS flat_count,
            avg(CASE WHEN valid THEN CASE WHEN correct THEN 1.0 ELSE 0.0 END END) AS accuracy,
            avg(CASE WHEN valid AND y=1 THEN CASE WHEN predicted_direction='UP' THEN 1.0 ELSE 0.0 END END) AS up_recall,
            avg(CASE WHEN valid AND protocol='DIRECTION_1D_V1' AND y=0 THEN CASE WHEN predicted_direction='NON_UP' THEN 1.0 ELSE 0.0 END END) AS non_up_recall,
            avg(CASE WHEN valid AND actual_direction='DOWN' THEN CASE WHEN predicted_direction='DOWN' THEN 1.0 ELSE 0.0 END END) AS down_recall,
            avg(CASE WHEN valid AND actual_direction='FLAT' THEN CASE WHEN predicted_direction='FLAT' THEN 1.0 ELSE 0.0 END END) AS flat_recall,
            CASE WHEN protocol IN ('DIRECTION_1D_V2','DIRECTION_1D_ANALYSIS_V1') THEN (
              avg(CASE WHEN valid AND actual_direction='UP' THEN CASE WHEN predicted_direction='UP' THEN 1.0 ELSE 0.0 END END)
              +avg(CASE WHEN valid AND actual_direction='DOWN' THEN CASE WHEN predicted_direction='DOWN' THEN 1.0 ELSE 0.0 END END)
              +avg(CASE WHEN valid AND actual_direction='FLAT' THEN CASE WHEN predicted_direction='FLAT' THEN 1.0 ELSE 0.0 END END))/3
            ELSE (avg(CASE WHEN valid AND y=1 THEN CASE WHEN predicted_direction='UP' THEN 1.0 ELSE 0.0 END END)
             +avg(CASE WHEN valid AND y=0 THEN CASE WHEN predicted_direction='NON_UP' THEN 1.0 ELSE 0.0 END END))/2 END AS balanced_accuracy,
            sum(CASE WHEN valid THEN (CASE WHEN correct THEN 1.0 ELSE 0.0 END)/family_shares END)
              /NULLIF(sum(CASE WHEN valid THEN 1.0/family_shares END),0) AS family_date_weighted_accuracy
          FROM expanded GROUP BY protocol,kind,key,branch_id ORDER BY CASE WHEN kind='TOTAL' THEN 0 ELSE 1 END,protocol,kind,key,branch_id LIMIT 1001
          """;
        var all=db.sql(rows.replace("o.revision_no=1","o.revision_no="+answer)).param("u",user).param("start",start).param("end",end).query().listOfRows();
        var pairs=db.sql(selection+"""
          SELECT count(*) FILTER(WHERE a.status='AVAILABLE' AND b.status='AVAILABLE') AS paired_count,
            count(*) FILTER(WHERE a.status='AVAILABLE' AND b.status<>'AVAILABLE') AS fixed_only_count,
            count(*) FILTER(WHERE a.status<>'AVAILABLE' AND b.status='AVAILABLE') AS weekly_only_count,
            count(*) FILTER(WHERE a.status='AVAILABLE' AND b.status='AVAILABLE' AND o.y IS NOT NULL) AS assessed_pair_count,
            sum(CASE WHEN a.status='AVAILABLE' AND b.status='AVAILABLE' AND o.y IS NOT NULL THEN
              (CASE WHEN b.predicted_direction=o.actual_direction THEN 1 ELSE 0 END)
              -(CASE WHEN a.predicted_direction=o.actual_direction THEN 1 ELSE 0 END) END) AS weekly_extra_correct
          FROM direction_1d_user_forecast u JOIN direction_1d_forecast f USING(forecast_id)
          JOIN selected USING(forecast_id)
          JOIN direction_1d_forecast_receipt r USING(forecast_id)
          JOIN direction_1d_forecast_score a ON a.forecast_id=f.forecast_id AND a.branch_id='FIXED'
          JOIN direction_1d_forecast_score b ON b.forecast_id=f.forecast_id AND b.branch_id='WEEKLY'
          LEFT JOIN direction_1d_outcome o ON o.forecast_id=f.forecast_id AND o.revision_no=1
          WHERE u.user_id=:u AND f.protocol='DIRECTION_1D_V2' AND r.status='VERIFIED' AND f.target_nav_date BETWEEN :start AND :end
            AND encode(sha256(convert_to(f.payload_json,'UTF8')),'hex')=f.content_hash
            AND COALESCE((f.payload->>'expires_at')::timestamptz,'infinity'::timestamptz)>clock_timestamp()
          """.replace("o.revision_no=1","o.revision_no="+answer)).param("u",user).param("start",start).param("end",end).query().singleRow();
        var coverage=db.sql("""
          WITH attempted AS (
            SELECT DISTINCT ON(a.fund_code,a.target_nav_date) a.fund_code,a.target_nav_date,a.status,a.reasons
            FROM direction_1d_attempt a JOIN direction_1d_scope_snapshot s ON s.snapshot_id=a.scope_snapshot_id
            WHERE s.user_id=:u AND a.target_nav_date BETWEEN :start AND :end
            ORDER BY a.fund_code,a.target_nav_date,a.attempted_at DESC,a.attempt_id DESC)
          SELECT count(*) AS checked_fund_days,
            count(*) FILTER(WHERE status NOT IN ('SPECIAL_POLICY_REQUIRED','GROUP_UNVERIFIED','SOURCE_UNAVAILABLE')) AS applicable_fund_days,
            count(*) FILTER(WHERE status='MISSED_DEADLINE') AS missed_deadline_count,
            count(*) FILTER(WHERE status='FAILED') AS failed_count,
            (SELECT count(DISTINCT (f.fund_code,f.target_nav_date)) FROM direction_1d_user_forecast u JOIN direction_1d_forecast f USING(forecast_id)
              JOIN direction_1d_forecast_receipt r USING(forecast_id)
              WHERE u.user_id=:u AND r.status='VERIFIED' AND r.content_hash=f.content_hash
                AND f.generated_at<f.deadline_at AND f.stored_at<f.deadline_at AND r.receipt_verified_at<f.deadline_at
                AND f.target_nav_date BETWEEN :start AND :end) AS verified_forecast_count
          FROM attempted
          """).param("u",user).param("start",start).param("end",end).query().singleRow();
        // 新分析分别保留目标日前与目标日的代表记录，避免盘中资料较多的结果混入前夜观察。
        var phases=db.sql("""
          WITH ranked AS (
            SELECT f.forecast_id,f.payload->>'analysis_phase' AS phase,
              row_number() OVER(PARTITION BY f.fund_code,f.target_nav_date,f.payload->>'analysis_phase'
                ORDER BY f.revision_sequence %s,f.stored_at %s,f.forecast_id %s) AS choice
            FROM direction_1d_forecast f JOIN direction_1d_user_forecast u USING(forecast_id)
            JOIN direction_1d_forecast_receipt r USING(forecast_id)
            WHERE u.user_id=:u AND f.protocol='DIRECTION_1D_ANALYSIS_V1' AND r.status='VERIFIED'
              AND r.content_hash=f.content_hash AND encode(sha256(convert_to(f.payload_json,'UTF8')),'hex')=f.content_hash
              AND f.target_nav_date BETWEEN :start AND :end
          )
          SELECT r.phase,count(*) FILTER(WHERE o.y IS NOT NULL) assessed_count,
            count(*) FILTER(WHERE o.y IS NULL) pending_count,
            count(*) FILTER(WHERE s.predicted_direction=o.actual_direction) correct_count
          FROM ranked r JOIN direction_1d_forecast f USING(forecast_id)
          JOIN direction_1d_forecast_score s ON s.forecast_id=f.forecast_id AND s.branch_id='PRIMARY'
          LEFT JOIN direction_1d_outcome o ON o.forecast_id=f.forecast_id AND o.revision_no=1
          WHERE r.choice=1 GROUP BY r.phase ORDER BY r.phase
          """.formatted(order,order,order).replace("o.revision_no=1","o.revision_no="+answer))
            .param("u",user).param("start",start).param("end",end).query().listOfRows();
        Map<String,Object> result=new LinkedHashMap<>(Map.of("branches",all.stream().filter(r->"TOTAL".equals(r.get("kind"))).toList(),
                "strata",all.stream().filter(r->!"TOTAL".equals(r.get("kind"))).limit(1000).toList(),"strataTruncated",all.size()>1000,
                "paired",pairs,"coverage",coverage,"startDate",start,"endDate",end,"labelBasis",labelBasis,"predictionBasis",predictionBasis,
                "observationNote","少于20个不同目标日属于很短观察期；60日后才适合更完整分析，均不自动发布。"));
        result.put("analysisPhases",phases);return result;
    }
}
