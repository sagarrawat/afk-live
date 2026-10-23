package com.afklive.streamer.config;

import com.afklive.streamer.util.SecretBox;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Loads the at-rest encryption key before any bean (or entity with a secret) is created. */
@Configuration
public class EncryptionConfig {

    @Bean
    static BeanFactoryPostProcessor encryptionKeyLoader() {
        return new KeyLoader();
    }

    static final class KeyLoader implements BeanFactoryPostProcessor, EnvironmentAware {
        @Override
        public void setEnvironment(Environment environment) {
            SecretBox.configure(environment.getProperty("app.encryption-key", ""));
        }

        @Override
        public void postProcessBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
            // the key is loaded in setEnvironment, which runs first
        }
    }
}
