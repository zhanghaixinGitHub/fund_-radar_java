package com.fundradar.core.simulation;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 仅连接专用临时容器；会话临时表和合成用户，不加载 Spring/Flyway 或项目数据源配置。 */
@EnabledIfEnvironmentVariable(named="EARNINGS_TEST_DB",matches="isolated")
class SimulationEarningsRepositoryTests {
    SingleConnectionDataSource source;
    JdbcTemplate jdbc;
    SimulationRepository repo;
    final UUID user=UUID.randomUUID(),other=UUID.randomUUID();
    final LocalDate first=LocalDate.of(2026,9,7),next=first.plusDays(1);
    @BeforeEach void setup() {
        source=new SingleConnectionDataSource("jdbc:postgresql://127.0.0.1:55439/earnings_test","postgres","",true);
        jdbc=new JdbcTemplate(source); repo=new SimulationRepository(JdbcClient.create(jdbc),jdbc);
        assertEquals("earnings_test",jdbc.queryForObject("select current_database()",String.class));
        jdbc.execute("CREATE TEMP TABLE sim_order(user_id uuid,fund_code text,fund_name text,trade_date date,status text)");
        jdbc.execute("CREATE TEMP TABLE sim_daily_valuation(user_id uuid,fund_code text,valuation_date date,market_value numeric,cumulative_gain numeric,daily_gain numeric,primary key(user_id,fund_code,valuation_date))");
        jdbc.execute("CREATE TEMP TABLE sim_position(user_id uuid,fund_code text,snapshot text,issue text)");
        order(user,"000001","历史已清仓基金","CONFIRMED"); order(user,"000002","当前基金","CONFIRMED");
        order(user,"000003","已撤单基金","CANCELLED"); order(other,"999999","其他用户基金","CONFIRMED");
        daily(user,"000001",first,"0","5","5"); daily(user,"000002",first,"100","0","0");
        daily(user,"000002",next,"103","3","3"); daily(other,"999999",first,"10000","9000","9000");
    }
    void order(UUID owner,String code,String name,String status) {
        jdbc.update("INSERT INTO sim_order VALUES (?,?,?,?,?)",owner,code,name,first,status);
    }
    void daily(UUID owner,String code,LocalDate date,String value,String gain,String day) {
        jdbc.update("INSERT INTO sim_daily_valuation VALUES (?,?,?,?,?,?)",owner,code,date,new BigDecimal(value),new BigDecimal(gain),day==null ? null : new BigDecimal(day));
    }
    @AfterEach void close() { if(source!=null) source.destroy(); }
    @Test void actualSqlPreservesClearedHistoryAndIsolatesEveryQuery() {
        var funds=repo.earningsFunds(user,null);
        assertEquals(List.of("000001","000002"),funds.stream().map(SimulationEarnings.Fund::fundCode).toList());
        assertEquals("历史已清仓基金",funds.get(0).fundName());
        assertTrue(repo.earningsFunds(other,"000001").isEmpty());
        var values=repo.earningsAggregates(user,null,first,next);
        assertEquals(2,values.size()); assertEquals(2,values.get(0).known());
        assertEquals(0,new BigDecimal("5").compareTo(values.get(0).dailyGain()));
        assertEquals(1,values.get(1).known());
        assertEquals(0,new BigDecimal("3").compareTo(values.get(1).dailyGain()));
        assertEquals(1,repo.earningsAggregates(user,"000001",first,next).size());
        assertTrue(repo.earningsDetails(other,List.of("000001"),first).isEmpty());
        assertEquals(Set.of("000001","000002"),repo.earningsDetails(user,List.of("000001","000002"),first).keySet());
    }
    @Test void sqlDoesNotConvertNullGainsOrReviewRequiredIntoKnownZero() {
        daily(user,"000001",next,"0","7",null);
        var v=repo.earningsAggregates(user,null,next,next).get(0);
        assertEquals(2,v.valued()); assertEquals(1,v.known());
        jdbc.update("INSERT INTO sim_position(user_id,fund_code,issue) VALUES (?,?,?)",user,"000002","synthetic-review");
        v=repo.earningsAggregates(user,null,next,next).get(0);
        assertEquals(1,v.valued()); assertEquals(0,v.known()); assertNull(v.dailyGain());
        assertTrue(repo.earningsFunds(user,"000002").get(0).reviewRequired());
    }
}
