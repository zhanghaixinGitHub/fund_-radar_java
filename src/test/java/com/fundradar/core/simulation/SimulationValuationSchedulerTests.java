package com.fundradar.core.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.sql.DataSource;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import static org.mockito.Mockito.*;

class SimulationValuationSchedulerTests {
    @TempDir Path folder;
    SimulationValuationService service=mock(SimulationValuationService.class);
    DataSource source=mock(DataSource.class);
    Connection connection=mock(Connection.class);
    PreparedStatement lock=mock(PreparedStatement.class),release=mock(PreparedStatement.class);
    ResultSet result=mock(ResultSet.class);
    UUID owner=UUID.randomUUID();
    SimulationValuationScheduler scheduler;
    @BeforeEach void setup() throws Exception {
        Path file=folder.resolve("account.json"); Files.writeString(file,"{\"owner_user_id\":\""+owner+"\"}");
        scheduler=new SimulationValuationScheduler(service,source,new ObjectMapper(),file.toString());
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("pg_try_advisory_lock"))).thenReturn(lock);
        when(connection.prepareStatement(contains("pg_advisory_unlock"))).thenReturn(release);
        when(lock.executeQuery()).thenReturn(result); when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(true);
        when(service.refresh(owner)).thenReturn(new SimulationValuationService.Result(1,0,false));
    }
    @Test void onlyBoundOwnerIsRefreshedAndGlobalLockIsReleased() throws Exception {
        scheduler.tick(); verify(service).refresh(owner); verify(release).execute(); verify(connection).close();
        verifyNoMoreInteractions(service);
    }
    @Test void busySettlementIsNotInterrupted() throws Exception {
        when(result.getBoolean(1)).thenReturn(false); scheduler.tick();
        verifyNoInteractions(service); verify(release,never()).execute();
    }
    @Test void storageFailureStopsFurtherAttempts() throws Exception {
        when(source.getConnection()).thenThrow(new SQLException("synthetic storage failure","58030"));
        scheduler.tick(); scheduler.tick(); verify(source,times(1)).getConnection(); verifyNoInteractions(service);
    }
    @Test void failedRefreshStillReleasesLockAndCanRetry() throws Exception {
        when(service.refresh(owner)).thenThrow(new IllegalStateException("synthetic unavailable"))
                .thenReturn(new SimulationValuationService.Result(1,0,false));
        scheduler.tick(); scheduler.tick(); verify(service,times(2)).refresh(owner); verify(release,times(2)).execute();
    }
}
