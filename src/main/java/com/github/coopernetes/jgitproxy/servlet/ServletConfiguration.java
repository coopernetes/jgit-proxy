package com.github.coopernetes.jgitproxy.servlet;

import com.github.coopernetes.jgitproxy.config.GitProxyProperties;
import com.github.coopernetes.jgitproxy.provider.GitProxyProvider;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.mitre.dsmiley.httpproxy.ProxyServlet;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@Slf4j
public class ServletConfiguration {

    @Bean
    public static BeanFactoryPostProcessor setupServlets(
            ApplicationContext applicationContext, Environment environment) {
        BindResult<GitProxyProperties> bindResult = Binder.get(environment).bind("git-proxy", GitProxyProperties.class);
        GitProxyProperties properties = bindResult.get();
        return (beanFactory) -> {
            var providerBeanMap = applicationContext.getBeansOfType(GitProxyProvider.class);
            List<ServletRegistrationBean<GitProxyProviderServlet>> registrations = new ArrayList<>();

            for (var providerBean : providerBeanMap.entrySet()) {
                var individualProps = properties.getProviders().get(providerBean.getKey());
                if (individualProps != null) {
                    registrations.add(createRegistration(providerBean.getValue(), individualProps));
                } else {
                    registrations.add(createRegistration(providerBean.getValue()));
                }
            }
            registrations.forEach(reg -> beanFactory.registerSingleton(reg.getServletName(), reg));
        };
    }

    private static ServletRegistrationBean<GitProxyProviderServlet> createRegistration(GitProxyProvider provider) {
        return createRegistration(provider, null);
    }

    private static ServletRegistrationBean<GitProxyProviderServlet> createRegistration(
            GitProxyProvider provider, GitProxyProperties.Provider properties) {
        var logProxy = Boolean.toString(true);
        var connectTimeout = Integer.toString(-1);
        var readTimeout = Integer.toString(-1);
        if (properties != null) {
            logProxy = Boolean.toString(properties.isLogProxy());
            connectTimeout = Integer.toString(properties.getConnectTimeout());
            readTimeout = Integer.toString(properties.getReadTimeout());
        }
        log.info("Creating proxy servlet for provider: {}", provider);
        var proxyServlet = new GitProxyProviderServlet(provider);
        var registration = new ServletRegistrationBean<>(proxyServlet, provider.servletPath());
        registration.setName(provider.getName() + "servlet");
        registration.addInitParameter(
                ProxyServlet.P_TARGET_URI, provider.getUri().toString());
        registration.addInitParameter(ProxyServlet.P_CONNECTTIMEOUT, connectTimeout);
        registration.addInitParameter(ProxyServlet.P_READTIMEOUT, readTimeout);
        registration.addInitParameter(ProxyServlet.P_LOG, logProxy);
        return registration;
    }
}
