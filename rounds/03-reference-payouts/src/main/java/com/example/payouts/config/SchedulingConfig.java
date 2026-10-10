package com.example.payouts.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/**
 * WHY: @Scheduled runs on EVERY instance. With three replicas, three payout jobs would pick the same rows.
 * ShedLock stores a lease in the database (works on MySQL, PostgreSQL, Oracle, SQL Server), so only one
 * instance runs a given job at a time.
 *
 * PAY ATTENTION: a lease is not a guarantee. If a run takes longer than lockAtMostFor, another instance
 * may start. That is why every job here is ALSO safe to run twice: rows are claimed with conditional updates.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()   // one clock (the database's) for all instances
                        .build());
    }
}
