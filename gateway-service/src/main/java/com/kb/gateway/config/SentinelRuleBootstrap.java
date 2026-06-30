package com.kb.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Bootstrap for loading Sentinel gateway flow rules from Nacos.
 * <p>
 * Uses Nacos {@link ConfigService} (gRPC transport) to fetch and listen for
 * {@code gateway-sentinel-rules} config changes in the {@code kb-platform}
 * namespace. Parses JSON into {@link GatewayFlowRule} objects and loads them
 * into {@link GatewayRuleManager}.
 * <p>
 * Rule changes in Nacos are pushed via gRPC and take effect immediately
 * without gateway restart.
 * <p>
 * <b>Fallback:</b> If Nacos is unreachable or the config data ID does not
 * exist, loads rules from {@code classpath:sentinel/gateway-sentinel-rules.json}
 * as a seed default. This ensures the gateway always starts with rate-limiting
 * protection (fail-safe) rather than with zero rules (fail-open).
 */
@Slf4j
@Configuration
@ConditionalOnProperty(
    name = "sentinel.datasource.bootstrap.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SentinelRuleBootstrap {

    @Value("${NACOS_SERVER_ADDR:localhost:8848}")
    private String nacosAddr;

    @Value("${NACOS_NAMESPACE:kb-platform}")
    private String namespace;

    private static final String DATA_ID = "gateway-sentinel-rules";
    private static final String GROUP = "DEFAULT_GROUP";
    private static final String SEED_PATH = "sentinel/gateway-sentinel-rules.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void initGatewayRules() {
        log.info("Loading Sentinel gateway flow rules from Nacos: {} namespace={} dataId={}",
                nacosAddr, namespace, DATA_ID);

        try {
            // 1. Try Nacos first
            Properties props = new Properties();
            props.setProperty("serverAddr", nacosAddr);
            props.setProperty("namespace", namespace);
            ConfigService configService = NacosFactory.createConfigService(props);

            // 2. Fetch initial config from Nacos
            String config = configService.getConfig(DATA_ID, GROUP, 5000);
            if (config != null && !config.isBlank()) {
                loadRules(config, "Nacos");
            } else {
                log.warn("Nacos config '{}' not found in namespace '{}', falling back to seed file.",
                        DATA_ID, namespace);
                loadSeedRules();
            }

            // 3. Register gRPC listener for dynamic rule updates
            configService.addListener(DATA_ID, GROUP, new Listener() {
                @Override
                public Executor getExecutor() {
                    return null; // use default executor
                }

                @Override
                public void receiveConfigInfo(String newConfig) {
                    log.info("Sentinel rules changed in Nacos, reloading...");
                    if (newConfig != null && !newConfig.isBlank()) {
                        loadRules(newConfig, "Nacos");
                    } else {
                        // Config deleted → fall back to seed rules (fail-safe)
                        GatewayRuleManager.loadRules(new HashSet<>());
                        loadSeedRules();
                        log.info("Nacos config deleted, reverted to seed rules");
                    }
                }
            });

        } catch (NacosException e) {
            log.error("Failed to connect to Nacos at {}: {}. Falling back to seed rules.",
                    nacosAddr, e.getMessage());
            loadSeedRules();
        }
    }

    /**
     * Load rules from the classpath seed file as a fail-safe default.
     * <p>
     * Seed rules provide baseline protection when Nacos is unavailable.
     * They can be overridden at runtime by pushing rules via Nacos.
     */
    private void loadSeedRules() {
        try {
            ClassPathResource resource = new ClassPathResource(SEED_PATH);
            try (InputStream is = resource.getInputStream()) {
                String config = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                loadRules(config, "seed-file (classpath:" + SEED_PATH + ")");
            }
        } catch (Exception e) {
            log.error("Failed to load seed rules from classpath:{}: {}. "
                    + "Gateway will start with NO rate limiting!", SEED_PATH, e.getMessage());
        }
    }

    private void loadRules(String config, String source) {
        try {
            List<GatewayFlowRule> list = objectMapper.readValue(
                    config, new TypeReference<List<GatewayFlowRule>>() {
                    });
            Set<GatewayFlowRule> rules = new HashSet<>(list);
            GatewayRuleManager.loadRules(rules);
            log.info("Loaded {} Sentinel gateway flow rules from {}:", rules.size(), source);
            for (GatewayFlowRule rule : rules) {
                log.info("  resource={}, count={}, grade={}, intervalSec={}",
                        rule.getResource(), rule.getCount(),
                        rule.getGrade(), rule.getIntervalSec());
            }
        } catch (Exception e) {
            log.error("Failed to parse Sentinel gateway rules from {}: {}", source, e.getMessage());
        }
    }
}
