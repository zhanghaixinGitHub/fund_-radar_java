package com.fundradar.core.simulation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;

@Configuration
public class SimulationConfiguration {
    @Bean public Clock simulationClock() { return Clock.systemUTC(); }
    @Bean public TransactionTemplate simulationTransaction(PlatformTransactionManager manager) { return new TransactionTemplate(manager); }
}
