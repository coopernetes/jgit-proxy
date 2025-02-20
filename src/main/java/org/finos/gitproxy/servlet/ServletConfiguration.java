package org.finos.gitproxy.servlet;

import org.finos.gitproxy.config.GitProxyProperties;
import org.finos.gitproxy.provider.GitProxyProvider;
import org.finos.gitproxy.provider.ProviderRepository;
import lombok.extern.slf4j.Slf4j;
import org.mitre.dsmiley.httpproxy.ProxyServlet;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

@Configuration
@Slf4j
public class ServletConfiguration {

    @Bean
    public static BeanFactoryPostProcessor setupServlets(Environment environment) {
        BindResult<GitProxyProperties> bindResult = Binder.get(environment).bind("git-proxy", GitProxyProperties.class);
        GitProxyProperties properties = bindResult.get();
        return (beanFactory) -> {
            var providerRepository = beanFactory.getBean(ProviderRepository.class);

            List<ServletRegistrationBean<GitProxyProviderServlet>> registrations = new ArrayList<>();
            for (var providerBean : providerRepository.getProviders()) {
                var individualProps = properties.getProviders().get(providerBean.getName());
                if (individualProps != null) {
                    registrations.add(createRegistration(providerBean, individualProps));
                } else {
                    registrations.add(createRegistration(providerBean));
                }
            }
            registrations.forEach(reg -> beanFactory.registerSingleton(reg.getServletName(), reg));
        };
    }

    private static ServletRegistrationBean<GitProxyProviderServlet> createRegistration(GitProxyProvider provider) {
        return createRegistration(provider, null);
    }

    private static ServletRegistrationBean<GitProxyProviderServlet> createRegistration(
            GitProxyProvider provider, GitProxyProperties.Provider cfg) {
        var logProxy = Boolean.toString(true);
        var connectTimeout = Integer.toString(-1);
        var readTimeout = Integer.toString(-1);
        if (cfg != null) {
            logProxy = Boolean.toString(cfg.isLogProxy());
            connectTimeout = Integer.toString(cfg.getConnectTimeout());
            readTimeout = Integer.toString(cfg.getReadTimeout());
        }
        log.info("Creating proxy servlet for provider: {}", provider);
        var proxyServlet = new GitProxyProviderServlet(provider);
        var registration = new ServletRegistrationBean<>(proxyServlet, provider.servletMapping());
        registration.setName(provider.getName() + "servlet");
        registration.addInitParameter(
                ProxyServlet.P_TARGET_URI, provider.getUri().toString());
        registration.addInitParameter(ProxyServlet.P_CONNECTTIMEOUT, connectTimeout);
        registration.addInitParameter(ProxyServlet.P_READTIMEOUT, readTimeout);
        registration.addInitParameter(ProxyServlet.P_LOG, logProxy);
        return registration;
    }
}
