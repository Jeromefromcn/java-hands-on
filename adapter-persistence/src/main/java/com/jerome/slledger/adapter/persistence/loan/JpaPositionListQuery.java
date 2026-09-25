package com.jerome.slledger.adapter.persistence.loan;

import com.jerome.slledger.adapter.persistence.support.MoneyColumns;
import com.jerome.slledger.application.port.PositionListQuery;
import com.jerome.slledger.application.port.PositionPage;
import com.jerome.slledger.application.port.PositionRow;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.party.CounterpartyId;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * The listing as a JPQL projection: only the columns a row shows, no entity loaded, nothing managed
 * by the persistence context, so nothing a caller does with a row can be flushed back.
 */
@Repository
public class JpaPositionListQuery implements PositionListQuery {

  private final EntityManager entityManager;

  JpaPositionListQuery(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public PositionPage page(int page, int size) {
    List<PositionRow> rows =
        entityManager
            .createQuery(
                "select new com.jerome.slledger.adapter.persistence.loan.PositionRowColumns("
                    + "p.id, p.counterpartyId, p.isin, p.status, p.originalQuantity,"
                    + " p.outstandingQuantity, p.collateralAmount, p.collateralCurrency)"
                    + " from LoanPositionEntity p order by p.id",
                PositionRowColumns.class)
            .setFirstResult(page * size)
            .setMaxResults(size)
            .getResultStream()
            .map(JpaPositionListQuery::toRow)
            .toList();
    long total =
        entityManager
            .createQuery("select count(p) from LoanPositionEntity p", Long.class)
            .getSingleResult();
    return new PositionPage(rows, page, size, total);
  }

  private static PositionRow toRow(PositionRowColumns columns) {
    return new PositionRow(
        new LoanPositionId(columns.id()),
        new CounterpartyId(columns.counterpartyId()),
        new Isin(columns.isin().trim()),
        columns.status(),
        columns.originalQuantity(),
        columns.outstandingQuantity(),
        MoneyColumns.read(columns.collateralAmount(), columns.collateralCurrency()));
  }
}
