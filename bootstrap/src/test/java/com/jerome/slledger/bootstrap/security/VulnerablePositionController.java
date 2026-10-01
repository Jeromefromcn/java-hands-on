package com.jerome.slledger.bootstrap.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoint a team writes when the request body is the entity: every column is a property, so
 * the client chooses them all, including the id that decides which row is written and the status
 * the server alone should set. {@code save} is an upsert by id, which is what {@code
 * JpaRepository.save} does with an entity whose id is set. Test-only, registered by the test that
 * reproduces the attack; not on the production classpath. See docs/decisions/13-security.md.
 */
@RestController
public class VulnerablePositionController {

  /** What a client was meant to send is {@code quantity}; the rest is whatever it likes. */
  public static class PositionBody {
    public String id;
    public String counterpartyId;
    public long quantity;
    public String status;
  }

  private final JdbcClient jdbc;

  VulnerablePositionController(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @PostMapping("/vulnerable/positions")
  @PreAuthorize("hasRole('ledger-trader')")
  public void save(@RequestBody PositionBody body) {
    jdbc.sql(
            "INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
                + " outstanding_quantity, status, collateral_amount, collateral_currency,"
                + " rule_type, flat_rate) VALUES (?, ?, 'HK0000000007', ?, ?, ?, 1.00, 'HKD',"
                + " 'FLAT', 0.0100) ON CONFLICT (id) DO UPDATE SET"
                + " counterparty_id = EXCLUDED.counterparty_id, status = EXCLUDED.status,"
                + " outstanding_quantity = EXCLUDED.outstanding_quantity")
        .params(body.id, body.counterpartyId, body.quantity, body.quantity, body.status)
        .update();
  }
}
