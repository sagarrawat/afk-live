package com.afklive.streamer.config;

import org.jobrunr.configuration.JobRunr;
import org.jobrunr.dashboard.JobRunrDashboardWebServer;
import org.jobrunr.jobs.mappers.JobMapper;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.server.BackgroundJobServer;
import org.jobrunr.server.JobActivator;
import org.jobrunr.storage.StorageProvider;
import org.jobrunr.storage.sql.common.SqlStorageProviderFactory;
import org.jobrunr.utils.mapper.jackson.JacksonJsonMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class JobRunrConfig {

    @Bean
    public JobMapper jobMapper() {
        return new JobMapper(new JacksonJsonMapper());
    }

    @Bean
    public StorageProvider storageProvider(DataSource dataSource, JobMapper jobMapper) {
        StorageProvider storageProvider = SqlStorageProviderFactory.using(dataSource);
        storageProvider.setJobMapper(jobMapper);
        return storageProvider;
    }

    @Bean
    public JobScheduler jobScheduler(StorageProvider storageProvider) {
        return new JobScheduler(storageProvider);
    }

    @Bean
    public JobActivator jobActivator(ApplicationContext applicationContext) {
        return applicationContext::getBean;
    }

    @Bean
    public BackgroundJobServer backgroundJobServer(StorageProvider storageProvider, JobActivator jobActivator) {
        BackgroundJobServer backgroundJobServer = new BackgroundJobServer(storageProvider, new JacksonJsonMapper(), jobActivator);
        backgroundJobServer.start();
        return backgroundJobServer;
    }

    @Bean
    public JobRunrDashboardWebServer jobRunrDashboardWebServer(StorageProvider storageProvider) {
        JobRunrDashboardWebServer dashboard = new JobRunrDashboardWebServer(storageProvider, new JacksonJsonMapper());
        dashboard.start();
        return dashboard;
    }
}
