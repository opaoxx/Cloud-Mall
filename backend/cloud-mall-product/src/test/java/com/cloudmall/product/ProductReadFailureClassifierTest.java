package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cloudmall.product.config.ProductReadExceptionHandler;
import com.cloudmall.product.util.ProductReadFailureClassifier;
import com.cloudmall.product.util.ProductReadFailureClassifier.Reason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/** 证明包装层、循环链和未知错误不会混淆依赖故障分类。 */
class ProductReadFailureClassifierTest {
  @Test
  void innerSqlTimeoutWinsOverRecoverableWrapper() {
    assertEquals(
        Reason.SQL_TIMEOUT,
        ProductReadFailureClassifier.classify(
            new RecoverableDataAccessException(
                "recoverable", new SQLTimeoutException("query timed out"))));
  }

  @Test
  void jdbcSocketTimeoutIsExecutionTimeoutButPlainNetworkTimeoutIsUnknown() {
    SQLException communication =
        new SQLException("lost response", "08S01", new SocketTimeoutException());
    assertEquals(
        Reason.SQL_TIMEOUT,
        ProductReadFailureClassifier.classify(
            new RecoverableDataAccessException("lost response", communication)));
    assertEquals(
        Reason.UNKNOWN, ProductReadFailureClassifier.classify(new SocketTimeoutException()));
  }

  @Test
  void connectionAcquireEvidenceWinsOverAnUnderlyingHandshakeTimeout() {
    assertEquals(
        Reason.CONNECTION_ACQUIRE,
        ProductReadFailureClassifier.classify(
            new CannotGetJdbcConnectionException(
                "pool failed",
                new SQLException("connect", "08001", new SocketTimeoutException()))));
  }

  @Test
  void connectionFailureDuringQueryIsNotAutomaticallyAPoolTimeout() {
    assertEquals(
        Reason.DB_CONNECTION,
        ProductReadFailureClassifier.classify(
            new SQLNonTransientConnectionException("connection lost", "08006")));
  }

  @Test
  void transientTransactionConnectionFailureNeedsPoolEvidence() {
    assertEquals(
        Reason.DB_CONNECTION,
        ProductReadFailureClassifier.classify(
            new CannotCreateTransactionException(
                "begin failed",
                new SQLTransientConnectionException("transient link failure", "08001"))));
  }

  @Test
  void bareTimeoutAndUnknownTransactionTimeoutHaveNoQueryEvidence() {
    assertEquals(
        Reason.UNKNOWN,
        ProductReadFailureClassifier.classify(new SQLTimeoutException("unknown phase")));
    assertEquals(
        Reason.UNKNOWN,
        ProductReadFailureClassifier.classify(
            new TransactionSystemException(
                "unknown phase", new SQLTimeoutException("unknown phase"))));
  }

  @Test
  void unknownJdbcTransactionAndSyntaxErrorsStayUnknown() {
    assertEquals(
        Reason.UNKNOWN,
        ProductReadFailureClassifier.classify(new SQLException("unknown", "HY000")));
    assertEquals(
        Reason.UNKNOWN,
        ProductReadFailureClassifier.classify(new TransactionSystemException("unknown")));
    assertEquals(
        Reason.UNKNOWN,
        ProductReadFailureClassifier.classify(
            new BadSqlGrammarException("query", "bad sql", new SQLException("syntax", "42000"))));
  }

  @Test
  void malformedCauseChainsAreBounded() {
    Throwable cycle =
        new RuntimeException() {
          @Override
          public synchronized Throwable getCause() {
            return this;
          }
        };
    assertEquals(Reason.UNKNOWN, ProductReadFailureClassifier.classify(cycle));
    Throwable longChain = new CannotGetJdbcConnectionException("connection failed");
    for (int depth = 0; depth < 40; depth++) longChain = new RuntimeException("wrapper", longChain);
    assertEquals(Reason.UNKNOWN, ProductReadFailureClassifier.classify(longChain));
  }

  @Test
  void writeRequestKeeps500AndDoesNotPolluteReadFailureCounter() {
    SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    var result =
        new ProductReadExceptionHandler(metrics)
            .databaseUnavailable(
                new CannotGetJdbcConnectionException("unavailable"),
                new MockHttpServletRequest("POST", "/api/products"));
    assertEquals(500, result.getStatusCodeValue());
    assertEquals("COMMON_INTERNAL_ERROR", result.getBody().code);
    assertEquals(0, metrics.getMeters().size());
  }
}
