package com.jerome.slledger.application.port;

import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.instrument.Security;
import java.util.Optional;

/** Reference data about securities, including the market price a trade is booked at. */
public interface SecurityDirectory {

  Optional<Security> find(Isin isin);
}
