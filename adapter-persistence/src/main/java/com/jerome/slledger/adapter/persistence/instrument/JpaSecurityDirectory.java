package com.jerome.slledger.adapter.persistence.instrument;

import com.jerome.slledger.application.port.SecurityDirectory;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.instrument.Security;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class JpaSecurityDirectory implements SecurityDirectory {

  private final SecurityJpaRepository jpa;

  JpaSecurityDirectory(SecurityJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Optional<Security> find(Isin isin) {
    return jpa.findById(isin.value()).map(SecurityMapper::toDomain);
  }
}
