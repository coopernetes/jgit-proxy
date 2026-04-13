package org.finos.gitproxy.jetty.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Shared YAML DTO for literal and regex-pattern block lists. Used by both {@code commit.message.block} and
 * {@code diff-scan.block}.
 */
@Data
public class BlockSettings {
    private List<String> literals = new ArrayList<>();
    private List<String> patterns = new ArrayList<>();
}
