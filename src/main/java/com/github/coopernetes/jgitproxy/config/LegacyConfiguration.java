package com.github.coopernetes.jgitproxy.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Legacy configuration to bring users of the old configuration into the new configuration. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LegacyConfiguration {}
