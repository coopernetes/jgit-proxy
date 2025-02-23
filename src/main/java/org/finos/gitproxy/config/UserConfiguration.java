package org.finos.gitproxy.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.PathResource;

@Configuration
@Slf4j
@EnableConfigurationProperties(GitProxyProperties.class)
public class UserConfiguration {

    // TODO: Remove once migrated over to configuration properties
    // https://github.com/finos/git-proxy/blob/363f4ae0588c02b32c8bb3d987919bc0b4268d12/index.js#L12-L17
    @Bean
    public LegacyJSONConfiguration legacyConfiguration(ApplicationArguments args, ObjectMapper objectMapper)
            throws IOException {
        String configFileArg = "";
        // if the "Spring way" of specifying the config file, use it
        if (args.containsOption("config") && !args.getOptionValues("config").isEmpty()) {
            configFileArg = args.getOptionValues("config").get(0);
        }
        // if "-c" or "--config" is used, use it
        for (int i = 0; i < args.getSourceArgs().length; i++) {
            if ((args.getSourceArgs()[i].equals("--config") || args.getSourceArgs()[i].equals("-c"))
                    && i + 1 < args.getSourceArgs().length) {
                configFileArg = args.getSourceArgs()[i + 1];
            }
        }
        if (configFileArg.isEmpty()) {
            log.debug("No configuration file argument found, creating an empty legacy config.");
            return new LegacyJSONConfiguration();
        }
        var resource = new PathResource(configFileArg);
        if (!resource.exists() || !resource.isReadable()) {
            log.info("No configuration file found or is unreadable at {}.", configFileArg);
            return new LegacyJSONConfiguration();
        }
        log.warn(
                "Legacy configuration file argument detected ({}), this method of configuring the server is considered deprecated and will be removed in a future release.",
                configFileArg);
        return objectMapper.readValue(resource.getFile(), LegacyJSONConfiguration.class);
    }
}
