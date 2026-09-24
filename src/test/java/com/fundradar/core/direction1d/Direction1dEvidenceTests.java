package com.fundradar.core.direction1d;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.auth.*;
import com.fundradar.core.auth.service.AuthenticationRequiredException;
import com.fundradar.core.watchlist.service.WatchlistRequiredException;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 依据必须属于本人同一基金、同一原文；只读入口不调用生成或归档。 */
class Direction1dEvidenceTests {
    final Direction1dRepository repo=mock(Direction1dRepository.class);
    final Direction1dClient client=mock(Direction1dClient.class);
    final Direction1dService service=new Direction1dService(repo,client);
    final UUID user=UUID.randomUUID(),forecast=UUID.randomUUID(),job=UUID.randomUUID();
    @BeforeEach void login(){CurrentUserContext.set(new AuthenticatedUser(user,"test","测试",AccountRole.FUND_USER,
            Set.of(PermissionCode.FUND_READ,PermissionCode.WATCHLIST_SELF_READ)));}
    @AfterEach void clear(){CurrentUserContext.clear();}
    @Test void requiresLoginAndFollowScopeBeforeReadingEvidence(){
        CurrentUserContext.clear();
        assertThrows(AuthenticationRequiredException.class,()->service.evidence("008888",forecast));
        verifyNoInteractions(repo,client);
        login();assertThrows(WatchlistRequiredException.class,()->service.evidence("008888",forecast));
        verifyNoInteractions(client);
    }
    @Test void anotherUsersRecordCannotReachPython(){
        when(repo.follows(user,"008888")).thenReturn(true);
        when(repo.evidenceSource(user,"008888",forecast)).thenThrow(new NoSuchElementException());
        assertThrows(NoSuchElementException.class,()->service.evidence("008888",forecast));verifyNoInteractions(client);
    }
    @Test void acceptsOnlySameFundAndExactOriginalHash() throws Exception {
        when(repo.follows(user,"008888")).thenReturn(true);
        when(repo.evidenceSource(user,"008888",forecast)).thenReturn(Map.of("source_job_id",job,"content_hash","original"));
        var json=new ObjectMapper();String path="/forecast-jobs/"+job+"/evidence";
        var valid=json.readTree("{\"fundCode\":\"008888\",\"contentHash\":\"original\"}");
        when(client.get(path)).thenReturn(valid);assertEquals(valid,service.evidence("008888",forecast));
        when(client.get(path)).thenReturn(json.readTree("{\"fundCode\":\"000001\",\"contentHash\":\"original\"}"));
        assertThrows(IllegalStateException.class,()->service.evidence("008888",forecast));
        when(client.get(path)).thenReturn(json.readTree("{\"fundCode\":\"008888\",\"contentHash\":\"wrong\"}"));
        assertThrows(IllegalStateException.class,()->service.evidence("008888",forecast));
        verify(client,never()).post(anyString(),any());
    }
}
