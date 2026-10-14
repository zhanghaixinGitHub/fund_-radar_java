package com.fundradar.core.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.auth.AccountRole;
import com.fundradar.core.auth.AuthenticatedUser;
import com.fundradar.core.auth.CurrentUserContext;
import com.fundradar.core.auth.PermissionCode;
import com.fundradar.core.auth.service.AuthenticationRequiredException;
import com.fundradar.core.direction1d.Direction1dRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 回看仍受本人关注授权约束，且读取不能写入正式预测引用或核验结果。 */
class AnalysisReviewTests {
    private final ObjectMapper json = new ObjectMapper();
    private final MultiPredictionClient client = mock(MultiPredictionClient.class);
    private final Direction1dRepository scope = mock(Direction1dRepository.class);
    private final JdbcClient database = mock(JdbcClient.class);
    private final MultiPredictionService service = new MultiPredictionService(client, scope, database, json);
    private final UUID user = UUID.randomUUID();

    @AfterEach void clear() { CurrentUserContext.clear(); }

    private void login() {
        CurrentUserContext.set(new AuthenticatedUser(user, "isolated", "隔离测试", AccountRole.FUND_USER,
                Set.of(PermissionCode.FUND_READ, PermissionCode.WATCHLIST_SELF_READ)));
    }

    @Test void loginAndCurrentWatchAreRequiredBeforeCallingAnalysisService() {
        assertThrows(AuthenticationRequiredException.class, () -> service.analysisReviews("016008", null, null));
        login();
        assertThrows(NoSuchElementException.class, () -> service.analysisReviews("016008", null, null));
        verifyNoInteractions(client, database);
    }

    @Test void readingOwnReviewPreservesActualTimeAndNeverLinksAsForecast() throws Exception {
        login(); when(scope.follows(user, "016008")).thenReturn(true);
        var result = json.readTree("""
          {"items":[{"recordType":"COMPREHENSIVE_ASOF_REVIEW","fundCode":"016008",
          "excludedFromAccuracy":true,"actualGeneratedAt":"2026-10-14T16:12:58+08:00"}],"nextCursor":null}
          """);
        when(client.get("/funds/016008/analysis-reviews")).thenReturn(result);
        assertEquals(result, service.analysisReviews("016008", null, null));
        verifyNoInteractions(database);
    }

    @Test void wrongFundOrPredictionTypeOrAccuracyFlagIsRejected() throws Exception {
        login(); when(scope.follows(user, "016008")).thenReturn(true);
        for (String body : new String[] {
                "{\"fundCode\":\"002112\",\"recordType\":\"COMPREHENSIVE_ASOF_REVIEW\",\"excludedFromAccuracy\":true}",
                "{\"fundCode\":\"016008\",\"recordType\":\"FORECAST\",\"excludedFromAccuracy\":true}",
                "{\"fundCode\":\"016008\",\"recordType\":\"COMPREHENSIVE_ASOF_REVIEW\",\"excludedFromAccuracy\":false}"}) {
            when(client.get("/funds/016008/analysis-reviews")).thenReturn(json.readTree("{\"items\":[" + body + "]}"));
            assertThrows(IllegalStateException.class, () -> service.analysisReviews("016008", null, null));
        }
        verifyNoInteractions(database);
    }

    @Test void partialCursorIsRejectedBeforeUpstreamRead() {
        login(); when(scope.follows(user, "016008")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service.analysisReviews("016008", null, UUID.randomUUID()));
        verifyNoInteractions(client, database);
    }
}
