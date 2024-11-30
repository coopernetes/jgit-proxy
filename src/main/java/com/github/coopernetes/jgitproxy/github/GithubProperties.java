package com.github.coopernetes.jgitproxy.github;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "git-proxy.github")
@Getter
@Setter
public class GithubProperties {
    private Fetch fetch;
    private Push push;

    @Getter
    @Setter
    public static class Fetch {
        private List<String> authorisedOwners;
    }

    @Getter
    @Setter
    public static class Push {
        private List<String> authorisedOwners;
    }
}
