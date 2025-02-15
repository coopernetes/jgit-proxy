package com.github.coopernetes.jgitproxy.servlet.filter;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class WhitelistFilterProperties extends FilterProperties {

    private List<String> owners = new ArrayList<>();
    private List<String> names = new ArrayList<>();
    private List<String> slugs = new ArrayList<>();

    public List<String> getWhitelistForTarget(AuthorizedByUrlFilter.Target target) {
        return switch (target) {
            case OWNER -> owners;
            case NAME -> names;
            case SLUG -> slugs;
        };
    }
}
