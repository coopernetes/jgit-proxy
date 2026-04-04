package org.finos.gitproxy.dashboard.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Forwards all non-API, non-static requests to {@code index.html} so that React Router's BrowserRouter can handle
 * client-side navigation on direct URL loads and refreshes.
 */
@Controller
public class SpaController {

    @RequestMapping(value = {"/", "/push/**", "/providers", "/repos"})
    public String spa() {
        return "forward:/index.html";
    }
}
